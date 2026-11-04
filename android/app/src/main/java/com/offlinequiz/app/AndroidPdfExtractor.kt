package com.offlinequiz.app

import android.graphics.Path
import android.graphics.PointF
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.IOException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import pdfprobe.Model

/**
 * Android-side extractor. The pure Model/QuestionParser rules are shared with the
 * desktop probe; this file is the only place that touches PdfBox-Android graphics.
 */
object AndroidPdfExtractor {

    fun extract(document: PDDocument): Model.Geometry {
        val geometry = Model.Geometry()
        var globalIndex = 0

        for (pageIndex in 0 until document.numberOfPages) {
            val pageNo = pageIndex + 1
            val page = document.getPage(pageIndex)
            val crop = page.cropBox
            val info = Model.PageInfo()
            info.number = pageNo
            info.width = crop.width
            info.height = crop.height
            info.rotation = page.rotation

            val collector = GlyphCollector()
            collector.startPage = pageNo
            collector.endPage = pageNo
            collector.getText(document)

            val glyphs = dedupe(collector.glyphs, geometry)
            info.glyphCount = glyphs.size
            geometry.glyphCount += glyphs.size
            val sortedGlyphs = glyphs.sortedWith(
                compareBy<Model.Glyph> { it.baseline }.thenBy { it.x }
            )

            val clusters = mutableListOf<MutableList<Model.Glyph>>()
            var current: MutableList<Model.Glyph>? = null
            for (glyph in sortedGlyphs) {
                if (current == null || abs(glyph.baseline - current[0].baseline) > 1.5f) {
                    current = mutableListOf()
                    clusters.add(current)
                }
                current.add(glyph)
            }
            clusters.sortBy { it[0].baseline }

            var lineNo = 0
            for (cluster in clusters) {
                cluster.sortBy { it.x }
                val line = buildLine(pageNo, ++lineNo, globalIndex++, info, cluster)
                geometry.lines.add(line)
                if (line.footer) geometry.footerLines++
            }
            info.lineCount = lineNo
            geometry.pages.add(info)

            PageGraphics(page, pageNo, info.height, geometry.underlines).processPage(page)
        }

        geometry.textChars = geometry.completeText(false).replace(Regex("\\s"), "").length
        matchUnderlines(geometry)
        return geometry
    }

    private fun dedupe(input: List<Model.Glyph>, geometry: Model.Geometry): List<Model.Glyph> {
        val out = mutableListOf<Model.Glyph>()
        val seen = HashSet<String>()
        for (glyph in input) {
            val text = glyph.text ?: continue
            if (text.trim().isEmpty()) continue
            val key = "$text|${Math.round(glyph.x * 20.0)}|${Math.round(glyph.baseline * 20.0)}"
            if (!seen.add(key)) {
                geometry.duplicateGlyphs++
                continue
            }
            out.add(glyph)
        }
        return out
    }

    private fun buildLine(
        pageNo: Int,
        lineNo: Int,
        globalIndex: Int,
        info: Model.PageInfo,
        glyphs: List<Model.Glyph>
    ): Model.Line {
        val line = Model.Line()
        line.page = pageNo
        line.lineNo = lineNo
        line.globalIndex = globalIndex
        line.glyphs.addAll(glyphs)
        line.x0 = Float.MAX_VALUE
        line.x1 = -Float.MAX_VALUE
        line.top = Float.MAX_VALUE
        line.baseline = -Float.MAX_VALUE
        line.bottom = -Float.MAX_VALUE

        val text = StringBuilder()
        var previous: Model.Glyph? = null
        for (glyph in glyphs) {
            if (previous != null) {
                val gap = glyph.x - (previous.x + previous.width)
                if (gap > 2.0f) text.append(' ')
            }
            glyph.textStart = text.length
            text.append(glyph.text)
            glyph.textEnd = text.length
            line.x0 = min(line.x0, glyph.x)
            line.x1 = maxOf(line.x1, glyph.x + glyph.width)
            line.top = min(line.top, glyph.baseline - glyph.height)
            line.baseline = maxOf(line.baseline, glyph.baseline)
            line.bottom = maxOf(line.bottom, glyph.baseline)
            line.fontSize = maxOf(line.fontSize, glyph.fontSize)
            previous = glyph
        }
        line.text = text.toString()
        line.footer = line.baseline > info.height - 100.0f &&
            line.text.matches(Regex("^\\d{1,3}\\s*/\\s*\\d{1,3}$"))
        return line
    }

    private fun matchUnderlines(geometry: Model.Geometry) {
        for (underline in geometry.underlines) {
            var best: Model.Line? = null
            var bestDistance = Float.MAX_VALUE
            for (line in geometry.lines) {
                if (line.page != underline.page) continue
                val distance = abs(underline.y - line.baseline)
                if (distance < bestDistance &&
                    underline.y >= line.baseline - 1.5f &&
                    underline.y <= line.baseline + 6.5f
                ) {
                    best = line
                    bestDistance = distance
                }
            }
            val line = best ?: continue

            val tolerance = maxOf(1.0f, line.fontSize * 0.12f)
            var start = -1
            var end = -1
            for (index in line.glyphs.indices) {
                val glyph = line.glyphs[index]
                val center = glyph.x + glyph.width / 2.0f
                if (center >= underline.x0 - tolerance && center <= underline.x1 + tolerance) {
                    if (start < 0) start = index
                    end = index
                }
            }
            if (start < 0) continue

            while (start <= end && isWhitespaceText(line.glyphs[start].text)) start++
            while (end >= start && isWhitespaceText(line.glyphs[end].text)) end--
            if (start > end) continue

            val raw = StringBuilder()
            for (index in start..end) raw.append(line.glyphs[index].text)
            val clean = raw.toString().trim()
            if (clean.isEmpty()) continue

            underline.lineGlobal = line.globalIndex
            underline.glyphStart = start
            underline.glyphEnd = end
            underline.rawText = raw.toString()
            underline.cleanText = clean
            underline.matched = true

            val first = line.glyphs[start]
            val last = line.glyphs[end]
            underline.boundaryPartial =
                overlapRatio(first, underline.x0, underline.x1) < 0.55f ||
                    overlapRatio(last, underline.x0, underline.x1) < 0.55f
        }
    }

    private fun overlapRatio(glyph: Model.Glyph, x0: Float, x1: Float): Float {
        val overlap = min(glyph.x + glyph.width, x1) - maxOf(glyph.x, x0)
        if (overlap <= 0 || glyph.width <= 0) return 0.0f
        return maxOf(0.0f, min(1.0f, overlap / glyph.width))
    }

    private fun isWhitespaceText(text: String?): Boolean {
        return text == null || text.trim().isEmpty()
    }

    private class GlyphCollector : PDFTextStripper() {
        val glyphs = mutableListOf<Model.Glyph>()

        init {
            sortByPosition = true
            setSuppressDuplicateOverlappingText(false)
        }

        override fun processTextPosition(text: TextPosition) {
            val glyph = Model.Glyph()
            glyph.text = text.unicode
            glyph.x = text.xDirAdj
            glyph.baseline = text.yDirAdj
            glyph.width = text.widthDirAdj
            glyph.height = text.heightDir
            glyph.fontSize = text.fontSizeInPt
            glyph.font = text.font?.name ?: ""
            glyphs.add(glyph)
        }
    }

    private class PageGraphics(
        page: PDPage,
        private val pageNo: Int,
        private val pageHeight: Float,
        private val underlines: MutableList<Model.Underline>
    ) : PDFGraphicsStreamEngine(page) {

        private val pathLines = mutableListOf<LineCandidate>()
        private val pathRects = mutableListOf<RectCandidate>()
        private var current: PointF? = null
        private var subpathStart: PointF? = null

        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
            val x0 = min(p0.x, p2.x)
            val x1 = maxOf(p0.x, p2.x)
            val y0 = min(p0.y, p2.y)
            val y1 = maxOf(p0.y, p2.y)
            pathRects.add(RectCandidate(x0, x1, y0, y1))
            current = PointF(p0.x, p0.y)
            subpathStart = current
        }

        override fun drawImage(pdImage: PDImage) {}

        override fun clip(windingRule: Path.FillType) {
            // W does not paint; leave the path for a possible following paint operator.
        }

        override fun moveTo(x: Float, y: Float) {
            current = PointF(x, y)
            subpathStart = current
        }

        override fun lineTo(x: Float, y: Float) {
            val from = current
            if (from != null) pathLines.add(LineCandidate(from.x, from.y, x, y))
            current = PointF(x, y)
        }

        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            current = PointF(x3, y3)
        }

        override fun getCurrentPoint(): PointF? = current

        override fun closePath() {
            val from = current
            val start = subpathStart
            if (from != null && start != null && hypot(from.x - start.x, from.y - start.y) > 0.2f) {
                pathLines.add(LineCandidate(from.x, from.y, start.x, start.y))
                current = PointF(start.x, start.y)
            }
        }

        override fun endPath() {
            clearPath()
        }

        override fun strokePath() {
            val width = graphicsState.lineWidth
            val committed = HashSet<String>()
            for (line in pathLines) addLine(line, width, "stroke", committed)
            for (rect in pathRects) addRect(rect, width, "stroke-rect", committed)
            clearPath()
        }

        override fun fillPath(windingRule: Path.FillType) {
            val committed = HashSet<String>()
            for (rect in pathRects) addRect(rect, 0.0f, "fill-rect", committed)
            clearPath()
        }

        override fun fillAndStrokePath(windingRule: Path.FillType) {
            val width = graphicsState.lineWidth
            val committed = HashSet<String>()
            for (line in pathLines) addLine(line, width, "stroke", committed)
            for (rect in pathRects) addRect(rect, width, "stroke-rect", committed)
            clearPath()
        }

        override fun shadingFill(shadingName: COSName) {}

        private fun addLine(
            line: LineCandidate,
            width: Float,
            source: String,
            committed: MutableSet<String>
        ) {
            if (width <= 0.0f || width > 2.0f) return
            if (abs(line.y1 - line.y0) > 0.5f) return
            if (abs(line.x1 - line.x0) < 2.0f) return
            val x0 = min(line.x0, line.x1)
            val x1 = maxOf(line.x0, line.x1)
            val y = pageHeight - (line.y0 + line.y1) / 2.0f
            val key = String.format(Locale.ROOT, "L|%.2f|%.2f|%.2f", x0, x1, y)
            if (!committed.add(key)) return
            add(x0, x1, y, width, source)
        }

        private fun addRect(rect: RectCandidate, width: Float, source: String, committed: MutableSet<String>) {
            if (rect.y1 - rect.y0 > 2.0f) return
            if (rect.x1 - rect.x0 < 2.0f) return
            val y = pageHeight - (rect.y0 + rect.y1) / 2.0f
            val key = String.format(Locale.ROOT, "R|%.2f|%.2f|%.2f", rect.x0, rect.x1, y)
            if (!committed.add(key)) return
            add(rect.x0, rect.x1, y, width, source)
        }

        private fun add(x0: Float, x1: Float, y: Float, width: Float, source: String) {
            val underline = Model.Underline()
            underline.page = pageNo
            underline.x0 = x0
            underline.x1 = x1
            underline.y = y
            underline.lineWidth = width
            underline.source = source
            underlines.add(underline)
        }

        private fun clearPath() {
            pathLines.clear()
            pathRects.clear()
            current = null
            subpathStart = null
        }
    }

    private data class LineCandidate(
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float
    )

    private data class RectCandidate(
        val x0: Float,
        val x1: Float,
        val y0: Float,
        val y1: Float
    )
}
