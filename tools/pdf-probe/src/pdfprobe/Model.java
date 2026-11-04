package pdfprobe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Data model shared by extraction and parsing. */
public final class Model {
    private Model() {}

    public static final class PageInfo {
        public int number;
        public float width;
        public float height;
        public int rotation;
        public int glyphCount;
        public int lineCount;
    }

    public static final class Glyph {
        public String text;
        public float x;
        public float baseline;
        public float width;
        public float height;
        public float fontSize;
        public String font;
        public int textStart;
        public int textEnd;

        public float xEnd() { return x + width; }
    }

    public static final class Line {
        public int page;
        public int lineNo;
        public int globalIndex;
        public float x0;
        public float x1;
        public float top;
        public float baseline;
        public float bottom;
        public float fontSize;
        public boolean footer;
        public String text;
        public final List<Glyph> glyphs = new ArrayList<>();
    }

    public static final class Underline {
        public int page;
        public float x0;
        public float x1;
        public float y;
        public float lineWidth;
        public String source = "stroke";
        public int lineGlobal = -1;
        public int glyphStart = -1;
        public int glyphEnd = -1;
        public String rawText = "";
        public String cleanText = "";
        public boolean matched;
        public boolean boundaryPartial;
    }

    public static final class Geometry {
        public final List<PageInfo> pages = new ArrayList<>();
        public final List<Line> lines = new ArrayList<>();
        public final List<Underline> underlines = new ArrayList<>();
        public int footerLines;
        public int duplicateGlyphs;
        public int glyphCount;
        public int textChars;

        public Line lineByGlobal(int global) {
            if (global < 0 || global >= lines.size()) return null;
            return lines.get(global);
        }

        public String pageText(int page, boolean includeFooter) {
            StringBuilder sb = new StringBuilder();
            for (Line line : lines) {
                if (line.page != page) continue;
                if (!includeFooter && line.footer) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(line.text);
            }
            return sb.toString();
        }

        public String completeText(boolean includeFooter) {
            StringBuilder sb = new StringBuilder();
            for (Line line : lines) {
                if (!includeFooter && line.footer) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(line.text);
            }
            return sb.toString();
        }

        public Set<Integer> pagesOf(List<Line> bodyLines) {
            Set<Integer> pages = new LinkedHashSet<>();
            for (Line line : bodyLines) pages.add(line.page);
            return pages;
        }
    }

    public static final class Option {
        public String key;
        public String text;
        public int markerIndex;
        public boolean duplicate;
        public boolean supported = true;
    }

    public static final class SourceLine {
        public int page;
        public int line;
    }

    public static final class Segment {
        public int page;
        public int line;
        public float x0;
        public float x1;
        public float y;
        public String text;
    }

    public static final class LearningBlank {
        public String answer;
        public int sentenceStart;
        public int sentenceEnd;
    }

    public static final class BlankSpan {
        public String answer;
        public String originalAnswer;
        public boolean crossLine;
        public boolean splitByEnumeration;
        public int sentenceStart;
        public int sentenceEnd;
        public final List<Segment> segments = new ArrayList<>();
        public final List<LearningBlank> learningBlanks = new ArrayList<>();
    }

    public static final class Question {
        public String id;
        public String type;
        public String sectionTitle;
        public String originalNumber;
        public Integer numberValue;
        public String stem;
        public String stemWithAnswer;
        public String originalSentence;
        public String referenceAnswer;
        public String optionRawText;
        public final List<Option> options = new ArrayList<>();
        public final List<String> answers = new ArrayList<>();
        public final List<Integer> sourcePages = new ArrayList<>();
        public final List<SourceLine> sourceLines = new ArrayList<>();
        public final List<BlankSpan> blanks = new ArrayList<>();
        public int learningBlankCount;
        public final List<String> reviewReasons = new ArrayList<>();
        public boolean needsReview;
    }

    public static final class Section {
        public String type;
        public String title;
        public int page;
        public int count;
    }

    public static final class Anomaly {
        public String type;
        public String section;
        public Integer number;
        public Integer page;
        public Integer line;
        public String detail;
    }

    public static final class ParseResult {
        public final List<Question> questions = new ArrayList<>();
        public final List<Section> sections = new ArrayList<>();
        public final List<Anomaly> anomalies = new ArrayList<>();
    }
}
