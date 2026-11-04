package pdfprobe;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/** Reads glyph positions and horizontal stroked paths from a text-layer PDF. */
public final class TextExtractor {
    private TextExtractor() {}

    public static Model.Geometry extract(PDDocument doc) throws IOException {
        Model.Geometry geometry = new Model.Geometry();
        int globalIndex = 0;

        for (int p = 0; p < doc.getNumberOfPages(); p++) {
            int pageNo = p + 1;
            PDPage page = doc.getPage(p);
            PDRectangle crop = page.getCropBox();
            Model.PageInfo info = new Model.PageInfo();
            info.number = pageNo;
            info.width = crop.getWidth();
            info.height = crop.getHeight();
            info.rotation = page.getRotation();

            GlyphCollector collector = new GlyphCollector();
            collector.setStartPage(pageNo);
            collector.setEndPage(pageNo);
            collector.getText(doc);

            List<Model.Glyph> glyphs = dedupe(collector.glyphs, geometry);
            info.glyphCount = glyphs.size();
            geometry.glyphCount += glyphs.size();
            glyphs.sort(Comparator.comparingDouble((Model.Glyph g) -> g.baseline)
                    .thenComparingDouble(g -> g.x));

            List<List<Model.Glyph>> clusters = new ArrayList<>();
            List<Model.Glyph> current = null;
            for (Model.Glyph g : glyphs) {
                if (current == null || Math.abs(g.baseline - current.get(0).baseline) > 1.5) {
                    current = new ArrayList<>();
                    clusters.add(current);
                }
                current.add(g);
            }
            clusters.sort(Comparator.comparingDouble(c -> c.get(0).baseline));

            int lineNo = 0;
            for (List<Model.Glyph> cluster : clusters) {
                cluster.sort(Comparator.comparingDouble(g -> g.x));
                Model.Line line = buildLine(pageNo, ++lineNo, globalIndex++, info, cluster);
                geometry.lines.add(line);
                if (line.footer) geometry.footerLines++;
            }
            info.lineCount = lineNo;
            geometry.pages.add(info);

            PageGraphics graphics = new PageGraphics(page, pageNo, info.height, geometry.underlines);
            graphics.processPage(page);
        }

        geometry.textChars = geometry.completeText(false).replaceAll("\\s", "").length();
        matchUnderlines(geometry);
        return geometry;
    }

    private static List<Model.Glyph> dedupe(List<Model.Glyph> input, Model.Geometry geometry) {
        List<Model.Glyph> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Model.Glyph g : input) {
            if (g.text == null || g.text.trim().isEmpty()) continue;
            String key = g.text + "|" + Math.round(g.x * 20.0) + "|" + Math.round(g.baseline * 20.0);
            if (!seen.add(key)) {
                geometry.duplicateGlyphs++;
                continue;
            }
            out.add(g);
        }
        return out;
    }

    private static Model.Line buildLine(int pageNo, int lineNo, int globalIndex,
                                        Model.PageInfo info, List<Model.Glyph> glyphs) {
        Model.Line line = new Model.Line();
        line.page = pageNo;
        line.lineNo = lineNo;
        line.globalIndex = globalIndex;
        line.glyphs.addAll(glyphs);
        line.x0 = Float.MAX_VALUE;
        line.x1 = -Float.MAX_VALUE;
        line.top = Float.MAX_VALUE;
        line.baseline = -Float.MAX_VALUE;
        line.bottom = -Float.MAX_VALUE;

        StringBuilder text = new StringBuilder();
        Model.Glyph prev = null;
        for (Model.Glyph g : glyphs) {
            if (prev != null) {
                float gap = g.x - (prev.x + prev.width);
                if (gap > 2.0f) text.append(' ');
            }
            g.textStart = text.length();
            text.append(g.text);
            g.textEnd = text.length();
            line.x0 = Math.min(line.x0, g.x);
            line.x1 = Math.max(line.x1, g.x + g.width);
            line.top = Math.min(line.top, g.baseline - g.height);
            line.baseline = Math.max(line.baseline, g.baseline);
            line.bottom = Math.max(line.bottom, g.baseline);
            line.fontSize = Math.max(line.fontSize, g.fontSize);
            prev = g;
        }
        line.text = text.toString();
        line.footer = line.baseline > info.height - 100.0f
                && line.text.matches("^\\d{1,3}\\s*/\\s*\\d{1,3}$");
        return line;
    }

    private static void matchUnderlines(Model.Geometry geometry) {
        for (Model.Underline u : geometry.underlines) {
            Model.Line best = null;
            float bestDistance = Float.MAX_VALUE;
            for (Model.Line line : geometry.lines) {
                if (line.page != u.page) continue;
                float d = Math.abs(u.y - line.baseline);
                if (d < bestDistance && u.y >= line.baseline - 1.5f && u.y <= line.baseline + 6.5f) {
                    best = line;
                    bestDistance = d;
                }
            }
            if (best == null) continue;

            float tolerance = Math.max(1.0f, best.fontSize * 0.12f);
            int start = -1;
            int end = -1;
            for (int i = 0; i < best.glyphs.size(); i++) {
                Model.Glyph g = best.glyphs.get(i);
                float center = g.x + g.width / 2.0f;
                if (center >= u.x0 - tolerance && center <= u.x1 + tolerance) {
                    if (start < 0) start = i;
                    end = i;
                }
            }
            if (start < 0) continue;

            while (start <= end && isWhitespaceText(best.glyphs.get(start).text)) start++;
            while (end >= start && isWhitespaceText(best.glyphs.get(end).text)) end--;
            if (start > end) continue;

            StringBuilder raw = new StringBuilder();
            for (int i = start; i <= end; i++) raw.append(best.glyphs.get(i).text);
            String clean = raw.toString().trim();
            if (clean.isEmpty()) continue;

            u.lineGlobal = best.globalIndex;
            u.glyphStart = start;
            u.glyphEnd = end;
            u.rawText = raw.toString();
            u.cleanText = clean;
            u.matched = true;

            Model.Glyph first = best.glyphs.get(start);
            Model.Glyph last = best.glyphs.get(end);
            u.boundaryPartial = overlapRatio(first, u.x0, u.x1) < 0.55f
                    || overlapRatio(last, u.x0, u.x1) < 0.55f;
        }
    }

    private static float overlapRatio(Model.Glyph g, float x0, float x1) {
        float overlap = Math.min(g.x + g.width, x1) - Math.max(g.x, x0);
        if (overlap <= 0 || g.width <= 0) return 0.0f;
        return Math.max(0.0f, Math.min(1.0f, overlap / g.width));
    }

    private static boolean isWhitespaceText(String text) {
        return text == null || text.trim().isEmpty();
    }

    private static final class GlyphCollector extends PDFTextStripper {
        final List<Model.Glyph> glyphs = new ArrayList<>();

        GlyphCollector() throws IOException {
            super();
            setSortByPosition(true);
            setSuppressDuplicateOverlappingText(false);
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            Model.Glyph g = new Model.Glyph();
            g.text = text.getUnicode();
            g.x = text.getXDirAdj();
            g.baseline = text.getYDirAdj();
            g.width = text.getWidthDirAdj();
            g.height = text.getHeightDir();
            g.fontSize = text.getFontSizeInPt();
            g.font = text.getFont() == null ? "" : text.getFont().getName();
            glyphs.add(g);
        }

        @Override
        protected void writeString(String text) {
            // The diagnostic output is rebuilt from glyph positions; no text stream is needed.
        }
    }

    private static final class PageGraphics extends PDFGraphicsStreamEngine {
        private final int pageNo;
        private final float pageHeight;
        private final List<Model.Underline> underlines;
        private final List<LineCandidate> pathLines = new ArrayList<>();
        private final List<RectCandidate> pathRects = new ArrayList<>();
        private Point2D.Float current;
        private Point2D.Float subpathStart;

        PageGraphics(PDPage page, int pageNo, float pageHeight, List<Model.Underline> underlines) {
            super(page);
            this.pageNo = pageNo;
            this.pageHeight = pageHeight;
            this.underlines = underlines;
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            // A rectangle becomes a candidate only when a later paint operator
            // (stroke/fill) actually paints this path. "n" discards it.
            float x0 = (float) Math.min(p0.getX(), p2.getX());
            float x1 = (float) Math.max(p0.getX(), p2.getX());
            float y0 = (float) Math.min(p0.getY(), p2.getY());
            float y1 = (float) Math.max(p0.getY(), p2.getY());
            pathRects.add(new RectCandidate(x0, x1, y0, y1));
            current = new Point2D.Float((float) p0.getX(), (float) p0.getY());
            subpathStart = current;
        }

        @Override
        public void drawImage(PDImage pdImage) { }

        @Override
        public void clip(int windingRule) {
            // W modifies the clipping path but does not paint the current path.
            // Leave it intact so a following painting operator can commit it.
        }

        @Override
        public void moveTo(float x, float y) {
            current = new Point2D.Float(x, y);
            subpathStart = current;
        }

        @Override
        public void lineTo(float x, float y) {
            if (current != null) {
                pathLines.add(new LineCandidate(current.x, current.y, x, y));
            }
            current = new Point2D.Float(x, y);
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            current = new Point2D.Float(x3, y3);
        }

        @Override
        public Point2D getCurrentPoint() {
            return current;
        }

        @Override
        public void closePath() {
            if (current != null && subpathStart != null
                    && current.distance(subpathStart) > 0.2f) {
                pathLines.add(new LineCandidate(current.x, current.y,
                        subpathStart.x, subpathStart.y));
                current = subpathStart;
            }
        }

        @Override
        public void endPath() {
            clearPath();
        }

        @Override
        public void strokePath() {
            float width = getGraphicsState().getLineWidth();
            Set<String> committed = new HashSet<>();
            for (LineCandidate line : pathLines) {
                addLine(line, width, "stroke", committed);
            }
            for (RectCandidate rect : pathRects) {
                addRect(rect, width, "stroke-rect", committed);
            }
            clearPath();
        }

        @Override
        public void fillPath(int windingRule) {
            Set<String> committed = new HashSet<>();
            for (RectCandidate rect : pathRects) {
                addRect(rect, 0.0f, "fill-rect", committed);
            }
            clearPath();
        }

        @Override
        public void fillAndStrokePath(int windingRule) {
            float width = getGraphicsState().getLineWidth();
            Set<String> committed = new HashSet<>();
            for (LineCandidate line : pathLines) {
                addLine(line, width, "stroke", committed);
            }
            for (RectCandidate rect : pathRects) {
                addRect(rect, width, "stroke-rect", committed);
            }
            clearPath();
        }

        @Override
        public void shadingFill(COSName shadingName) { }

        private void addLine(LineCandidate line, float width, String source,
                             Set<String> committed) {
            if (width <= 0.0f || width > 2.0f) return;
            if (Math.abs(line.y1 - line.y0) > 0.5f) return;
            if (Math.abs(line.x1 - line.x0) < 2.0f) return;
            float x0 = Math.min(line.x0, line.x1);
            float x1 = Math.max(line.x0, line.x1);
            float y = pageHeight - (line.y0 + line.y1) / 2.0f;
            String key = String.format(java.util.Locale.ROOT, "L|%.2f|%.2f|%.2f", x0, x1, y);
            if (!committed.add(key)) return;
            add(x0, x1, y, width, source);
        }

        private void addRect(RectCandidate rect, float width, String source,
                             Set<String> committed) {
            if (rect.y1 - rect.y0 > 2.0f) return;
            if (rect.x1 - rect.x0 < 2.0f) return;
            float y = pageHeight - (rect.y0 + rect.y1) / 2.0f;
            String key = String.format(java.util.Locale.ROOT, "R|%.2f|%.2f|%.2f",
                    rect.x0, rect.x1, y);
            if (!committed.add(key)) return;
            add(rect.x0, rect.x1, y, width, source);
        }

        private void add(float x0, float x1, float y, float width, String source) {
            Model.Underline u = new Model.Underline();
            u.page = pageNo;
            u.x0 = x0;
            u.x1 = x1;
            u.y = y;
            u.lineWidth = width;
            u.source = source;
            underlines.add(u);
        }

        private void clearPath() {
            pathLines.clear();
            pathRects.clear();
            current = null;
            subpathStart = null;
        }
    }

    private static final class LineCandidate {
        final float x0;
        final float y0;
        final float x1;
        final float y1;

        LineCandidate(float x0, float y0, float x1, float y1) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
        }
    }

    private static final class RectCandidate {
        final float x0;
        final float x1;
        final float y0;
        final float y1;

        RectCandidate(float x0, float x1, float y0, float y1) {
            this.x0 = x0;
            this.x1 = x1;
            this.y0 = y0;
            this.y1 = y1;
        }
    }
}
