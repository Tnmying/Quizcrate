package com.offlinequiz.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pdfprobe.Model
import pdfprobe.QuestionParser

class QuestionParserTest {

    @Test
    fun syntheticFiveTypesAndCrossLineUnderline() {
        val geometry = Model.Geometry()
        var global = 0

        fun add(text: String): Model.Line {
            val line = Model.Line()
            line.page = 1
            line.lineNo = global + 1
            line.globalIndex = global
            line.text = text
            line.x0 = 84f
            line.x1 = 510f
            line.baseline = 700f - global * 20f
            line.fontSize = 10f
            geometry.lines.add(line)
            global++
            return line
        }

        add("一、单选题")
        add("1.单选（A）")
        add("A.甲 B.乙 C.丙 D.丁")

        add("二、多选题")
        add("2.多选（AB）")
        add("A.甲 B.乙 C.丙 D.丁")

        add("三、判断题")
        add("1.判断（√）")

        add("四、填空题")
        val fillLine1 = add("1.请填入跨行答")
        fillLine1.x1 = 84f + fillLine1.text.length * 10f
        makeGlyphs(fillLine1)
        val fillLine2 = add("案。")
        fillLine2.x1 = 84f + fillLine2.text.length * 10f
        makeGlyphs(fillLine2)

        add("五、简答题")
        add("1.简答")
        add("参考答案：这是公开自制答案。")

        val underline1 = Model.Underline()
        underline1.page = 1
        underline1.lineGlobal = fillLine1.globalIndex
        underline1.glyphStart = fillLine1.text.lastIndex
        underline1.glyphEnd = fillLine1.text.lastIndex
        underline1.x0 = 84f + underline1.glyphStart * 10f
        underline1.x1 = 84f + (underline1.glyphEnd + 1) * 10f
        underline1.y = fillLine1.baseline + 1.2f
        underline1.cleanText = "答"
        underline1.rawText = "答"
        underline1.matched = true
        geometry.underlines.add(underline1)

        val underline2 = Model.Underline()
        underline2.page = 1
        underline2.lineGlobal = fillLine2.globalIndex
        underline2.glyphStart = 0
        underline2.glyphEnd = 0
        underline2.x0 = 84f
        underline2.x1 = 94f
        underline2.y = fillLine2.baseline + 1.2f
        underline2.cleanText = "案"
        underline2.rawText = "案"
        underline2.matched = true
        geometry.underlines.add(underline2)

        val result = QuestionParser.parse(geometry)
        assertEquals(5, result.questions.size)
        assertEquals("single_choice", result.questions[0].type)
        assertEquals("multiple_choice", result.questions[1].type)
        assertEquals("true_false", result.questions[2].type)
        assertEquals("fill_blank", result.questions[3].type)
        assertEquals("short_answer", result.questions[4].type)
        assertEquals(listOf("A"), result.questions[0].answers)
        assertEquals(listOf("A", "B"), result.questions[1].answers)
        assertEquals(listOf("√"), result.questions[2].answers)
        assertEquals(listOf("答案"), result.questions[3].answers)
        assertTrue(result.questions[3].blanks.single().crossLine)
        assertTrue(result.questions[3].stem.contains("____"))
        assertTrue(result.questions[4].referenceAnswer.contains("公开自制答案"))
    }

    @Test
    fun duplicateAndSixOptionMarkersRemainVisible() {
        val geometry = Model.Geometry()
        var global = 0

        fun add(text: String) {
            val line = Model.Line()
            line.page = 1
            line.lineNo = global + 1
            line.globalIndex = global
            line.text = text
            line.x0 = 84f
            line.x1 = 510f
            geometry.lines.add(line)
            global++
        }

        add("一、单选题")
        add("1.六选项（A）")
        add("A.one B.two C.three D.four E.five F.six")
        val six = QuestionParser.parse(geometry).questions.single()
        assertEquals(6, six.options.size)
        assertEquals("F", six.options[5].key)
        assertEquals("six", six.options[5].text)

        val duplicateGeometry = Model.Geometry()
        global = 0
        fun addDuplicate(text: String) {
            val line = Model.Line()
            line.page = 1
            line.lineNo = global + 1
            line.globalIndex = global
            line.text = text
            line.x0 = 84f
            line.x1 = 510f
            duplicateGeometry.lines.add(line)
            global++
        }
        addDuplicate("一、单选题")
        addDuplicate("1.重复（B）")
        addDuplicate("A.one B.two B.duplicate C.three D.four")
        val duplicate = QuestionParser.parse(duplicateGeometry).questions.single()
        assertEquals(5, duplicate.options.size)
        assertTrue(duplicate.needsReview)
        assertTrue(duplicate.reviewReasons.contains("duplicate_option_marker"))
        assertTrue(duplicate.optionRawText.contains("B.duplicate"))
    }

    @Test
    fun blankRulesSplitOnlyOnChineseEnumerationComma() {
        val geometry = baseFillGeometry("1.有理想、敢担当、能吃苦、肯奋斗")
        val line = geometry.lines[1]
        addUnderline(geometry, line, 2, line.text.length - 1, line.text.substring(2))
        val question = QuestionParser.parse(geometry).questions.single()
        assertEquals(1, question.blanks.size)
        assertEquals(4, question.learningBlankCount)
        assertEquals(listOf("有理想", "敢担当", "能吃苦", "肯奋斗"), question.answers)
        assertEquals("____、____、____、____", question.stem)
        assertFalse(question.needsReview)
    }

    @Test
    fun blankRulesCrossLineMergesBeforeSplittingAndGapDoesNotMerge() {
        val geometry = baseFillGeometry("1.有理想、敢担当、")
        val first = geometry.lines[1]
        first.x1 = 84f + first.text.length * 10f
        val second = addLine(geometry, "能吃苦、肯奋斗")
        addUnderline(geometry, first, 2, first.text.length - 1, first.text.substring(2))
        addUnderline(geometry, second, 0, second.text.length - 1, second.text)
        val question = QuestionParser.parse(geometry).questions.single()
        assertEquals(4, question.learningBlankCount)
        assertTrue(question.blanks.single().crossLine)
        assertFalse(question.needsReview)

        val gapGeometry = baseFillGeometry("1.前文答案、")
        val gapFirst = gapGeometry.lines[1]
        gapFirst.x1 = gapFirst.x0 + (gapFirst.text.length - 1) * 10f
        val gapSecond = addLine(gapGeometry, "后文")
        val answerStart = gapFirst.text.indexOf("答案")
        addUnderline(gapGeometry, gapFirst, answerStart, answerStart + 1, "答案")
        addUnderline(gapGeometry, gapSecond, 0, gapSecond.text.length - 1, "后文")
        val gapQuestion = QuestionParser.parse(gapGeometry).questions.single()
        assertEquals(2, gapQuestion.blanks.size)
        assertEquals(listOf("答案", "后文"), gapQuestion.answers)
        assertEquals("前文____、____", gapQuestion.stem)
        assertFalse(gapQuestion.needsReview)
    }

    private fun baseFillGeometry(question: String): Model.Geometry {
        val geometry = Model.Geometry()
        addLine(geometry, "四、填空题")
        addLine(geometry, question)
        return geometry
    }

    private fun addLine(geometry: Model.Geometry, text: String): Model.Line {
        val line = Model.Line()
        line.page = 1
        line.lineNo = geometry.lines.size + 1
        line.globalIndex = geometry.lines.size
        line.text = text
        line.x0 = 84f
        line.x1 = 84f + text.length * 10f
        line.baseline = 700f - line.lineNo * 20f
        line.fontSize = 10f
        text.forEachIndexed { index, char ->
            val glyph = Model.Glyph()
            glyph.text = char.toString()
            glyph.textStart = index
            glyph.textEnd = index + 1
            glyph.x = 84f + index * 10f
            glyph.baseline = line.baseline
            glyph.width = 10f
            glyph.height = 5f
            glyph.fontSize = 10f
            line.glyphs.add(glyph)
        }
        geometry.lines.add(line)
        return line
    }

    private fun addUnderline(
        geometry: Model.Geometry,
        line: Model.Line,
        glyphStart: Int,
        glyphEnd: Int,
        text: String
    ) {
        val underline = Model.Underline()
        underline.page = line.page
        underline.lineGlobal = line.globalIndex
        underline.glyphStart = glyphStart
        underline.glyphEnd = glyphEnd
        underline.x0 = line.glyphs[glyphStart].x
        underline.x1 = line.glyphs[glyphEnd].x + line.glyphs[glyphEnd].width
        underline.y = line.baseline + 1.2f
        underline.rawText = text
        underline.cleanText = text
        underline.matched = true
        geometry.underlines.add(underline)
    }

    private fun makeGlyphs(line: Model.Line) {
        var x = 84f
        for (index in line.text.indices) {
            val glyph = Model.Glyph()
            glyph.text = line.text[index].toString()
            glyph.textStart = index
            glyph.textEnd = index + 1
            glyph.x = x
            glyph.baseline = line.baseline
            glyph.width = 10f
            glyph.height = 5f
            glyph.fontSize = 10f
            line.glyphs.add(glyph)
            x += 10f
        }
    }
}
