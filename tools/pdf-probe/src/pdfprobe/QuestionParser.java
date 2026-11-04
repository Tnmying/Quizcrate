package pdfprobe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts reconstructed lines and underline spans into typed questions. */
public final class QuestionParser {
    private static final Pattern HEADING =
            Pattern.compile("^([一二三四五六七八九十]+)、(.+?)\\s*$");
    private static final Pattern QUESTION_START =
            Pattern.compile("^(\\d{1,3})\\s*[.．]\\s*(.*)$");
    private static final Pattern NUMBER_PREFIX =
            Pattern.compile("^\\s*\\d{1,3}\\s*[.．]\\s*");
    private static final Pattern OPTION_MARKER =
            Pattern.compile("(?m)(^|\\s)([A-Z])[.．]\\s*");
    private static final Pattern UNSUPPORTED_OPTION_MARKER =
            Pattern.compile("(?m)(^|\\s)([A-Z]{2,})[.．]");
    private static final Pattern ANSWER =
            Pattern.compile("[（(]\\s*([A-Za-z]{1,26}|[√×✓✗✔✘])\\s*[）)]");
    private static final String OPTION_KEYS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private QuestionParser() {}

    public static Model.ParseResult parse(Model.Geometry geometry) {
        Model.ParseResult result = new Model.ParseResult();
        List<Model.Line> content = new ArrayList<>();
        for (Model.Line line : geometry.lines) {
            if (!line.footer && !line.text.trim().isEmpty()) content.add(line);
        }

        Model.Section currentSection = null;
        RawQuestion current = null;
        Map<String, Integer> counters = new HashMap<>();

        for (int i = 0; i < content.size(); i++) {
            Model.Line line = content.get(i);
            String text = line.text.trim();

            Matcher heading = HEADING.matcher(text);
            if (heading.matches()) {
                if (current != null) {
                    finish(current, result, counters);
                    current = null;
                }
                Model.Section section = new Model.Section();
                section.title = text;
                section.type = sectionType(heading.group(2));
                section.page = line.page;
                result.sections.add(section);
                currentSection = section;
                continue;
            }

            if (currentSection == null) continue;

            Matcher start = QUESTION_START.matcher(text);
            if (start.matches()) {
                if (current != null) {
                    finish(current, result, counters);
                    current = null;
                }
                String numberText = start.group(1);
                String body = start.group(2).trim();
                if (body.isEmpty() && hasSameNumberAhead(content, i, numberText)) {
                    Model.Anomaly a = new Model.Anomaly();
                    a.type = "orphan_number";
                    a.section = currentSection.type;
                    a.number = safeInt(numberText);
                    a.page = line.page;
                    a.line = line.lineNo;
                    a.detail = "bare question number before the same numbered question; skipped as page-break artifact";
                    result.anomalies.add(a);
                    continue;
                }

                current = new RawQuestion();
                current.geometry = geometry;
                current.section = currentSection;
                current.type = currentSection.type;
                current.originalNumber = numberText;
                current.numberValue = safeInt(numberText);
                current.emptyStart = body.isEmpty();
                current.lines.add(line);
                continue;
            }

            if (current != null) current.lines.add(line);
        }
        if (current != null) finish(current, result, counters);

        addNumberingAnomalies(result);
        addUnderlineAnomalies(result, geometry);
        return result;
    }

    private static void finish(RawQuestion raw, Model.ParseResult result,
                               Map<String, Integer> counters) {
        if (raw.lines.isEmpty()) return;
        Model.Question q = parseRaw(raw);
        if (q == null) return;
        int count = counters.getOrDefault(q.type, 0) + 1;
        counters.put(q.type, count);
        q.id = String.format("%s-%03d", q.type, count);
        result.questions.add(q);
        raw.section.count++;
    }

    private static Model.Question parseRaw(RawQuestion raw) {
        Model.Question q = new Model.Question();
        q.type = raw.type;
        q.sectionTitle = raw.section.title;
        q.originalNumber = raw.originalNumber;
        q.numberValue = raw.numberValue;
        for (Model.Line line : raw.lines) {
            Model.SourceLine source = new Model.SourceLine();
            source.page = line.page;
            source.line = line.lineNo;
            q.sourceLines.add(source);
            if (!q.sourcePages.contains(line.page)) q.sourcePages.add(line.page);
        }

        switch (raw.type) {
            case "single_choice":
            case "multiple_choice":
                parseChoice(raw, q);
                break;
            case "true_false":
                parseJudge(raw, q);
                break;
            case "fill_blank":
                parseFill(raw, q);
                break;
            case "short_answer":
                parseShort(raw, q);
                break;
            default:
                q.stem = compact(bodyWithBreaks(raw));
                q.stemWithAnswer = q.stem;
                addReview(q, "unknown_question_type");
        }

        if (q.stem == null || q.stem.trim().isEmpty()) addReview(q, "empty_stem");
        if (raw.emptyStart) addReview(q, "empty_question_start");
        q.needsReview = !q.reviewReasons.isEmpty();
        return q;
    }

    private static void parseChoice(RawQuestion raw, Model.Question q) {
        String body = bodyWithBreaks(raw);
        q.optionRawText = body;
        List<Marker> markers = optionMarkers(body);
        if (markers.isEmpty()) {
            q.stemWithAnswer = compact(body);
            q.stem = q.stemWithAnswer;
            addReview(q, "no_option_markers");
            return;
        }

        int first = markers.get(0).start;
        q.stemWithAnswer = compact(body.substring(0, first));
        Set<String> seenKeys = new HashSet<>();
        for (int i = 0; i < markers.size(); i++) {
            Marker marker = markers.get(i);
            int end = i + 1 < markers.size() ? markers.get(i + 1).start : body.length();
            Model.Option option = new Model.Option();
            option.key = marker.key;
            option.text = compact(body.substring(marker.end, end));
            option.markerIndex = i;
            option.duplicate = !seenKeys.add(marker.key);
            if (option.text.isEmpty()) addReview(q, "empty_option");
            q.options.add(option);
        }

        if (q.options.size() < 2) addReview(q, "too_few_options");
        if (q.options.size() > OPTION_KEYS.length()) addReview(q, "too_many_options");
        StringBuilder keys = new StringBuilder();
        for (Model.Option option : q.options) keys.append(option.key);
        String expected = OPTION_KEYS.substring(0, Math.min(OPTION_KEYS.length(), q.options.size()));
        if (!keys.toString().equals(expected)) addReview(q, "option_keys_not_sequential");
        for (Model.Option option : q.options) {
            if (option.duplicate) {
                addReview(q, "duplicate_option_marker");
            }
        }
        if (hasOutOfOrderMarker(q.options)) addReview(q, "option_markers_out_of_order");
        if (hasMarkerGap(q.options)) addReview(q, "option_marker_gap");
        Matcher unsupported = UNSUPPORTED_OPTION_MARKER.matcher(body);
        if (unsupported.find()) addReview(q, "unsupported_option_marker");

        Matcher answer = ANSWER.matcher(q.stemWithAnswer);
        int answerStart = -1;
        int answerEnd = -1;
        String token = null;
        while (answer.find()) {
            answerStart = answer.start();
            answerEnd = answer.end();
            token = answer.group(1);
        }
        if (token == null) {
            q.stem = q.stemWithAnswer;
            addReview(q, "no_answer_in_parentheses");
            return;
        }

        String letters = token.replaceAll("\\s", "").toUpperCase();
        Set<String> unique = new LinkedHashSet<>();
        for (int i = 0; i < letters.length(); i++) {
            char c = letters.charAt(i);
            if (c >= 'A' && c <= 'Z') unique.add(String.valueOf(c));
        }
        q.answers.addAll(unique);
        q.stem = q.stemWithAnswer.substring(0, answerStart) + "（ ）"
                + q.stemWithAnswer.substring(answerEnd);
        q.stem = q.stem.trim();

        if (q.answers.isEmpty()) addReview(q, "non_choice_answer_token");
        if ("single_choice".equals(q.type) && q.answers.size() != 1) {
            addReview(q, "single_choice_answer_count");
        }
        if ("multiple_choice".equals(q.type) && q.answers.size() < 2) {
            addReview(q, "multiple_choice_answer_count");
        }
        for (String letter : q.answers) {
            boolean found = false;
            for (Model.Option option : q.options) {
                if (option.key.equals(letter)) found = true;
            }
            if (!found) addReview(q, "answer_not_in_options");
        }
    }

    private static boolean hasOutOfOrderMarker(List<Model.Option> options) {
        for (int i = 1; i < options.size(); i++) {
            if (options.get(i).key.charAt(0) <= options.get(i - 1).key.charAt(0)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMarkerGap(List<Model.Option> options) {
        Set<String> keys = new HashSet<>();
        for (Model.Option option : options) keys.add(option.key);
        for (int i = 0; i < options.size(); i++) {
            char expected = (char) ('A' + i);
            if (!keys.contains(String.valueOf(expected))) return true;
        }
        return false;
    }

    private static void parseJudge(RawQuestion raw, Model.Question q) {
        q.stemWithAnswer = compact(bodyWithBreaks(raw));
        Matcher answer = ANSWER.matcher(q.stemWithAnswer);
        int start = -1;
        int end = -1;
        String token = null;
        while (answer.find()) {
            start = answer.start();
            end = answer.end();
            token = answer.group(1);
        }
        if (token == null || token.length() != 1
                || !"√×✓✗✔✘".contains(token)) {
            q.stem = q.stemWithAnswer;
            addReview(q, "no_true_false_symbol");
            return;
        }
        q.answers.add(token);
        q.stem = (q.stemWithAnswer.substring(0, start) + "（ ）"
                + q.stemWithAnswer.substring(end)).trim();
    }

    private static void parseShort(RawQuestion raw, Model.Question q) {
        String body = bodyWithBreaks(raw);
        int index = body.indexOf("参考答案");
        if (index < 0) {
            q.stem = compact(body);
            q.stemWithAnswer = q.stem;
            addReview(q, "no_reference_answer_marker");
            return;
        }
        q.stem = compact(body.substring(0, index));
        String answer = body.substring(index + "参考答案".length())
                .replaceFirst("^\\s*[：:]\\s*", "");
        q.referenceAnswer = compact(answer);
        q.stemWithAnswer = body.substring(0, index) + "参考答案：" + answer;
        if (q.referenceAnswer.isEmpty()) {
            addReview(q, "empty_reference_answer");
        } else {
            q.answers.add(q.referenceAnswer);
        }
    }

    private static void parseFill(RawQuestion raw, Model.Question q) {
        SentenceBuilder sentence = new SentenceBuilder(raw);
        q.originalSentence = sentence.text.toString();
        q.stemWithAnswer = q.originalSentence;

        Set<Integer> lineSet = new HashSet<>();
        for (Model.Line line : raw.lines) lineSet.add(line.globalIndex);

        List<Match> matches = new ArrayList<>();
        for (Model.Underline u : raw.geometry.underlines) {
            if (!u.matched || !lineSet.contains(u.lineGlobal)) continue;
            int[] range = sentence.rangeOf(u);
            if (range == null) continue;
            Match m = new Match();
            m.underline = u;
            m.start = range[0];
            m.end = range[1];
            matches.add(m);
        }
        matches.sort(Comparator.comparingInt((Match m) -> sentence.lineOrder(m.underline.lineGlobal))
                .thenComparingDouble(m -> m.underline.x0));

        List<Model.BlankSpan> groups = new ArrayList<>();
        for (Match m : matches) {
            if (!groups.isEmpty()) {
                Model.BlankSpan last = groups.get(groups.size() - 1);
                CrossLineState state = crossLineState(last, m, sentence);
                if (state == CrossLineState.EXACT) {
                    last.segments.add(segment(m.underline));
                    last.answer += m.underline.cleanText;
                    last.sentenceEnd = m.end;
                    last.crossLine = true;
                    continue;
                }
                if (state == CrossLineState.OVERLAP) {
                    addReview(q, "overlapping_cross_line_text");
                }
                // GAP: there is text or punctuation between the two underlined
                // segments. Keep them as independent original blanks.
            }
            Model.BlankSpan group = new Model.BlankSpan();
            group.answer = m.underline.cleanText;
            group.sentenceStart = m.start;
            group.sentenceEnd = m.end;
            group.segments.add(segment(m.underline));
            groups.add(group);
        }

        for (Model.BlankSpan group : groups) {
            group.originalAnswer = sentence.text.substring(group.sentenceStart, group.sentenceEnd);
            boolean reliable = withoutLayoutSpaces(group.originalAnswer)
                    .equals(withoutLayoutSpaces(group.answer));
            if (!reliable) addReview(q, "blank_text_mismatch");
            buildLearningBlanks(group, q, reliable);
        }

        q.blanks.addAll(groups);
        q.learningBlankCount = 0;
        for (Model.BlankSpan group : groups) {
            for (Model.LearningBlank learning : group.learningBlanks) {
                q.answers.add(learning.answer);
                q.learningBlankCount++;
            }
            for (Model.Segment segment : group.segments) {
                for (Model.Underline u : sentence.underlines) {
                    if (u.page == segment.page && Math.abs(u.x0 - segment.x0) < 0.01
                            && Math.abs(u.x1 - segment.x1) < 0.01 && u.boundaryPartial) {
                        addReview(q, "partial_underline_coverage");
                    }
                }
            }
        }
        q.stem = blankRanges(q.originalSentence, groups, q);

        if (groups.isEmpty()) addReview(q, "no_underline_answers");
        for (int i = 1; i < groups.size(); i++) {
            if (groups.get(i).sentenceStart < groups.get(i - 1).sentenceEnd) {
                addReview(q, "overlapping_blank_ranges");
            }
        }
    }

    private static CrossLineState crossLineState(Model.BlankSpan last, Match next,
                                                 SentenceBuilder sentence) {
        Model.Segment lastSegment = last.segments.get(last.segments.size() - 1);
        Model.Line lastLine = sentence.line(lastSegment.line);
        Model.Line nextLine = sentence.line(next.underline.lineGlobal);
        if (lastLine == null || nextLine == null) return CrossLineState.NONE;
        if (lastLine.page != nextLine.page) return CrossLineState.NONE;
        if (nextLine.lineNo != lastLine.lineNo + 1) return CrossLineState.NONE;
        if (lastSegment.x1 < lastLine.x1 - 3.0f) return CrossLineState.NONE;
        if (next.underline.x0 > nextLine.x0 + 3.0f) return CrossLineState.NONE;
        if (next.underline.glyphStart < 0) return CrossLineState.NONE;
        if (next.start < last.sentenceEnd) return CrossLineState.OVERLAP;
        if (next.start > last.sentenceEnd) return CrossLineState.GAP;
        return CrossLineState.EXACT;
    }

    private static void buildLearningBlanks(Model.BlankSpan group, Model.Question q,
                                             boolean reliable) {
        String original = group.originalAnswer == null
                ? (group.answer == null ? "" : group.answer)
                : group.originalAnswer;
        group.learningBlanks.clear();
        group.splitByEnumeration = false;
        if (original.trim().isEmpty()) {
            addReview(q, "empty_underline_answer");
            return;
        }
        if (!reliable) {
            addSingleFallbackBlank(group, original);
            return;
        }
        List<int[]> ranges = new ArrayList<>();
        if (original.indexOf('、') < 0) {
            int[] trimmed = trimWhitespace(original, 0, original.length());
            if (trimmed[0] >= trimmed[1]) {
                addReview(q, "empty_underline_answer");
                return;
            }
            ranges.add(new int[]{0, original.length(), trimmed[0], trimmed[1]});
        } else {
            boolean hasEmpty = false;
            int start = 0;
            for (int i = 0; i < original.length(); i++) {
                if (original.charAt(i) == '、') {
                    int[] trimmed = trimWhitespace(original, start, i);
                    if (trimmed[0] >= trimmed[1]) hasEmpty = true;
                    ranges.add(new int[]{start, i, trimmed[0], trimmed[1]});
                    start = i + 1;
                }
            }
            int[] tail = trimWhitespace(original, start, original.length());
            if (tail[0] >= tail[1]) hasEmpty = true;
            ranges.add(new int[]{start, original.length(), tail[0], tail[1]});

            if (hasEmpty) {
                addReview(q, "empty_enumeration_item");
                addSingleFallbackBlank(group, original);
                return;
            }
            group.splitByEnumeration = true;
        }

        for (int[] range : ranges) {
            Model.LearningBlank learning = new Model.LearningBlank();
            learning.answer = original.substring(range[2], range[3]).trim();
            learning.sentenceStart = group.sentenceStart + range[0];
            learning.sentenceEnd = group.sentenceStart + range[1];
            group.learningBlanks.add(learning);
        }
    }

    private static void addSingleFallbackBlank(Model.BlankSpan group, String original) {
        group.splitByEnumeration = false;
        Model.LearningBlank fallback = new Model.LearningBlank();
        fallback.answer = original.trim();
        fallback.sentenceStart = group.sentenceStart;
        fallback.sentenceEnd = group.sentenceEnd;
        group.learningBlanks.add(fallback);
    }

    private static String withoutLayoutSpaces(String value) {
        return value == null ? "" : value.replace(" ", "").replace("\t", "");
    }

    private static int[] trimWhitespace(String value, int start, int end) {
        while (start < end && Character.isWhitespace(value.charAt(start))) start++;
        while (end > start && Character.isWhitespace(value.charAt(end - 1))) end--;
        return new int[]{start, end};
    }

    private enum CrossLineState {
        NONE,
        EXACT,
        GAP,
        OVERLAP
    }

    private static Model.Segment segment(Model.Underline u) {
        Model.Segment s = new Model.Segment();
        s.page = u.page;
        s.line = u.lineGlobal;
        s.x0 = u.x0;
        s.x1 = u.x1;
        s.y = u.y;
        s.text = u.cleanText;
        return s;
    }

    private static String blankRanges(String sentence, List<Model.BlankSpan> groups,
                                      Model.Question q) {
        StringBuilder sb = new StringBuilder(sentence);
        List<Model.LearningBlank> learningBlanks = new ArrayList<>();
        for (Model.BlankSpan group : groups) {
            learningBlanks.addAll(group.learningBlanks);
        }
        if (learningBlanks.isEmpty()) {
            addReview(q, "no_learning_blanks");
            return sentence;
        }
        learningBlanks.sort(Comparator.comparingInt((Model.LearningBlank b) -> b.sentenceStart).reversed());
        for (Model.LearningBlank learning : learningBlanks) {
            if (learning.sentenceStart < 0 || learning.sentenceEnd > sb.length()
                    || learning.sentenceStart >= learning.sentenceEnd) {
                addReview(q, "invalid_blank_range");
                continue;
            }
            sb.replace(learning.sentenceStart, learning.sentenceEnd, "____");
        }
        return sb.toString();
    }

    private static void addNumberingAnomalies(Model.ParseResult result) {
        Map<String, List<Model.Question>> byType = new LinkedHashMap<>();
        for (Model.Question q : result.questions) {
            byType.computeIfAbsent(q.type, k -> new ArrayList<>()).add(q);
        }
        for (Map.Entry<String, List<Model.Question>> e : byType.entrySet()) {
            List<Integer> numbers = new ArrayList<>();
            for (Model.Question q : e.getValue()) {
                if (q.numberValue != null) numbers.add(q.numberValue);
            }
            if (numbers.isEmpty()) continue;
            Collections.sort(numbers);
            int min = numbers.get(0);
            int max = numbers.get(numbers.size() - 1);
            Set<Integer> unique = new HashSet<>(numbers);
            for (int n = min; n <= max; n++) {
                if (!unique.contains(n)) {
                    Model.Anomaly a = new Model.Anomaly();
                    a.type = "missing_number";
                    a.section = e.getKey();
                    a.number = n;
                    a.detail = "number absent from source; no question synthesized";
                    result.anomalies.add(a);
                }
            }
            Map<Integer, Integer> counts = new LinkedHashMap<>();
            for (Integer n : numbers) counts.put(n, counts.getOrDefault(n, 0) + 1);
            for (Map.Entry<Integer, Integer> c : counts.entrySet()) {
                if (c.getValue() > 1) {
                    Model.Anomaly a = new Model.Anomaly();
                    a.type = "duplicate_number";
                    a.section = e.getKey();
                    a.number = c.getKey();
                    a.detail = "number appears " + c.getValue() + " times";
                    result.anomalies.add(a);
                }
            }
        }
    }

    private static void addUnderlineAnomalies(Model.ParseResult result, Model.Geometry geometry) {
        int number = 0;
        for (Model.Underline u : geometry.underlines) {
            if (u.matched) continue;
            Model.Anomaly a = new Model.Anomaly();
            a.type = "unmatched_underline";
            a.section = "fill_blank";
            a.number = ++number;
            a.page = u.page;
            a.detail = String.format(java.util.Locale.ROOT,
                    "horizontal segment x=%.2f..%.2f y=%.2f did not map to a text line/glyph",
                    u.x0, u.x1, u.y);
            result.anomalies.add(a);
        }
    }

    private static List<Marker> optionMarkers(String body) {
        List<Marker> markers = new ArrayList<>();
        Matcher matcher = OPTION_MARKER.matcher(body);
        int firstA = -1;
        while (matcher.find()) {
            if ("A".equals(matcher.group(2))) {
                firstA = matcher.start(2);
                break;
            }
        }
        if (firstA < 0) return markers;

        matcher = OPTION_MARKER.matcher(body);
        matcher.region(firstA, body.length());
        while (matcher.find()) {
            Marker marker = new Marker();
            marker.key = matcher.group(2);
            marker.start = matcher.start(2);
            marker.end = matcher.end();
            markers.add(marker);
        }
        return markers;
    }

    private static String bodyWithBreaks(RawQuestion raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.lines.size(); i++) {
            String text = raw.lines.get(i).text.trim();
            if (i == 0) text = text.replaceFirst("^\\s*\\d{1,3}\\s*[.．]\\s*", "");
            text = text.trim();
            if (text.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(text);
        }
        return sb.toString();
    }

    private static String compact(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s*\\n\\s*", "")
                .replaceAll("[\\t ]+", " ")
                .trim();
    }

    private static boolean hasSameNumberAhead(List<Model.Line> content, int index, String number) {
        int limit = Math.min(content.size(), index + 8);
        for (int i = index + 1; i < limit; i++) {
            String text = content.get(i).text.trim();
            if (HEADING.matcher(text).matches()) return false;
            Matcher m = QUESTION_START.matcher(text);
            if (m.matches()) {
                if (m.group(1).equals(number) && !m.group(2).trim().isEmpty()) return true;
                return false;
            }
        }
        return false;
    }

    private static String sectionType(String title) {
        if (title.contains("单选")) return "single_choice";
        if (title.contains("多选")) return "multiple_choice";
        if (title.contains("判断")) return "true_false";
        if (title.contains("填空")) return "fill_blank";
        if (title.contains("简答")) return "short_answer";
        return "unknown";
    }

    private static Integer safeInt(String s) {
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void addReview(Model.Question q, String reason) {
        if (!q.reviewReasons.contains(reason)) q.reviewReasons.add(reason);
    }

    private static final class RawQuestion {
        Model.Geometry geometry;
        Model.Section section;
        String type;
        String originalNumber;
        Integer numberValue;
        boolean emptyStart;
        final List<Model.Line> lines = new ArrayList<>();
    }

    private static final class Marker {
        String key;
        int start;
        int end;
    }

    private static final class Match {
        Model.Underline underline;
        int start;
        int end;
    }

    private static final class SentenceBuilder {
        final StringBuilder text = new StringBuilder();
        final Map<Integer, LineMap> maps = new LinkedHashMap<>();
        final List<Model.Underline> underlines;

        SentenceBuilder(RawQuestion raw) {
            this.underlines = raw.geometry == null ? new ArrayList<>() : raw.geometry.underlines;
            for (int i = 0; i < raw.lines.size(); i++) {
                Model.Line line = raw.lines.get(i);
                String source = line.text;
                int start = 0;
                if (i == 0) {
                    Matcher m = NUMBER_PREFIX.matcher(source);
                    if (m.find()) start = m.end();
                }
                while (start < source.length() && Character.isWhitespace(source.charAt(start))) start++;
                int end = source.length();
                while (end > start && Character.isWhitespace(source.charAt(end - 1))) end--;
                if (end <= start) continue;
                LineMap map = new LineMap();
                map.line = line;
                map.sourceStart = start;
                map.sourceEnd = end;
                map.base = text.length();
                maps.put(line.globalIndex, map);
                text.append(source, start, end);
            }
        }

        int lineOrder(int global) {
            LineMap map = maps.get(global);
            return map == null ? Integer.MAX_VALUE : map.line.lineNo;
        }

        Model.Line line(int global) {
            LineMap map = maps.get(global);
            return map == null ? null : map.line;
        }

        int[] rangeOf(Model.Underline u) {
            LineMap map = maps.get(u.lineGlobal);
            if (map == null || u.glyphStart < 0) return null;
            Model.Line line = map.line;
            if (u.glyphStart >= line.glyphs.size() || u.glyphEnd >= line.glyphs.size()) return null;
            Model.Glyph first = line.glyphs.get(u.glyphStart);
            Model.Glyph last = line.glyphs.get(u.glyphEnd);
            int start = map.base + Math.max(0, first.textStart - map.sourceStart);
            int end = map.base + Math.min(map.sourceEnd - map.sourceStart, last.textEnd - map.sourceStart);
            if (end <= start) return null;
            return new int[]{start, end};
        }

        private static final class LineMap {
            Model.Line line;
            int sourceStart;
            int sourceEnd;
            int base;
        }
    }
}
