package pdfprobe;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.util.Version;

/** Command-line entry point for the phase-01 PDF extraction probe. */
public final class PdfProbe {
    private static final String VERSION = "0.1.0";

    private PdfProbe() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        if (opts.containsKey("help")) {
            System.out.println("Usage: PdfProbe --input <pdf> --out <dir> [--render 1,20,21]");
            return;
        }
        Path input = Paths.get(opts.getOrDefault("input", "local-inputs/sample.pdf"));
        Path outDir = Paths.get(opts.getOrDefault("out", "work/phase-01"));
        if (!Files.isRegularFile(input)) {
            throw new IOException("input PDF not found: " + input.toAbsolutePath());
        }
        Files.createDirectories(outDir.resolve("raw"));
        Files.createDirectories(outDir.resolve("render"));

        String sha256;
        try (PDDocument doc = PDDocument.load(input.toFile())) {
            sha256 = sha256(input);
            Model.Geometry geometry = TextExtractor.extract(doc);
            Model.ParseResult parsed = QuestionParser.parse(geometry);

            writeText(outDir.resolve("raw").resolve("text.txt"), geometry);
            writeLines(outDir.resolve("raw").resolve("lines.tsv"), geometry);
            writeGlyphs(outDir.resolve("raw").resolve("glyphs.tsv"), geometry);
            writeUnderlines(outDir.resolve("raw").resolve("underlines.tsv"), geometry);

            Map<String, Object> source = sourceMap(input, sha256, doc, geometry);
            Map<String, Object> summary = summaryMap(parsed, geometry);
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("schemaVersion", 1);
            root.put("generatedAt", OffsetDateTime.now().toString());
            root.put("generator", generatorMap());
            root.put("source", source);
            root.put("sections", sectionsList(parsed));
            root.put("questions", questionsList(parsed));
            root.put("anomalies", anomaliesList(parsed));
            root.put("summary", summary);

            writeUtf8(outDir.resolve("questions.json"), Json.stringify(root));
            Map<String, Object> summaryRoot = new LinkedHashMap<>();
            summaryRoot.put("schemaVersion", 1);
            summaryRoot.put("generatedAt", root.get("generatedAt"));
            summaryRoot.put("generator", root.get("generator"));
            summaryRoot.put("source", source);
            summaryRoot.put("summary", summary);
            writeUtf8(outDir.resolve("summary.json"), Json.stringify(summaryRoot));

            List<Integer> renderPages = parsePages(opts.get("render"));
            if (!renderPages.isEmpty()) {
                render(doc, renderPages, outDir.resolve("render"));
            }

            System.out.println("pages=" + doc.getNumberOfPages());
            System.out.println("questions=" + parsed.questions.size());
            System.out.println("byType=" + summary.get("byType"));
            System.out.println("underlines=" + summary.get("underlines"));
            System.out.println("withReview=" + summary.get("withReview"));
            System.out.println("output=" + outDir.toAbsolutePath());
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                opts.put("help", "true");
                continue;
            }
            if (!arg.startsWith("--")) continue;
            int eq = arg.indexOf('=');
            if (eq < 0) {
                opts.put(arg.substring(2), "true");
            } else {
                opts.put(arg.substring(2, eq), arg.substring(eq + 1));
            }
        }
        return opts;
    }

    private static List<Integer> parsePages(String value) {
        List<Integer> pages = new ArrayList<>();
        if (value == null || value.isBlank()) return pages;
        for (String part : value.split(",")) {
            try {
                pages.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // Ignore malformed page numbers; the CLI remains usable without rendering.
            }
        }
        return pages;
    }

    private static Map<String, Object> sourceMap(Path input, String sha256, PDDocument doc,
                                                 Model.Geometry geometry) throws IOException {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("path", input.toString().replace('\\', '/'));
        source.put("sha256", sha256);
        source.put("bytes", Files.size(input));
        source.put("pages", doc.getNumberOfPages());
        source.put("pdfVersion", doc.getVersion());
        source.put("encrypted", doc.isEncrypted());
        String title = doc.getDocumentInformation() == null ? "" : doc.getDocumentInformation().getTitle();
        source.put("title", title == null ? "" : title);
        if (!geometry.pages.isEmpty()) {
            source.put("pageWidth", round2(geometry.pages.get(0).width));
            source.put("pageHeight", round2(geometry.pages.get(0).height));
            source.put("rotation", geometry.pages.get(0).rotation);
        }
        source.put("hasTextLayer", geometry.glyphCount > 0);
        source.put("glyphCount", geometry.glyphCount);
        source.put("textChars", geometry.textChars);
        source.put("footerLines", geometry.footerLines);
        source.put("duplicateGlyphsSuppressed", geometry.duplicateGlyphs);
        return source;
    }

    private static Map<String, Object> generatorMap() {
        Map<String, Object> generator = new LinkedHashMap<>();
        generator.put("name", "pdf-probe");
        generator.put("version", VERSION);
        generator.put("pdfbox", Version.getVersion());
        generator.put("java", System.getProperty("java.version"));
        generator.put("javaVendor", System.getProperty("java.vendor"));
        generator.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        return generator;
    }

    private static List<Object> sectionsList(Model.ParseResult parsed) {
        List<Object> sections = new ArrayList<>();
        for (Model.Section section : parsed.sections) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", section.type);
            map.put("title", section.title);
            map.put("page", section.page);
            map.put("count", section.count);
            sections.add(map);
        }
        return sections;
    }

    private static List<Object> questionsList(Model.ParseResult parsed) {
        List<Object> questions = new ArrayList<>();
        for (Model.Question q : parsed.questions) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", q.id);
            map.put("type", q.type);
            map.put("sectionTitle", q.sectionTitle);
            map.put("originalNumber", q.originalNumber);
            map.put("numberValue", q.numberValue);
            map.put("stem", q.stem == null ? "" : q.stem);
            map.put("stemWithAnswer", q.stemWithAnswer == null ? "" : q.stemWithAnswer);
            if ("fill_blank".equals(q.type)) {
                map.put("originalSentence", q.originalSentence == null ? "" : q.originalSentence);
                map.put("answerEvidence", "underline");
                map.put("learningBlankCount", q.learningBlankCount);
            }
            if ("short_answer".equals(q.type)) {
                map.put("referenceAnswer", q.referenceAnswer == null ? "" : q.referenceAnswer);
            }
            if ("single_choice".equals(q.type) || "multiple_choice".equals(q.type)) {
                map.put("optionRawText", q.optionRawText == null ? "" : q.optionRawText);
            }
            List<Object> options = new ArrayList<>();
            for (Model.Option option : q.options) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("key", option.key);
                o.put("text", option.text);
                o.put("markerIndex", option.markerIndex);
                o.put("duplicate", option.duplicate);
                o.put("supported", option.supported);
                options.add(o);
            }
            map.put("options", options);
            map.put("answers", new ArrayList<Object>(q.answers));
            map.put("sourcePages", new ArrayList<Object>(q.sourcePages));
            List<Object> sourceLines = new ArrayList<>();
            for (Model.SourceLine line : q.sourceLines) {
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("page", line.page);
                l.put("line", line.line);
                sourceLines.add(l);
            }
            map.put("sourceLines", sourceLines);
            List<Object> blanks = new ArrayList<>();
            for (Model.BlankSpan blank : q.blanks) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("answer", blank.answer);
                b.put("originalAnswer", blank.originalAnswer == null ? "" : blank.originalAnswer);
                b.put("crossLine", blank.crossLine);
                b.put("splitByEnumeration", blank.splitByEnumeration);
                b.put("sentenceStart", blank.sentenceStart);
                b.put("sentenceEnd", blank.sentenceEnd);
                List<Object> learningBlanks = new ArrayList<>();
                for (Model.LearningBlank learning : blank.learningBlanks) {
                    Map<String, Object> lb = new LinkedHashMap<>();
                    lb.put("answer", learning.answer);
                    lb.put("sentenceStart", learning.sentenceStart);
                    lb.put("sentenceEnd", learning.sentenceEnd);
                    learningBlanks.add(lb);
                }
                b.put("learningBlanks", learningBlanks);
                List<Object> segments = new ArrayList<>();
                for (Model.Segment segment : blank.segments) {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("page", segment.page);
                    s.put("line", segment.line);
                    s.put("x0", round2(segment.x0));
                    s.put("x1", round2(segment.x1));
                    s.put("y", round2(segment.y));
                    s.put("text", segment.text);
                    segments.add(s);
                }
                b.put("segments", segments);
                blanks.add(b);
            }
            if ("fill_blank".equals(q.type)) map.put("blanks", blanks);
            map.put("needsReview", q.needsReview);
            map.put("reviewReasons", new ArrayList<Object>(q.reviewReasons));
            questions.add(map);
        }
        return questions;
    }

    private static List<Object> anomaliesList(Model.ParseResult parsed) {
        List<Object> anomalies = new ArrayList<>();
        for (Model.Anomaly anomaly : parsed.anomalies) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", anomaly.type);
            map.put("section", anomaly.section);
            map.put("number", anomaly.number);
            map.put("page", anomaly.page);
            map.put("line", anomaly.line);
            map.put("detail", anomaly.detail);
            anomalies.add(map);
        }
        return anomalies;
    }

    private static Map<String, Object> summaryMap(Model.ParseResult parsed, Model.Geometry geometry) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", parsed.questions.size());

        Map<String, Object> byType = new LinkedHashMap<>();
        for (Model.Question question : parsed.questions) {
            byType.put(question.type, ((Number) byType.getOrDefault(question.type, 0)).intValue() + 1);
        }
        summary.put("byType", byType);

        int review = 0;
        Map<String, Object> reviewReasons = new LinkedHashMap<>();
        for (Model.Question q : parsed.questions) {
            if (q.needsReview) review++;
            for (String reason : q.reviewReasons) {
                reviewReasons.put(reason, ((Number) reviewReasons.getOrDefault(reason, 0)).intValue() + 1);
            }
        }
        summary.put("withReview", review);
        summary.put("reviewReasons", reviewReasons);

        Map<String, Object> numbering = new LinkedHashMap<>();
        for (String type : byType.keySet()) {
            List<Integer> numbers = new ArrayList<>();
            for (Model.Question q : parsed.questions) {
                if (q.type.equals(type) && q.numberValue != null) numbers.add(q.numberValue);
            }
            numbers.sort(Integer::compareTo);
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("count", byType.get(type));
            if (!numbers.isEmpty()) {
                n.put("min", numbers.get(0));
                n.put("max", numbers.get(numbers.size() - 1));
                List<Object> missing = new ArrayList<>();
                for (Model.Anomaly anomaly : parsed.anomalies) if ("missing_number".equals(anomaly.type) && type.equals(anomaly.section)) missing.add(anomaly.number);
                n.put("missing", missing);
                List<Object> duplicates = new ArrayList<>();
                for (Model.Anomaly anomaly : parsed.anomalies) if ("duplicate_number".equals(anomaly.type) && type.equals(anomaly.section)) duplicates.add(anomaly.number);
                n.put("duplicates", duplicates);
            }
            numbering.put(type, n);
        }
        summary.put("numbering", numbering);

        int matched = 0;
        int crossLine = 0;
        for (Model.Underline u : geometry.underlines) if (u.matched) matched++;
        int fillOriginalRegions = 0;
        int fillLearningBlanks = 0;
        for (Model.Question q : parsed.questions) {
            if ("fill_blank".equals(q.type)) {
                fillOriginalRegions += q.blanks.size();
                fillLearningBlanks += q.learningBlankCount;
            }
            for (Model.BlankSpan blank : q.blanks) if (blank.crossLine) crossLine++;
        }
        Map<String, Object> underlines = new LinkedHashMap<>();
        underlines.put("total", geometry.underlines.size());
        underlines.put("matched", matched);
        underlines.put("unmatched", geometry.underlines.size() - matched);
        underlines.put("crossLineBlanks", crossLine);
        underlines.put("crossLineMerges", crossLine);
        summary.put("underlines", underlines);
        summary.put("fillOriginalBlankRegions", fillOriginalRegions);
        summary.put("fillLearningBlanks", fillLearningBlanks);
        summary.put("footerLinesRemoved", geometry.footerLines);
        summary.put("glyphCount", geometry.glyphCount);
        summary.put("textChars", geometry.textChars);

        int emptyAnswers = 0;
        int withOptions = 0;
        for (Model.Question q : parsed.questions) {
            if (q.answers.isEmpty()) emptyAnswers++;
            if (!q.options.isEmpty()) withOptions++;
        }
        summary.put("emptyAnswers", emptyAnswers);
        summary.put("questionsWithOptions", withOptions);
        return summary;
    }

    private static void writeText(Path path, Model.Geometry geometry) throws IOException {
        try (PrintStream out = new PrintStream(path.toFile(), StandardCharsets.UTF_8)) {
            for (Model.PageInfo page : geometry.pages) {
                out.println("===== PAGE " + page.number + " / " + geometry.pages.size() + " =====");
                for (Model.Line line : geometry.lines) {
                    if (line.page == page.number) out.println(line.text);
                }
                out.println();
            }
        }
    }

    private static void writeLines(Path path, Model.Geometry geometry) throws IOException {
        try (PrintStream out = new PrintStream(path.toFile(), StandardCharsets.UTF_8)) {
            out.println("page\tline\tglobal\tx0\tx1\ttop\tbaseline\tbottom\tfontSize\tfooter\ttext");
            for (Model.Line line : geometry.lines) {
                out.printf(java.util.Locale.ROOT, "%d\t%d\t%d\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.1f\t%s\t%s%n",
                        line.page, line.lineNo, line.globalIndex, line.x0, line.x1, line.top,
                        line.baseline, line.bottom, line.fontSize, line.footer ? "1" : "0",
                        tsv(line.text));
            }
        }
    }

    private static void writeGlyphs(Path path, Model.Geometry geometry) throws IOException {
        try (PrintStream out = new PrintStream(path.toFile(), StandardCharsets.UTF_8)) {
            out.println("page\tline\tseq\tx\tbaseline\twidth\theight\tfontSize\tfont\ttext");
            for (Model.Line line : geometry.lines) {
                for (int i = 0; i < line.glyphs.size(); i++) {
                    Model.Glyph g = line.glyphs.get(i);
                    out.printf(java.util.Locale.ROOT, "%d\t%d\t%d\t%.2f\t%.2f\t%.2f\t%.2f\t%.1f\t%s\t%s%n",
                            line.page, line.lineNo, i, g.x, g.baseline, g.width, g.height,
                            g.fontSize, tsv(g.font), tsv(g.text));
                }
            }
        }
    }

    private static void writeUnderlines(Path path, Model.Geometry geometry) throws IOException {
        try (PrintStream out = new PrintStream(path.toFile(), StandardCharsets.UTF_8)) {
            out.println("page\tx0\tx1\ty\twidth\tsource\tlineGlobal\tlineNo\tglyphStart\tglyphEnd\tmatched\tpartial\trawText\tcleanText");
            for (Model.Underline u : geometry.underlines) {
                Model.Line line = geometry.lineByGlobal(u.lineGlobal);
                out.printf(java.util.Locale.ROOT, "%d\t%.2f\t%.2f\t%.2f\t%.2f\t%s\t%d\t%d\t%d\t%d\t%s\t%s\t%s\t%s%n",
                        u.page, u.x0, u.x1, u.y, u.lineWidth, u.source,
                        u.lineGlobal, line == null ? -1 : line.lineNo,
                        u.glyphStart, u.glyphEnd, u.matched ? "1" : "0",
                        u.boundaryPartial ? "1" : "0", tsv(u.rawText), tsv(u.cleanText));
            }
        }
    }

    private static void render(PDDocument doc, List<Integer> pages, Path dir) throws IOException {
        PDFRenderer renderer = new PDFRenderer(doc);
        for (Integer page : pages) {
            if (page == null || page < 1 || page > doc.getNumberOfPages()) continue;
            BufferedImage image = renderer.renderImageWithDPI(page - 1, 150);
            File file = dir.resolve(String.format("page-%02d.png", page)).toFile();
            ImageIO.write(image, "png", file);
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) md.update(buffer, 0, read);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String tsv(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\t", "\\t")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static void writeUtf8(Path path, String text) throws IOException {
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }
}
