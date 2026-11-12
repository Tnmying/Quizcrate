package com.offlinequiz.app

import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import org.junit.Assert.*
import org.junit.Test

class TextImportParserTest {
    private val choice = "1、哪项是水？\nA、H2O\nB、CO2\n答案：A"
    private fun parse(text: String, type: String = "single_choice") = TextImportParser.parse(text, defaultType = type).result

    @Test fun headerlessChoiceWithExplicitTypeAndAnswer() {
        val imported = TextImportParser.parse(choice, defaultType = "single_choice")
        val q = imported.result.questions.single()
        assertEquals(listOf("A"), q.answers)
        assertEquals(listOf("H2O", "CO2"), q.options.map { it.text })
        assertFalse(q.needsReview)
        assertEquals(choice, imported.sourceText)
        assertTrue(q.sourcePages.isEmpty())
        assertEquals(listOf(1, 2, 3, 4), q.sourceLines.map { it.line })
    }

    @Test fun numberingAndOptionVariants() {
        for (number in listOf("1.", "1．", "1、", "（1）", "(1)", "第1题")) {
            for (marker in listOf(".", "．", "、", ")", "）")) {
                val q = parse("${number}题目（A）\nA${marker}甲\nB${marker}乙").questions.single()
                assertEquals("1", q.originalNumber)
                assertEquals(2, q.options.size)
                assertEquals(listOf("A"), q.answers)
            }
        }
    }

    @Test fun typedSectionsOverrideImportDefault() {
        val q = parse("一、多选题\n1. 多选\nA.甲 B.乙 C.丙\n答案：A、C").questions.single()
        assertEquals("multiple_choice", q.type)
        assertEquals(listOf("A", "C"), q.answers)
    }

    @Test fun headerlessChoiceTypeUsesOptionsAndExplicitAnswer() {
        val q = parse(choice, "unknown").questions.single()
        assertEquals("single_choice", q.type)
        assertFalse(q.needsReview)
    }

    @Test fun uniqueAnswerTableDoesNotBecomeQuestions() {
        val q = parse("1. 水？\nA.水 B.气\n参考答案\n1. A").questions.single()
        assertEquals(listOf("A"), q.answers)
        assertEquals("气", q.options.last().text)
        assertFalse(q.needsReview)
    }

    @Test fun repeatedQuestionNumbersAreNotMatchedByPosition() {
        val result = parse("一、单选题\n1.甲？\nA.甲 B.乙\n二、单选题\n1.乙？\nA.甲 B.乙\n参考答案\n1. A")
        assertEquals(2, result.questions.size)
        assertTrue(result.questions.all { it.answers.isEmpty() && it.reviewReasons.contains("ambiguous_answer_table") })
    }

    @Test fun conflictingAnswerSourcesRemainFlagged() {
        val q = parse("1.题目（A）\nA.甲 B.乙\n答案：B").questions.single()
        assertTrue(q.reviewReasons.contains("conflicting_answers"))
        assertEquals(listOf("A"), q.answers)
    }

    @Test fun repeatedAnswerLabelsRequireReview() {
        assertTrue(parse("$choice\n正确答案：B").questions.single().reviewReasons.contains("multiple_answer_labels"))
    }

    @Test fun analysisDoesNotEnterLastOption() {
        val q = parse("$choice\n解析：水的化学式是 H2O。").questions.single()
        assertEquals("CO2", q.options.last().text)
        assertEquals(listOf("A"), q.answers)
    }

    @Test fun unknownAnswerTableLineRemainsVisible() {
        val result = parse("1.题目\nA.甲 B.乙\n参考答案\n答案格式无法解析")
        assertEquals(1, result.questions.size)
        assertTrue(result.anomalies.any { it.type == "unparsed_answer_table" })
        assertTrue(result.questions.single().needsReview)
    }

    @Test fun trueFalseAndShortAnswerLabels() {
        assertEquals(listOf("×"), parse("1.太阳从西边升起。\n答案：错误", "true_false").questions.single().answers)
        assertEquals("液体表面的汽化。", parse("1.什么是蒸发？\n答案：液体表面的汽化。", "short_answer").questions.single().referenceAnswer)
    }

    @Test fun fillUsesExplicitPlaceholdersAndDoublePipeAnswers() {
        val q = parse("1.水由____和____组成。\n答案：氢||氧", "fill_blank").questions.single()
        assertEquals(listOf("氢", "氧"), q.answers)
        assertFalse(q.needsReview)
        assertTrue(parse("1.水由____和____组成。\n答案：氢、氧", "fill_blank").questions.single().needsReview)
    }

    @Test fun paragraphsBeforeQuestionsAreReported() {
        assertTrue(parse("说明文字\n$choice").anomalies.any { it.type == "unparsed_text" && it.line == 1 })
    }

    @Test fun utf8Utf16AndGb18030Decoding() {
        for (encoding in listOf("UTF-8", "UTF-16", "GB18030")) {
            val bytes = choice.toByteArray(Charset.forName(encoding))
            assertEquals(choice, TextImportParser.decode(ByteArrayInputStream(bytes), if (encoding == "GB18030") encoding else "自动（UTF-8）"))
        }
        assertEquals(choice, TextImportParser.decode(ByteArrayInputStream(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + choice.toByteArray())))
    }

    @Test fun malformedEncodingAndOversizedContentRejected() {
        assertThrows(IllegalArgumentException::class.java) { TextImportParser.decode(ByteArrayInputStream(byteArrayOf(0xff.toByte()))) }
        assertThrows(IllegalArgumentException::class.java) { TextImportParser.decode(ByteArrayInputStream(ByteArray(TextImportParser.MAX_BYTES + 1))) }
        assertThrows(IllegalArgumentException::class.java) { TextImportParser.parse(" ") }
    }

    @Test fun decimalIsNotAQuestionNumber() {
        assertEquals(1, parse("$choice\n3.14 是圆周率的近似值").questions.size)
    }

    @Test fun numberedAnalysisStepsDoNotBecomeChoiceQuestions() {
        val result = parse("$choice\n解析：步骤如下\n1. 先检查组成\n2. 再看化学式\n2. 下列哪项是气体？\nA. 水 B. 氧气\n答案：B")
        assertEquals(2, result.questions.size)
        assertEquals(listOf("A"), result.questions[0].answers)
        assertEquals(listOf("B"), result.questions[1].answers)
    }

    @Test fun judgeConflictIsNotHiddenByTrailingAnswer() {
        val q = parse("1.题目（√）\n答案：错误", "true_false").questions.single()
        assertTrue(q.reviewReasons.contains("conflicting_answers"))
    }

    @Test fun bareReferenceLabelStillWorksInShortAnswer() {
        assertEquals(listOf("说明。"), parse("1.什么是示例？\n参考答案\n说明。", "short_answer").questions.single().answers)
    }

    @Test fun numberedShortAnswerPointsStayInsideAnswer() {
        val result = parse("1.写出两种办法？\n参考答案：\n1.第一种办法。\n2.第二种办法。\n2.写出一种办法？\n答案：另一种办法。", "short_answer")
        assertEquals(2, result.questions.size)
        assertTrue(result.questions[0].referenceAnswer.contains("第二种办法"))
        assertEquals("另一种办法。", result.questions[1].referenceAnswer)
    }
}
