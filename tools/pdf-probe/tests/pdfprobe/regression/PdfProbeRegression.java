package pdfprobe.regression;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import pdfprobe.Model;
import pdfprobe.QuestionParser;
import pdfprobe.TextExtractor;

/** Small, dependency-free regression suite; exits non-zero on any failure. */
@SuppressWarnings("deprecation")
public final class PdfProbeRegression {
    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<>();

    private PdfProbeRegression() {}

    interface Checked {
        void run() throws Exception;
    }

    interface ContentAction {
        void run(PDPageContentStream stream) throws Exception;
    }

    public static void main(String[] args) {
        java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.OFF);
        test("R1 painted horizontal stroke is one underline",
                () -> assertEquals(1, underlineCount(cs -> {
                    cs.moveTo(72, 718.8f);
                    cs.lineTo(97, 718.8f);
                    cs.stroke();
                }), "painted stroke"));
        test("R1 unpainted path is discarded",
                () -> assertEquals(0, underlineCount(cs -> {
                    cs.moveTo(72, 718.8f);
                    cs.lineTo(97, 718.8f);
                    cs.appendRawCommands("n\n");
                }), "path ended with n"));
        test("R1 unpainted rectangle is discarded",
                () -> assertEquals(0, underlineCount(cs -> {
                    cs.addRect(72, 718.8f, 25, 0.5f);
                    cs.appendRawCommands("n\n");
                }), "rectangle ended with n"));
        test("R1 clip-only path is discarded",
                () -> assertEquals(0, underlineCount(cs -> {
                    cs.addRect(72, 718.8f, 25, 0.5f);
                    cs.appendRawCommands("W n\n");
                }), "clip path"));
        test("R1 filled thin rectangle is one underline",
                () -> assertEquals(1, underlineCount(cs -> {
                    cs.addRect(72, 718.8f, 25, 0.5f);
                    cs.fill();
                }), "filled thin rectangle"));
        test("R1 large filled rectangle is not an underline",
                () -> assertEquals(0, underlineCount(cs -> {
                    cs.addRect(72, 700, 100, 20);
                    cs.fill();
                }), "large fill"));
        test("R1 closed/backtracked path is not double-counted",
                () -> assertEquals(1, underlineCount(cs -> {
                    cs.moveTo(72, 718.8f);
                    cs.lineTo(97, 718.8f);
                    cs.lineTo(72, 718.8f);
                    cs.closePath();
                    cs.stroke();
                }), "closed horizontal path"));

        test("R2 normal cross-line options remain four",
                () -> {
                    Model.Question q = parse(
                            "一、单选题",
                            "1. 跨行选项题（A）",
                            "A. 第一项",
                            "B. 第二项",
                            "C. 第三项",
                            "D. 第四",
                            "项").questions.get(0);
                    assertEquals(4, q.options.size(), "option count");
                    assertEquals("第四项", q.options.get(3).text, "cross-line D");
                    assertEquals(List.of("A"), q.answers, "answer");
                    assertFalse(q.needsReview, "normal cross-line options must not be flagged");
                });
        test("R2 duplicate B marker is visible and flagged",
                () -> {
                    Model.Question q = parse(
                            "一、单选题",
                            "1. Question（B）",
                            "A. first B. second B. duplicate C. third D. fourth").questions.get(0);
                    assertEquals(5, q.options.size(), "duplicate marker must not be swallowed");
                    assertEquals("B", q.options.get(1).key, "first B key");
                    assertEquals("second", q.options.get(1).text, "first B text");
                    assertEquals("B", q.options.get(2).key, "duplicate B key");
                    assertEquals("duplicate", q.options.get(2).text, "duplicate B text");
                    assertTrue(q.needsReview, "duplicate must require review");
                    assertTrue(q.reviewReasons.contains("duplicate_option_marker"),
                            "duplicate reason");
                    assertTrue(q.optionRawText.contains("B. duplicate"), "raw options preserved");
                });
        test("R2 six options are parsed, not folded into E",
                () -> {
                    Model.Question q = parse(
                            "一、单选题",
                            "1. Question（A）",
                            "A. one B. two C. three D. four E. five F. six").questions.get(0);
                    assertEquals(6, q.options.size(), "six option markers");
                    assertEquals("E", q.options.get(4).key, "E key");
                    assertEquals("five", q.options.get(4).text, "E must not contain F");
                    assertEquals("F", q.options.get(5).key, "F key");
                    assertEquals("six", q.options.get(5).text, "F text");
                    assertEquals(List.of("A"), q.answers, "answer A");
                });
        test("R2 out-of-order markers are flagged",
                () -> {
                    Model.Question q = parse(
                            "一、单选题",
                            "1. Question（B）",
                            "A. one C. three B. two D. four").questions.get(0);
                    assertEquals(4, q.options.size(), "all markers preserved");
                    assertTrue(q.reviewReasons.contains("option_markers_out_of_order"),
                            "out-of-order reason");
                    assertTrue(q.reviewReasons.contains("option_keys_not_sequential"),
                            "sequence reason");
                });
        test("R2 missing marker is flagged",
                () -> {
                    Model.Question q = parse(
                            "一、单选题",
                            "1. Question（B）",
                            "A. one B. two D. four").questions.get(0);
                    assertEquals(3, q.options.size(), "all observed markers preserved");
                    assertTrue(q.reviewReasons.contains("option_marker_gap"), "gap reason");
                    assertTrue(q.reviewReasons.contains("option_keys_not_sequential"),
                            "sequence reason");
                });
        test("R2 unsupported multi-letter marker is visible and flagged",
                () -> {
                    Model.Question q = parse(
                            "一、单选题",
                            "1. Question（A）",
                            "A. one B. two C. three D. four E. five AA. extra").questions.get(0);
                    assertTrue(q.needsReview, "unsupported marker must require review");
                    assertTrue(q.reviewReasons.contains("unsupported_option_marker"),
                            "unsupported reason");
                    assertTrue(q.optionRawText.contains("AA. extra"), "raw text preserved");
                });

        test("blank rules: one region with four enumeration items", () -> {
            Model.Geometry g = baseFillGeometry("1.有理想、敢担当、能吃苦、肯奋斗");
            Model.Line line = g.lines.get(1);
            addUnderline(g, line, 2, line.text.length() - 1, line.text.substring(2));
            Model.Question q = QuestionParser.parse(g).questions.get(0);
            assertEquals(1, q.blanks.size(), "one physical region");
            assertEquals(4, q.learningBlankCount, "four learning blanks");
            assertEquals(List.of("有理想", "敢担当", "能吃苦", "肯奋斗"), q.answers,
                    "enumeration answers");
            assertEquals("____、____、____、____", q.stem, "enumeration stem");
            assertFalse(q.needsReview, "clear enumeration needs no review");
            Model.Question again = QuestionParser.parse(g).questions.get(0);
            assertEquals(q.answers, again.answers, "enumeration split is repeatable");
            assertEquals(q.stem, again.stem, "enumeration stem is repeatable");
        });
        test("blank rules: cross-line answer merged then split", () -> {
            Model.Geometry g = baseFillGeometry("1.有理想、敢担当、");
            Model.Line first = g.lines.get(1);
            first.x1 = 84f + first.text.length() * 10f;
            Model.Line second = addLine(g, "能吃苦、肯奋斗");
            addUnderline(g, first, 2, first.text.length() - 1,
                    first.text.substring(2));
            addUnderline(g, second, 0, second.text.length() - 1, second.text);
            Model.Question q = QuestionParser.parse(g).questions.get(0);
            assertEquals(1, q.blanks.size(), "merged physical region");
            assertTrue(q.blanks.get(0).crossLine, "cross-line fact");
            assertEquals(4, q.learningBlankCount, "four learning blanks after merge");
            assertEquals(List.of("有理想", "敢担当", "能吃苦", "肯奋斗"), q.answers,
                    "cross-line enumeration answers");
            assertEquals("____、____、____、____", q.stem, "cross-line enumeration stem");
            assertFalse(q.needsReview, "clear cross-line needs no review");
        });
        test("blank rules: unlined punctuation prevents cross-line merge", () -> {
            for (String gap : List.of("、", "，", "和")) {
                Model.Geometry g = baseFillGeometry("1.前文答案" + gap);
                Model.Line first = g.lines.get(1);
                first.x1 = first.x0 + (first.text.length() - 1) * 10f;
                Model.Line second = addLine(g, "后文");
                int answerStart = first.text.indexOf("答案");
                addUnderline(g, first, answerStart, answerStart + 1, "答案");
                addUnderline(g, second, 0, second.text.length() - 1, "后文");
                Model.Question q = QuestionParser.parse(g).questions.get(0);
                assertEquals(2, q.blanks.size(), "gap must remain two regions: " + gap);
                assertEquals(List.of("答案", "后文"), q.answers, "gap text preserved: " + gap);
                assertEquals("前文____" + gap + "____", q.stem, "gap punctuation kept: " + gap);
                assertFalse(q.needsReview, "clear gap needs no review: " + gap);
            }
        });
        test("blank rules: ambiguous boundary still requires review", () -> {
            Model.Geometry g = baseFillGeometry("1.答案");
            Model.Line first = g.lines.get(1);
            Model.Line second = addLine(g, "继续");
            addUnderline(g, first, 2, first.text.length() - 1, "答案");
            addUnderline(g, second, 0, second.text.length() - 1, "继续");
            g.underlines.get(0).boundaryPartial = true;
            Model.Question q = QuestionParser.parse(g).questions.get(0);
            assertTrue(q.blanks.get(0).crossLine, "ambiguous cross-line still merged when exact");
            assertTrue(q.needsReview, "ambiguous boundary must be visible");
            assertTrue(q.reviewReasons.contains("partial_underline_coverage"),
                    "boundary review reason");
        });
        test("blank rules: unreliable text mapping falls back to one full region", () -> {
            Model.Geometry g = baseFillGeometry("1.甲、乙、丙");
            Model.Line line = g.lines.get(1);
            addUnderline(g, line, 2, line.text.length() - 1, "错误文本");
            Model.Question q = QuestionParser.parse(g).questions.get(0);
            assertEquals(1, q.learningBlankCount, "fallback keeps one blank");
            assertEquals(List.of("甲、乙、丙"), q.answers, "fallback keeps full original answer");
            assertEquals("____", q.stem, "fallback blanks full region");
            assertTrue(q.needsReview, "fallback must be reviewed");
            assertTrue(q.reviewReasons.contains("blank_text_mismatch"), "mapping reason");
        });
        test("blank rules: internal comma/semicolon stays one learning blank", () -> {
            Model.Geometry g = baseFillGeometry("1.甲，乙；丙");
            Model.Line line = g.lines.get(1);
            addUnderline(g, line, 2, line.text.length() - 1, line.text.substring(2));
            Model.Question q = QuestionParser.parse(g).questions.get(0);
            assertEquals(1, q.learningBlankCount, "only 、 splits");
            assertEquals(List.of("甲，乙；丙"), q.answers, "internal punctuation kept");
            assertEquals("____", q.stem, "single blank");
            assertFalse(q.needsReview, "internal punctuation is not ambiguity");
        });
        test("blank rules: leading/consecutive enumeration creates no empty answer", () -> {
            Model.Geometry g = baseFillGeometry("1.、甲、、乙、");
            Model.Line line = g.lines.get(1);
            addUnderline(g, line, 2, line.text.length() - 1, line.text.substring(2));
            Model.Question q = QuestionParser.parse(g).questions.get(0);
            assertEquals(1, q.learningBlankCount, "ambiguous split keeps one region");
            assertEquals(List.of("、甲、、乙、"), q.answers, "full original answer kept");
            assertTrue(q.needsReview, "empty enumeration item needs review");
            assertTrue(q.reviewReasons.contains("empty_enumeration_item"), "enumeration reason");
            assertEquals("____", q.stem, "no empty blanks");
        });
        test("blank rules: unlined enumeration does not create a blank", () -> {
            Model.Geometry g = baseFillGeometry("1.前文、答案");
            Model.Line line = g.lines.get(1);
            int start = line.text.indexOf("答案");
            addUnderline(g, line, start, line.text.length() - 1, "答案");
            Model.Question q = QuestionParser.parse(g).questions.get(0);
            assertEquals(1, q.learningBlankCount, "unlined 、 is not a blank");
            assertEquals(List.of("答案"), q.answers, "only underlined answer");
            assertEquals("前文、____", q.stem, "unlined punctuation kept in stem");
            assertFalse(q.needsReview, "unlined punctuation is not ambiguity");
        });
        test("blank rules: short answer is not converted to fill", () -> {
            Model.Geometry g = new Model.Geometry();
            addLine(g, "五、简答题");
            addLine(g, "1.简答");
            addLine(g, "参考答案：有理想、敢担当。");
            Model.Question q = QuestionParser.parse(g).questions.get(0);
            assertEquals("short_answer", q.type, "short answer type");
            assertEquals(1, q.answers.size(), "short answer stays one answer");
            assertEquals("有理想、敢担当。", q.referenceAnswer, "short answer unchanged");
            assertEquals(0, q.learningBlankCount, "no fill learning blanks");
        });

        String sample = args.length > 0 ? args[0] : "local-inputs/sample.pdf";
        Path cjkFont = Path.of("C:/Windows/Fonts/simhei.ttf");
        if (Files.isRegularFile(cjkFont)) {
            test("synthetic PDF: five types and cross-line underline",
                    () -> checkSyntheticPdf(cjkFont));
            test("spacing PDF: normal enumeration with layout space",
                    () -> checkSpacingPdf(cjkFont, new String[]{"1.甲、 乙、丙"}, false));
            test("spacing PDF: leading/trailing layout whitespace",
                    () -> checkSpacingPdf(cjkFont, new String[]{"1. 甲、 乙、丙 "}, false));
            test("spacing PDF: spaces around all enumeration commas",
                    () -> checkSpacingPdf(cjkFont, new String[]{"1.甲 、 乙 、丙"}, false));
            test("spacing PDF: repeated layout whitespace",
                    () -> checkSpacingPdf(cjkFont, new String[]{"1.甲、  乙、丙"}, false));
            test("spacing PDF: cross-line with leading space",
                    () -> checkSpacingPdf(cjkFont, new String[]{"1.甲、 乙、", " 丙"}, true));
            test("spacing PDF: no-space control",
                    () -> checkSpacingPdf(cjkFont, new String[]{"1.甲、乙、丙"}, false));
        } else {
            System.out.println("SKIP synthetic/spacing PDF: CJK test font not found: " + cjkFont);
        }
        if (Files.isRegularFile(Path.of(sample))) {
            test("sample regression: 201 questions, 58 underlines, anomalies",
                    () -> checkSample(sample));
        } else {
            System.out.println("SKIP sample regression: file not found: " + sample);
        }

        System.out.println();
        System.out.println("PASS=" + passed + " FAIL=" + failed);
        for (String failure : failures) System.out.println("FAILED: " + failure);
        if (failed > 0) System.exit(1);
    }

    private static Model.Geometry baseFillGeometry(String question) {
        Model.Geometry geometry = new Model.Geometry();
        addLine(geometry, "四、填空题");
        addLine(geometry, question);
        return geometry;
    }

    private static Model.Line addLine(Model.Geometry geometry, String text) {
        Model.Line line = new Model.Line();
        line.page = 1;
        line.lineNo = geometry.lines.size() + 1;
        line.globalIndex = geometry.lines.size();
        line.text = text;
        line.x0 = 84f;
        line.x1 = 84f + text.length() * 10f;
        line.baseline = 700f - line.lineNo * 20f;
        line.fontSize = 10f;
        for (int i = 0; i < text.length(); i++) {
            Model.Glyph glyph = new Model.Glyph();
            glyph.text = String.valueOf(text.charAt(i));
            glyph.textStart = i;
            glyph.textEnd = i + 1;
            glyph.x = 84f + i * 10f;
            glyph.baseline = line.baseline;
            glyph.width = 10f;
            glyph.height = 5f;
            glyph.fontSize = 10f;
            line.glyphs.add(glyph);
        }
        geometry.lines.add(line);
        return line;
    }

    private static void addUnderline(Model.Geometry geometry, Model.Line line,
                                     int glyphStart, int glyphEnd, String text) {
        Model.Underline underline = new Model.Underline();
        underline.page = line.page;
        underline.lineGlobal = line.globalIndex;
        underline.glyphStart = glyphStart;
        underline.glyphEnd = glyphEnd;
        underline.x0 = line.glyphs.get(glyphStart).x;
        underline.x1 = line.glyphs.get(glyphEnd).x + line.glyphs.get(glyphEnd).width;
        underline.y = line.baseline + 1.2f;
        underline.rawText = text;
        underline.cleanText = text;
        underline.matched = true;
        geometry.underlines.add(underline);
    }

    private static int underlineCount(ContentAction action) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(72, 720);
                cs.showText("ABC");
                cs.endText();
                cs.setLineWidth(0.5f);
                action.run(cs);
            }
            return TextExtractor.extract(doc).underlines.size();
        }
    }

    private static Model.ParseResult parse(String... lines) {
        Model.Geometry geometry = new Model.Geometry();
        for (int i = 0; i < lines.length; i++) {
            Model.Line line = new Model.Line();
            line.page = 1;
            line.lineNo = i + 1;
            line.globalIndex = i;
            line.text = lines[i];
            geometry.lines.add(line);
        }
        return QuestionParser.parse(geometry);
    }

    private static void checkSyntheticPdf(Path fontPath) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType0Font font = PDType0Font.load(doc, fontPath.toFile());
            String[] lines = {
                "一、单选题",
                "1.公开单选（A）",
                "A.甲 B.乙 C.丙 D.丁",
                "二、多选题",
                "1.公开多选（AB）",
                "A.甲 B.乙 C.丙 D.丁",
                "三、判断题",
                "1.公开判断（√）",
                "四、填空题",
                "1.公开填空：跨行",
                "答案。",
                "五、简答题",
                "1.公开简答",
                "参考答案：公开自制答案。"
            };
            float startY = 720f;
            float step = 34f;
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                for (int i = 0; i < lines.length; i++) {
                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.newLineAtOffset(72, startY - i * step);
                    cs.showText(lines[i]);
                    cs.endText();
                }
                cs.setLineWidth(0.6f);
                float fillY = startY - 9 * step;
                String fillLine = lines[9];
                String prefix = "1.公开填空：";
                float x0 = 72 + font.getStringWidth(prefix) / 1000f * 12f;
                float x1 = 72 + font.getStringWidth(fillLine) / 1000f * 12f;
                cs.moveTo(x0, fillY - 2f);
                cs.lineTo(x1, fillY - 2f);
                cs.stroke();

                float nextY = startY - 10 * step;
                float nextX1 = 72 + font.getStringWidth("答案") / 1000f * 12f;
                cs.moveTo(72, nextY - 2f);
                cs.lineTo(nextX1, nextY - 2f);
                cs.stroke();
            }

            Model.Geometry geometry = TextExtractor.extract(doc);
            Model.ParseResult result = QuestionParser.parse(geometry);
            assertEquals(5, result.questions.size(), "synthetic PDF question count");
            assertEquals("single_choice", result.questions.get(0).type, "synthetic single");
            assertEquals("multiple_choice", result.questions.get(1).type, "synthetic multiple");
            assertEquals("true_false", result.questions.get(2).type, "synthetic judge");
            assertEquals("fill_blank", result.questions.get(3).type, "synthetic fill");
            assertEquals("short_answer", result.questions.get(4).type, "synthetic short");
            Model.Question fill = result.questions.get(3);
            assertEquals(List.of("跨行答案"), fill.answers, "synthetic cross-line fill answer");
            assertTrue(fill.blanks.size() == 1 && fill.blanks.get(0).crossLine,
                    "synthetic cross-line flag");
            assertTrue(fill.stem.contains("____"), "synthetic fill blank marker");
        }
    }

    private static void checkSpacingPdf(Path fontPath, String[] lines, boolean crossLine)
            throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType0Font font = PDType0Font.load(doc, fontPath.toFile());
            float startY = 700f;
            float step = 34f;
            float fontSize = 12f;
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, fontSize);
                cs.newLineAtOffset(72, 750);
                cs.showText("四、填空题");
                cs.endText();
                for (int i = 0; i < lines.length; i++) {
                    cs.beginText();
                    cs.setFont(font, fontSize);
                    cs.newLineAtOffset(72, startY - i * step);
                    cs.showText(lines[i]);
                    cs.endText();
                }
                cs.setLineWidth(0.6f);
                float firstX0 = 72 + font.getStringWidth("1.") / 1000f * fontSize;
                float firstX1 = 72 + font.getStringWidth(lines[0]) / 1000f * fontSize;
                cs.moveTo(firstX0, startY - 2f);
                cs.lineTo(firstX1, startY - 2f);
                cs.stroke();
                if (crossLine) {
                    float secondY = startY - step;
                    float secondX1 = 72 + font.getStringWidth(lines[1]) / 1000f * fontSize;
                    cs.moveTo(72, secondY - 2f);
                    cs.lineTo(secondX1, secondY - 2f);
                    cs.stroke();
                }
            }

            Model.Geometry geometry = TextExtractor.extract(doc);
            Model.Question q = QuestionParser.parse(geometry).questions.get(0);
            assertEquals(1, q.blanks.size(), "spacing PDF one original region");
            assertEquals(3, q.learningBlankCount, "spacing PDF three learning blanks");
            assertEquals(List.of("甲", "乙", "丙"), q.answers, "spacing PDF answers");
            assertEquals("____、____、____", q.stem, "spacing PDF stem");
            assertFalse(q.needsReview, "spacing PDF needs no review");
            assertEquals(crossLine, q.blanks.get(0).crossLine, "spacing PDF cross-line fact");
            assertTrue(q.blanks.get(0).splitByEnumeration, "spacing PDF split fact");
            assertFalse(q.stem.contains("甲"), "spacing PDF leaks 甲");
            assertFalse(q.stem.contains("乙"), "spacing PDF leaks 乙");
            assertFalse(q.stem.contains("丙"), "spacing PDF leaks 丙");
            for (Model.LearningBlank learning : q.blanks.get(0).learningBlanks) {
                assertEquals(learning.answer,
                        q.originalSentence.substring(learning.sentenceStart, learning.sentenceEnd).trim(),
                        "learning blank maps to original range");
            }
        }
    }

    private static void checkSample(String sample) throws Exception {
        try (PDDocument doc = PDDocument.load(new File(sample))) {
            Model.Geometry geometry = TextExtractor.extract(doc);
            Model.ParseResult result = QuestionParser.parse(geometry);
            assertEquals(201, result.questions.size(), "sample total");
            assertEquals(93, countType(result, "single_choice"), "sample single");
            assertEquals(44, countType(result, "multiple_choice"), "sample multiple");
            assertEquals(32, countType(result, "true_false"), "sample judge");
            assertEquals(25, countType(result, "fill_blank"), "sample fill");
            assertEquals(7, countType(result, "short_answer"), "sample short");
            assertEquals(58, geometry.underlines.size(), "sample underlines");
            long matched = geometry.underlines.stream().filter(u -> u.matched).count();
            assertEquals(58L, matched, "matched underlines");
            long crossLine = result.questions.stream()
                    .flatMap(q -> q.blanks.stream())
                    .filter(b -> b.crossLine).count();
            assertEquals(6L, crossLine, "cross-line blanks");
            int originalRegions = result.questions.stream()
                    .filter(q -> "fill_blank".equals(q.type))
                    .mapToInt(q -> q.blanks.size()).sum();
            int learningBlanks = result.questions.stream()
                    .filter(q -> "fill_blank".equals(q.type))
                    .mapToInt(q -> q.learningBlankCount).sum();
            assertEquals(52, originalRegions, "sample original fill regions");
            assertEquals(52, learningBlanks, "sample learning blanks");
            assertEquals(0L, result.questions.stream().filter(q -> q.needsReview).count(),
                    "sample reviewed questions");

            Set<Integer> missing = new HashSet<>();
            boolean orphan6 = false;
            for (Model.Anomaly a : result.anomalies) {
                if ("missing_number".equals(a.type) && a.number != null && a.number == 92) {
                    missing.add(92);
                }
                if ("orphan_number".equals(a.type) && a.number != null && a.number == 6) {
                    orphan6 = true;
                }
            }
            assertTrue(missing.contains(92), "missing 92 recorded");
            assertTrue(orphan6, "orphan 6 recorded");

            Model.Question q1 = find(result, "fill_blank", "1");
            assertEquals(List.of("智能化", "绿色化", "融合化", "先进制造业"),
                    q1.answers, "fill 1 answers");
            Model.Question q6 = find(result, "fill_blank", "6");
            assertEquals(List.of("基本实现社会主义现代化"), q6.answers, "fill 6 cross-line");
            for (Model.Question q : result.questions) {
                if ("fill_blank".equals(q.type)) {
                    int blanks = q.stem.split("____", -1).length - 1;
                    assertEquals(q.answers.size(), blanks, "fill blank count " + q.id);
                }
            }
            Set<String> ids = new HashSet<>();
            for (Model.Question q : result.questions) {
                assertTrue(ids.add(q.id), "duplicate id " + q.id);
                assertFalse(q.answers.isEmpty(), "empty answer " + q.id);
            }
        }
    }

    private static int countType(Model.ParseResult result, String type) {
        return (int) result.questions.stream().filter(q -> q.type.equals(type)).count();
    }

    private static Model.Question find(Model.ParseResult result, String type, String number) {
        for (Model.Question q : result.questions) {
            if (q.type.equals(type) && number.equals(q.originalNumber)) return q;
        }
        throw new AssertionError("question not found: " + type + " " + number);
    }

    private static void test(String name, Checked check) {
        try {
            check.run();
            passed++;
            System.out.println("PASS: " + name);
        } catch (Throwable t) {
            failed++;
            failures.add(name + " :: " + t.getMessage());
            System.out.println("FAIL: " + name + " :: " + t.getMessage());
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertFalse(boolean value, String message) {
        if (value) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }
}
