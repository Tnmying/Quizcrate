package pdfprobe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts reconstructed lines and underline spans into typed questions. */
public final class QuestionParser {
    private static final Pattern HEADING =
            Pattern.compile("^([一二三四五六七八九十]+)、(.+?)\\s*$");
    private static final Pattern QUESTION_START =
            Pattern.compile("^[\\t \u3000]*(?:[（(][\\s\u3000]*|第[\\s\u3000]*)?([0-9０-９]{1,4})[\\s\u3000]*(?:[.．]|、|[）)]|题)[\\s\u3000]*(.*)$");
    private static final Pattern NUMBER_PREFIX =
            Pattern.compile("^[\\s\u3000]*(?:[（(][\\s\u3000]*|第[\\s\u3000]*)?[0-9０-９]{1,4}[\\s\u3000]*(?:[.．]|、|[）)]|题)[\\s\u3000]*");
    private static final Pattern OPTION_MARKER =
            Pattern.compile("(?m)(^|[\\s\u3000])([A-Za-zＡ-Ｚａ-ｚ])[.．、)）][\\s\u3000]*");
    private static final Pattern UNSUPPORTED_OPTION_MARKER =
            Pattern.compile("(?m)(^|\\s)([A-Z]{2,})[.．]");
    private static final Pattern ANSWER =
            Pattern.compile("[（(][\\s\u3000]*([A-Za-zＡ-Ｚａ-ｚ](?:[A-Za-zＡ-Ｚａ-ｚ\\t \u3000、,，]*[A-Za-zＡ-Ｚａ-ｚ])?|[√×✓✗✔✘])[\\s\u3000]*[）)]");
    private static final String OPTION_KEYS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final Pattern ANSWER_LABEL = Pattern.compile("(?m)^[\\t ]*(?:正确答案|参考答案|答案)[\\t ]*[：:]+[\\t ]*(.*)$");
    private static final Pattern ANALYSIS_LABEL = Pattern.compile("(?m)^\\s*(?:答案解析|解析|解答说明)\\s*[：:]");
    private static final Pattern PERSONAL_LABEL = Pattern.compile("(?m)^[\\t ]*(?:我的答案|你的答案|学生答案|已选答案)[\\t ]*[：:]");
    private static final Pattern CONTROL_LABEL = Pattern.compile("(?m)^[\\t ]*(?:查看解析|收起解析|查看答案|收起答案)[\\t ]*$");
    private static final Pattern TYPE_TAG = Pattern.compile("^[\\t ]*[\\[【［](单项选择题|多项选择题|单选题|多选题|判断题|填空题|简答题|问答题|单选|多选|判断|填空|简答)[\\]】］][\\t ]*");
    private static final Pattern BLANK_PLACEHOLDER = Pattern.compile("_{2,}|[（(][\\t ]*[）)]");
    private static final Pattern NUMBERED_BLANK_ANSWER = Pattern.compile("(?:^|[\\r\\n\\t ])[(（](\\d+)[)）][\\t ]*");
    private static final Pattern TABLE_HEADING = Pattern.compile("^(?:[一二三四五六七八九十]+[、.．][\\t ]*)?(?:(?:单项选择题|多项选择题|单选题|多选题|判断题)[\\t ]*)?(?:参考答案|答案汇总|参考答案汇总|答案表)[：:]?$");
    private static final Pattern CHAPTER_HEADING = Pattern.compile("^(第[一二三四五六七八九十百零〇0-9０-９]+[章节篇部])(?:[\\t \u3000]*.*)$");

    private QuestionParser() {}

    public static Model.ParseResult parse(Model.Geometry geometry) {
        return parse(geometry, "unknown");
    }

    /** defaultType is an explicit import choice, used only outside typed sections. */
    public static Model.ParseResult parse(Model.Geometry geometry, String defaultType) {
        Model.ParseResult result = new Model.ParseResult();
        Set<Integer> marginNoise = repeatedMarginLines(geometry);
        List<Model.Line> content = new ArrayList<>();
        for (Model.Line line : geometry.lines) {
            if (!line.footer && !marginNoise.contains(line.globalIndex) && !line.text.trim().isEmpty()) content.add(line);
        }

        Model.Section currentSection = new Model.Section();
        currentSection.title = "未分节";
        currentSection.type = defaultType;
        result.sections.add(currentSection);
        RawQuestion current = null;
        TableBlock answerTable = null;
        List<TableBlock> answerTables = new ArrayList<>();
        Map<Model.Question, Model.Section> questionScopes = new IdentityHashMap<>();
        Map<Model.Section, String> chapters = new IdentityHashMap<>();
        String chapter = "";
        chapters.put(currentSection, chapter);
        Map<String, Integer> counters = new HashMap<>();

        for (int i = 0; i < content.size(); i++) {
            Model.Line line = content.get(i);
            String text = line.text.trim();

            boolean tableHeading = TABLE_HEADING.matcher(text).matches();
            boolean sectionHeading = isSectionHeading(text, current != null);
            if (sectionHeading && !chapterKey(text).isEmpty() && current != null && (current.inAnswer || current.inAnalysis)
                    && !hasQuestionEvidenceAfter(content, i)) sectionHeading = false;
            if (answerTable != null && !tableHeading) {
                if ((sectionHeading && hasQuestionEvidenceAfter(content, i)) || isNewQuestionAfterTable(content, i)) {
                    answerTable.closedByQuestions = true;
                    answerTable = null;
                } else if (sectionHeading) {
                    answerTable.hasSelectors = true;
                    String key = chapterKey(text);
                    if (!key.isEmpty()) { answerTable.chapter = key; answerTable.type = null; }
                    else {
                        String type = sectionType(text);
                        if (!"unknown".equals(type)) answerTable.type = type;
                        else addTableAnomaly(result, line, text);
                    }
                    continue;
                } else {
                    List<AnswerTableParser.Entry> entries = AnswerTableParser.parse(text);
                    if (entries == null) addTableAnomaly(result, line, text);
                    else for (AnswerTableParser.Entry entry : entries) answerTable.entries.add(new TableEntry(entry, line, answerTable.type, answerTable.chapter));
                    continue;
                }
            }
            if (tableHeading
                    && (current == null || (!"short_answer".equals(current.type) && !"fill_blank".equals(current.type))
                        || !text.matches("^参考答案[：:]?$"))) {
                if (current != null) finish(current, result, counters, questionScopes);
                current = null;
                String inheritedChapter = answerTable == null ? "" : answerTable.chapter;
                answerTable = new TableBlock(currentSection, result.questions.size());
                answerTable.chapter = inheritedChapter;
                String type = sectionType(text);
                if (!"unknown".equals(type)) answerTable.type = type;
                answerTables.add(answerTable);
                continue;
            }

            if (sectionHeading) {
                if (current != null) {
                    finish(current, result, counters, questionScopes);
                    current = null;
                }
                Model.Section section = new Model.Section();
                section.title = text;
                section.type = sectionType(text);
                String chapterKey = chapterKey(text);
                if (!chapterKey.isEmpty()) chapter = chapterKey;
                section.page = line.page;
                result.sections.add(section);
                currentSection = section;
                chapters.put(section, chapter);
                continue;
            }

            if (currentSection == null) continue;

            Matcher start = QUESTION_START.matcher(text);
            if (start.matches()) {
                String taggedType = taggedType(start.group(2));
                if (current != null && "fill_blank".equals(current.type) && current.inMetadata
                        && taggedType == null && text.matches("^[（(][0-9０-９]+[）)].*")) {
                    current.lines.add(line);
                    continue;
                }
                if (taggedType == null && current != null && text.matches("^[0-9０-９]+[.．][0-9０-９]+.*")
                        && ("single_choice".equals(current.type) || "multiple_choice".equals(current.type))
                        && !hasChoiceEvidenceAhead(content, i)) {
                    current.lines.add(line);
                    continue;
                }
                if (taggedType == null && current != null && "short_answer".equals(current.type) && current.inAnswer
                        && !hasReferenceAnswerAhead(content, i)) {
                    current.lines.add(line);
                    current.numberedAnswerContent |= line.page == 0;
                    continue;
                }
                if (taggedType == null && current != null && current.inAnalysis
                        && ("single_choice".equals(current.type) || "multiple_choice".equals(current.type))
                        && !hasChoiceEvidenceAhead(content, i)) {
                    current.lines.add(line);
                    continue;
                }
                if (current != null) {
                    finish(current, result, counters, questionScopes);
                    current = null;
                }
                String numberText = AnswerTableParser.ascii(start.group(1));
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
                current.type = taggedType == null ? currentSection.type : taggedType;
                current.autoType = "unknown".equals(current.type);
                current.typeConflict = taggedType != null && !"unknown".equals(currentSection.type)
                    && !"未分节".equals(currentSection.title) && !taggedType.equals(currentSection.type);
                current.originalNumber = numberText;
                current.numberValue = safeInt(numberText);
                current.emptyStart = body.isEmpty();
                current.lines.add(line);
                continue;
            }

            if (current != null) {
                current.lines.add(line);
                if (ANALYSIS_LABEL.matcher(text).find()) current.inAnalysis = true;
                if (ANSWER_LABEL.matcher(text).find() || text.matches("参考答案[：:]?")) current.inAnswer = true;
                if (PERSONAL_LABEL.matcher(text).find() || ANSWER_LABEL.matcher(text).find()) current.inMetadata = true;
                if (current.autoType) current.type = inferType(current);
            }
            else {
                Model.Anomaly anomaly = new Model.Anomaly();
                anomaly.type = "unparsed_text";
                anomaly.page = line.page;
                anomaly.line = line.lineNo;
                anomaly.detail = text;
                result.anomalies.add(anomaly);
            }
        }
        if (current != null) finish(current, result, counters, questionScopes);
        bindAnswerTables(result, answerTables, questionScopes, chapters);

        addNumberingAnomalies(result, questionScopes);
        addUnderlineAnomalies(result, geometry);
        result.sections.removeIf(section -> section.count == 0 && "未分节".equals(section.title));
        counters.clear();
        for (Model.Question q : result.questions) {
            int count = counters.getOrDefault(q.type, 0) + 1;
            counters.put(q.type, count);
            q.id = String.format(Locale.ROOT, "%s-%03d", q.type, count);
        }
        return result;
    }

    private static void finish(RawQuestion raw, Model.ParseResult result,
                               Map<String, Integer> counters, Map<Model.Question, Model.Section> scopes) {
        if (raw.lines.isEmpty()) return;
        Model.Question q = parseRaw(raw);
        if (q == null) return;
        int count = counters.getOrDefault(q.type, 0) + 1;
        counters.put(q.type, count);
        q.id = String.format(Locale.ROOT, "%s-%03d", q.type, count);
        result.questions.add(q);
        scopes.put(q, raw.section);
        raw.section.count++;
    }

    private static Model.Question parseRaw(RawQuestion raw) {
        Model.Question q = new Model.Question();
        if (raw.autoType) raw.type = inferType(raw);
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
                if (ANSWER_LABEL.matcher(bodyWithBreaks(raw)).find()
                        || (!raw.lines.isEmpty() && raw.lines.get(0).page == 0)
                        || (raw.autoType && BLANK_PLACEHOLDER.matcher(questionContent(bodyWithBreaks(raw))).find())) parseLabeledFill(raw, q);
                else if (!raw.lines.isEmpty() && raw.lines.get(0).page == 0) parseTextFill(raw, q);
                else parseFill(raw, q);
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
        if (raw.typeConflict) addReview(q, "type_tag_section_conflict");
        if (raw.choiceTypeUncertain) addReview(q, "choice_type_uncertain");
        if (raw.numberedAnswerContent) addReview(q, "numbered_answer_content");
        Matcher answerLabels = ANSWER_LABEL.matcher(bodyWithBreaks(raw));
        int labelCount = 0;
        while (answerLabels.find()) labelCount++;
        if (labelCount > 1) addReview(q, "multiple_answer_labels");
        if (q.originalSentence == null) q.originalSentence = bodyWithBreaks(raw);
        q.needsReview = !q.reviewReasons.isEmpty();
        return q;
    }

    private static void parseChoice(RawQuestion raw, Model.Question q) {
        String body = bodyWithBreaks(raw);
        q.optionRawText = body;
        Matcher explicit = ANSWER_LABEL.matcher(body);
        String explicitAnswer = null;
        if (explicit.find()) {
            explicitAnswer = referenceText(body, explicit);
        }
        body = questionContent(body);
        if (explicitAnswer != null) {
            Matcher trailingToken = Pattern.compile("\\n[\\t ]*([A-Z]{1,26})[\\t ]*\\z").matcher(body);
            if (trailingToken.find() && optionMarkers(body.substring(0, trailingToken.start())).size() >= 2) {
                addReview(q, "unlabeled_answer_text");
                body = body.substring(0, trailingToken.start()).trim();
            }
        }
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
            if (explicitAnswer != null) applyExternalAnswer(q, explicitAnswer);
            return;
        }

        String letters = AnswerTableParser.ascii(token).replaceAll("[\\s\u3000]", "").toUpperCase(Locale.ROOT);
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
        if (explicitAnswer != null) applyExternalAnswer(q, explicitAnswer);
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
        String body = bodyWithBreaks(raw);
        q.stemWithAnswer = compact(questionContent(body));
        Matcher explicit = ANSWER_LABEL.matcher(body);
        if (explicit.find()) {
            q.stem = q.stemWithAnswer;
            q.stemWithAnswer = q.stem;
            Matcher bracket = ANSWER.matcher(q.stem);
            while (bracket.find()) {
                if (bracket.group(1).matches("[√×✓✗✔✘]")) {
                    q.answers.clear();
                    q.answers.add(bracket.group(1).matches("[√✓✔]") ? "√" : "×");
                    q.stem = q.stem.substring(0, bracket.start()) + "（ ）" + q.stem.substring(bracket.end());
                    break;
                }
            }
            applyExternalAnswer(q, referenceText(body, explicit));
            return;
        }
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
        Matcher label = ANSWER_LABEL.matcher(body);
        int index = body.indexOf("参考答案");
        int answerStart = index < 0 ? -1 : index + "参考答案".length();
        if (label.find()) {
            index = label.start();
            answerStart = label.start(1);
        }
        if (index < 0) {
            q.stem = compact(body);
            q.stemWithAnswer = q.stem;
            addReview(q, "no_reference_answer_marker");
            return;
        }
        q.stem = compact(questionContent(body.substring(0, index)));
        String answer = body.substring(answerStart)
                .replaceFirst("^\\s*[：:]\\s*", "");
        answer = stripTrailingMetadata(answer);
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

    private static void addNumberingAnomalies(Model.ParseResult result, Map<Model.Question, Model.Section> scopes) {
        Map<Model.Section, List<Model.Question>> byScope = new LinkedHashMap<>();
        for (Model.Question q : result.questions) {
            byScope.computeIfAbsent(scopes.get(q), k -> new ArrayList<>()).add(q);
        }
        for (Map.Entry<Model.Section, List<Model.Question>> e : byScope.entrySet()) {
            String scopeType = e.getValue().get(0).type;
            for (Model.Question q : e.getValue()) if (!scopeType.equals(q.type)) { scopeType = "mixed"; break; }
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
                    a.section = scopeType;
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
                    a.section = scopeType;
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
            if ("A".equals(AnswerTableParser.ascii(matcher.group(2)).toUpperCase(Locale.ROOT))) {
                firstA = matcher.start(2);
                break;
            }
        }
        if (firstA < 0) return markers;

        matcher = OPTION_MARKER.matcher(body);
        matcher.region(firstA, body.length());
        while (matcher.find()) {
            Marker marker = new Marker();
            marker.key = AnswerTableParser.ascii(matcher.group(2)).toUpperCase(Locale.ROOT);
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
            if (i == 0) text = NUMBER_PREFIX.matcher(text).replaceFirst("");
            if (i == 0) text = TYPE_TAG.matcher(text).replaceFirst("");
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
                if (AnswerTableParser.ascii(m.group(1)).equals(number) && !m.group(2).trim().isEmpty()) return true;
                return false;
            }
        }
        return false;
    }

    private static boolean hasChoiceEvidenceAhead(List<Model.Line> content, int index) {
        for (int i = index; i < Math.min(content.size(), index + 30); i++) {
            String text = content.get(i).text.trim();
            if (i > index && (QUESTION_START.matcher(text).matches() || HEADING.matcher(text).matches())) return false;
            if (OPTION_MARKER.matcher(text).find()) return true;
        }
        return false;
    }

    private static boolean hasReferenceAnswerAhead(List<Model.Line> content, int index) {
        if (content.get(index).text.contains("参考答案")) return true;
        for (int i = index + 1; i < Math.min(content.size(), index + 50); i++) {
            String text = content.get(i).text.trim();
            if (QUESTION_START.matcher(text).matches() || HEADING.matcher(text).matches()) return false;
            if (ANSWER_LABEL.matcher(text).find() || text.matches("参考答案[：:]?")) return true;
        }
        return false;
    }

    private static String sectionType(String title) {
        if (title.contains("单选") || title.contains("单项选择")) return "single_choice";
        if (title.contains("多选") || title.contains("多项选择")) return "multiple_choice";
        if (title.contains("判断")) return "true_false";
        if (title.contains("填空")) return "fill_blank";
        if (title.contains("简答") || title.contains("问答")) return "short_answer";
        return "unknown";
    }

    private static Integer safeInt(String s) {
        try {
            return Integer.valueOf(AnswerTableParser.ascii(s));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void addReview(Model.Question q, String reason) {
        if (!q.reviewReasons.contains(reason)) q.reviewReasons.add(reason);
    }

    private static void applyExternalAnswer(Model.Question q, String text) {
        List<String> candidate = new ArrayList<>();
        if ("single_choice".equals(q.type) || "multiple_choice".equals(q.type)) {
            String token = AnswerTableParser.ascii(text).replaceAll("[\\s\u3000、,，;；]", "").toUpperCase(Locale.ROOT);
            if (!token.matches("[A-Z]{1,26}")) { addReview(q, "invalid_explicit_answer"); return; }
            for (char key : token.toCharArray()) {
                String value = String.valueOf(key);
                if (candidate.contains(value)) { addReview(q, "invalid_explicit_answer"); return; }
                boolean exists = false;
                for (Model.Option option : q.options) if (value.equals(option.key)) exists = true;
                if (!exists) { addReview(q, "answer_not_in_options"); return; }
                candidate.add(value);
            }
            if (("single_choice".equals(q.type) && candidate.size() != 1)
                    || ("multiple_choice".equals(q.type) && candidate.size() < 2)) {
                addReview(q, "invalid_explicit_answer"); return;
            }
        } else if ("true_false".equals(q.type)) {
            if (text.matches("√|✓|✔|正确|对")) candidate.add("√");
            else if (text.matches("×|✗|✘|错误|错")) candidate.add("×");
            else { addReview(q, "invalid_explicit_answer"); return; }
        } else { addReview(q, "unmatched_answer_table"); return; }
        Set<String> existing = new HashSet<>(q.answers);
        if ("true_false".equals(q.type) && !q.answers.isEmpty()) {
            existing.clear();
            for (String value : q.answers) existing.add(value.matches("[√✓✔]") ? "√" : value.matches("[×✗✘]") ? "×" : value);
        }
        if (!q.answers.isEmpty() && !existing.equals(new HashSet<>(candidate))) {
            addReview(q, "conflicting_answers"); return;
        }
        q.answers.clear();
        q.answers.addAll(candidate);
        q.reviewReasons.remove("no_answer_in_parentheses");
        q.reviewReasons.remove("no_true_false_symbol");
    }

    private static void parseTextFill(RawQuestion raw, Model.Question q) {
        String body = bodyWithBreaks(raw);
        q.originalSentence = body;
        Matcher label = ANSWER_LABEL.matcher(body);
        if (!label.find()) {
            q.stem = body;
            addReview(q, "no_reference_answer_marker");
            return;
        }
        q.stem = body.substring(0, label.start()).trim().replaceAll("_{2,}", "____");
        String answer = body.substring(label.start(1)).trim();
        Matcher analysis = ANALYSIS_LABEL.matcher(answer);
        if (analysis.find()) answer = answer.substring(0, analysis.start()).trim();
        for (String part : answer.split("\\|\\|", -1)) q.answers.add(part.trim());
        Matcher blanks = Pattern.compile("____").matcher(q.stem);
        int count = 0;
        while (blanks.find()) count++;
        if (count == 0 || count != q.answers.size()) addReview(q, "text_blank_count_mismatch");
        if (q.answers.contains("")) addReview(q, "empty_reference_answer");
        q.learningBlankCount = count;
        q.stemWithAnswer = q.stem;
    }

    private static String taggedType(String text) {
        Matcher tag = TYPE_TAG.matcher(text);
        return tag.find() ? sectionType(tag.group(1)) : null;
    }

    private static String chapterKey(String text) {
        Matcher matcher = CHAPTER_HEADING.matcher(text);
        return matcher.matches() && text.length() <= 100 ? AnswerTableParser.ascii(matcher.group(1)) : "";
    }

    private static boolean isSectionHeading(String text, boolean withinQuestion) {
        if (!chapterKey(text).isEmpty()) return true;
        if (text.matches("^(?:单选题|单项选择题|多选题|多项选择题|判断题|填空题|简答题|问答题)$")) return true;
        return HEADING.matcher(text).matches() && (!"unknown".equals(sectionType(text)) || !withinQuestion);
    }

    private static boolean hasQuestionEvidenceAfter(List<Model.Line> lines, int index) {
        for (int i = index + 1; i < Math.min(lines.size(), index + 80); i++) {
            String text = lines.get(i).text.trim();
            if (TABLE_HEADING.matcher(text).matches() || !chapterKey(text).isEmpty()) return false;
            if (isSectionHeading(text, false)) continue;
            if (isNewQuestionAfterTable(lines, i)) return true;
            if (AnswerTableParser.parse(text) != null) return false;
        }
        return false;
    }

    private static boolean isNewQuestionAfterTable(List<Model.Line> lines, int index) {
        String text = lines.get(index).text.trim();
        Matcher start = QUESTION_START.matcher(text);
        if (!start.matches()) return false;
        if (taggedType(start.group(2)) != null) return true;
        return AnswerTableParser.parse(text) == null && (hasChoiceEvidenceAhead(lines, index) || hasReferenceAnswerAhead(lines, index));
    }

    private static void addTableAnomaly(Model.ParseResult result, Model.Line line, String text) {
        Model.Anomaly anomaly = new Model.Anomaly();
        anomaly.type = "unparsed_answer_table";
        anomaly.page = line.page;
        anomaly.line = line.lineNo;
        anomaly.detail = text;
        result.anomalies.add(anomaly);
    }

    private static boolean tableApplies(TableBlock table, String type, String chapter, Model.Question q,
                                        boolean localConvention, Map<Model.Question, Model.Section> scopes,
                                        Map<Model.Section, String> chapters, int position) {
        Model.Section scope = scopes.get(q);
        if (!chapter.isEmpty() && !chapter.equals(chapters.getOrDefault(scope, ""))) return false;
        if (type != null && !type.equals(q.type)) return false;
        if (!chapter.isEmpty()) return true;
        return !(table.closedByQuestions || (localConvention && table.conventionScope))
            || (scope == table.previousSection && position < table.endQuestionIndex);
    }

    private static void bindAnswerTables(Model.ParseResult result, List<TableBlock> tables,
                                         Map<Model.Question, Model.Section> scopes, Map<Model.Section, String> chapters) {
        if (tables.isEmpty()) return;
        Map<Model.Question, Integer> positions = new IdentityHashMap<>();
        Map<String, List<Model.Question>> byNumber = new HashMap<>();
        for (int i = 0; i < result.questions.size(); i++) {
            Model.Question q = result.questions.get(i);
            positions.put(q, i);
            String key = q.numberValue == null ? q.originalNumber : q.numberValue.toString();
            byNumber.computeIfAbsent(key, k -> new ArrayList<>()).add(q);
        }
        boolean localConvention = tables.stream().anyMatch(table -> table.closedByQuestions && !table.entries.isEmpty());
        Map<Model.Section, Integer> tableCounts = new IdentityHashMap<>();
        for (TableBlock table : tables) tableCounts.put(table.previousSection, tableCounts.getOrDefault(table.previousSection, 0) + 1);
        for (TableBlock table : tables) table.conventionScope = !table.hasSelectors && tableCounts.get(table.previousSection) == 1;
        Map<Model.Question, List<TableEntry>> assignments = new IdentityHashMap<>();
        Set<Model.Question> covered = Collections.newSetFromMap(new IdentityHashMap<>());
        for (TableBlock table : tables) {
            for (Model.Question q : result.questions) if (tableApplies(table, table.type, table.chapter, q, localConvention, scopes, chapters, positions.get(q))) covered.add(q);
            for (TableEntry entry : table.entries) {
                List<Model.Question> matches = new ArrayList<>();
                Integer number = safeInt(entry.number);
                String key = number == null ? entry.number : number.toString();
                for (Model.Question q : byNumber.getOrDefault(key, Collections.emptyList())) {
                    if (tableApplies(table, entry.type, entry.chapter, q, localConvention, scopes, chapters, positions.get(q))) {
                        covered.add(q);
                        if ((q.numberValue != null && q.numberValue.equals(safeInt(entry.number))) || entry.number.equals(q.originalNumber)) matches.add(q);
                    }
                }
                if (matches.size() == 1) assignments.computeIfAbsent(matches.get(0), k -> new ArrayList<>()).add(entry);
                else {
                    for (Model.Question q : matches) addReview(q, "ambiguous_answer_table");
                    if (matches.isEmpty()) addTableAnomaly(result, entry.line, "答案题号 " + entry.number + " 没有唯一对应题目");
                }
            }
        }
        for (Map.Entry<Model.Question, List<TableEntry>> assignment : assignments.entrySet()) {
            Model.Question q = assignment.getKey();
            if (assignment.getValue().size() != 1) { addReview(q, "ambiguous_answer_table"); continue; }
            String answer = assignment.getValue().get(0).answer;
            boolean inferred = q.reviewReasons.contains("choice_type_uncertain");
            if (inferred && answer.matches("[A-Za-z]{1,26}")) {
                Set<Character> keys = new HashSet<>();
                for (char key : answer.toUpperCase(Locale.ROOT).toCharArray()) keys.add(key);
                q.type = keys.size() > 1 ? "multiple_choice" : "single_choice";
            }
            applyExternalAnswer(q, answer);
            if (inferred && !q.answers.isEmpty()) q.reviewReasons.remove("choice_type_uncertain");
        }
        for (Model.Question q : result.questions) {
            if (covered.contains(q) && q.answers.isEmpty()) addReview(q, "unmatched_answer_table");
            q.needsReview = !q.reviewReasons.isEmpty();
        }
    }

    private static final class TableBlock {
        final Model.Section previousSection;
        final int endQuestionIndex;
        final List<TableEntry> entries = new ArrayList<>();
        String type;
        String chapter = "";
        boolean closedByQuestions;
        boolean hasSelectors;
        boolean conventionScope;
        TableBlock(Model.Section section, int end) { previousSection = section; endQuestionIndex = end; }
    }

    private static final class TableEntry {
        final String number, answer, type, chapter;
        final Model.Line line;
        TableEntry(AnswerTableParser.Entry entry, Model.Line line, String type, String chapter) {
            number = entry.number; answer = entry.answer; this.line = line; this.type = type; this.chapter = chapter;
        }
    }

    /** Local evidence wins: options before answer shape, then explicit blanks, then prose answers. */
    private static String inferType(RawQuestion raw) {
        String body = bodyWithBreaks(raw);
        String content = questionContent(body);
        Matcher label = ANSWER_LABEL.matcher(body);
        boolean hasLabel = label.find();
        String answer = hasLabel ? referenceText(body, label) : "";
        List<Marker> options = optionMarkers(content);
        raw.choiceTypeUncertain = false;
        if (options.size() >= 2) {
            if (!hasLabel) {
                Matcher inline = ANSWER.matcher(content.substring(0, options.get(0).start));
                while (inline.find()) answer = inline.group(1);
            }
            String letters = AnswerTableParser.ascii(answer).replaceAll("[\\s\u3000、,，;；]", "").toUpperCase(Locale.ROOT);
            if (letters.matches("[A-Z]{1,26}")) {
                Set<Character> keys = new HashSet<>();
                for (char key : letters.toCharArray()) keys.add(key);
                return keys.size() > 1 ? "multiple_choice" : "single_choice";
            }
            raw.choiceTypeUncertain = true;
            return "single_choice";
        }
        if (hasLabel && answer.matches("√|✓|✔|×|✗|✘|正确|错误|对|错")) return "true_false";
        Matcher inline = ANSWER.matcher(content);
        while (inline.find()) if (inline.group(1).matches("[√✓✔×✗✘]")) return "true_false";
        if (BLANK_PLACEHOLDER.matcher(content).find()) return "fill_blank";
        if (hasLabel) return "short_answer";
        if (!hasLabel) {
            Set<Integer> sourceLines = new HashSet<>();
            for (Model.Line line : raw.lines) sourceLines.add(line.globalIndex);
            for (Model.Underline underline : raw.geometry.underlines) {
                if (underline.matched && sourceLines.contains(underline.lineGlobal) && !underline.cleanText.trim().isEmpty()) return "fill_blank";
            }
        }
        return "unknown";
    }

    /** Structural metadata is excluded from the question, but remains in sourceLines/originalSentence. */
    private static String questionContent(String body) {
        int end = body.length();
        for (Pattern pattern : new Pattern[]{ANSWER_LABEL, PERSONAL_LABEL, ANALYSIS_LABEL, CONTROL_LABEL}) {
            Matcher matcher = pattern.matcher(body);
            if (matcher.find()) end = Math.min(end, matcher.start());
        }
        return body.substring(0, end).trim();
    }

    private static String stripTrailingMetadata(String text) {
        int end = text.length();
        for (Pattern pattern : new Pattern[]{PERSONAL_LABEL, ANALYSIS_LABEL, CONTROL_LABEL}) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) end = Math.min(end, matcher.start());
        }
        return text.substring(0, end).trim();
    }

    private static String referenceText(String body, Matcher label) {
        return stripTrailingMetadata(body.substring(label.start(1)));
    }

    /** Explicit blank answers override PDF strokes, which may only underline an export label. */
    private static void parseLabeledFill(RawQuestion raw, Model.Question q) {
        String body = bodyWithBreaks(raw);
        q.originalSentence = body;
        q.stem = BLANK_PLACEHOLDER.matcher(questionContent(body)).replaceAll("____");
        q.stemWithAnswer = q.stem;
        Matcher label = ANSWER_LABEL.matcher(body);
        if (!label.find()) { addReview(q, "no_reference_answer_marker"); return; }
        String answer = referenceText(body, label);
        Matcher numbered = NUMBERED_BLANK_ANSWER.matcher(answer);
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        List<Integer> numbers = new ArrayList<>();
        while (numbered.find()) {
            starts.add(numbered.start());
            ends.add(numbered.end());
            numbers.add(safeInt(numbered.group(1)));
        }
        if (!starts.isEmpty() && answer.substring(0, starts.get(0)).trim().isEmpty()) {
            for (int i = 0; i < starts.size(); i++) {
                if (numbers.get(i) == null || numbers.get(i) != i + 1) addReview(q, "numbered_blank_answer_order");
                int end = i + 1 < starts.size() ? starts.get(i + 1) : answer.length();
                q.answers.add(compact(answer.substring(ends.get(i), end)));
            }
        } else {
            for (String part : answer.split("\\|\\|", -1)) q.answers.add(compact(part));
        }
        Matcher blanks = Pattern.compile("____").matcher(q.stem);
        int count = 0;
        while (blanks.find()) count++;
        q.learningBlankCount = count;
        if (count == 0 || count != q.answers.size()) addReview(q, "text_blank_count_mismatch");
        if (q.answers.contains("")) addReview(q, "empty_reference_answer");
        if (q.answers.stream().anyMatch(value -> value.trim().startsWith("\\") || value.trim().endsWith("\\"))) addReview(q, "invalid_answer_variants");
    }

    /** Detect repeated text only in a narrow physical margin; no document-specific keywords. */
    private static Set<Integer> repeatedMarginLines(Model.Geometry geometry) {
        Set<Integer> result = new HashSet<>();
        if (geometry.pages.size() < 3) return result;
        Map<Integer, Float> heights = new HashMap<>();
        for (Model.PageInfo page : geometry.pages) heights.put(page.number, page.height);
        Map<String, List<Model.Line>> candidates = new HashMap<>();
        for (Model.Line line : geometry.lines) {
            float height = heights.getOrDefault(line.page, 0f);
            String text = line.text.trim();
            if (line.footer || height <= 0 || text.isEmpty() || text.length() > 80) continue;
            boolean top = line.baseline < height * .08f;
            boolean bottom = line.baseline > height * .92f;
            if (!top && !bottom) continue;
            if (QUESTION_START.matcher(text).matches() || TYPE_TAG.matcher(text).find()
                    || OPTION_MARKER.matcher(text).find() || ANSWER_LABEL.matcher(text).find()
                    || PERSONAL_LABEL.matcher(text).find() || !"unknown".equals(sectionType(text))) continue;
            String key = (top ? "top:" : "bottom:") + text;
            if (text.matches("^(?:第\\s*)?\\d+\\s*(?:页|/\\s*\\d+)?$")) {
                if (!(line.baseline > height * .97f || line.baseline < height * .04f)) continue;
                key = (top ? "top:" : "bottom:") + "#page";
            }
            candidates.computeIfAbsent(key, k -> new ArrayList<>()).add(line);
        }
        int threshold = Math.max(3, (int) Math.ceil(geometry.pages.size() * .6));
        for (List<Model.Line> lines : candidates.values()) {
            Set<Integer> pages = new HashSet<>();
            for (Model.Line line : lines) pages.add(line.page);
            if (pages.size() < threshold) continue;
            for (Model.Line line : lines) {
                result.add(line.globalIndex);
                line.footer = true;
                geometry.footerLines++;
            }
        }
        return result;
    }

    private static final class RawQuestion {
        Model.Geometry geometry;
        Model.Section section;
        String type;
        String originalNumber;
        Integer numberValue;
        boolean emptyStart;
        boolean inAnalysis;
        boolean inAnswer;
        boolean numberedAnswerContent;
        boolean inMetadata;
        boolean typeConflict;
        boolean autoType;
        boolean choiceTypeUncertain;
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
