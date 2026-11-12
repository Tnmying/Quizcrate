package com.offlinequiz.app

import org.junit.Assert.*
import org.junit.Test
import pdfprobe.AnswerTableParser

class StructuredRecognitionTest {
    private fun parse(text: String) = TextImportParser.parse(text).result
    private val first = "1.[单选题]哪项为偶数？\nA.2 B.3"
    private val second = "2.[单选题]哪项为奇数？\nA.2 B.3"

    @Test fun compactAnswerRowsSupportMultipleEntriesAndSeparators() {
        for (row in listOf("1.A 2.B", "1.A2.B", "（1）A；（2）B", "1 A,2 B", "１．Ａ　２．Ｂ")) {
            val result = parse("$first\n$second\n参考答案\n$row")
            assertEquals(row, 2, result.questions.size)
            assertEquals(row, listOf(listOf("A"), listOf("B")), result.questions.map { it.answers })
            assertTrue(row, result.questions.none { it.needsReview })
        }
    }

    @Test fun answerRegionCanEndBeforeAnotherQuestionSection() {
        val result = parse("一、单选题\n1.题目甲\nA.甲 B.乙\n参考答案\n1.A\n二、判断题\n1.题目乙\n正确答案：对")
        assertEquals(2, result.questions.size)
        assertEquals(listOf(listOf("A"), listOf("√")), result.questions.map { it.answers })
        assertTrue(result.questions.none { it.needsReview })
    }

    @Test fun localTablesRespectNumberResetsWithinSameType() {
        for (header in listOf("参考答案", "单选题参考答案")) {
            val result = parse("一、单选题\n1.题目甲\nA.甲 B.乙\n$header\n1.A\n二、单选题\n1.题目乙\nA.甲 B.乙\n$header\n1.B")
            assertEquals(listOf(listOf("A"), listOf("B")), result.questions.map { it.answers })
            assertTrue(result.questions.none { it.needsReview })
        }
    }

    @Test fun trailingTableCanSelectQuestionTypeWithRepeatedNumbers() {
        val result = parse("一、单选题\n1.题目甲\nA.甲 B.乙\n二、多选题\n1.题目乙\nA.甲 B.乙 C.丙\n参考答案\n一、单选题\n1.B\n二、多选题\n1.AC")
        assertEquals(listOf(listOf("B"), listOf("A", "C")), result.questions.map { it.answers })
        assertTrue(result.questions.none { it.needsReview })
    }

    @Test fun trailingTablesCanSelectParentChapters() {
        val result = parse("第一章 绪论\n一、单选题\n1.题目甲\nA.甲 B.乙\n第二章 基础\n一、单选题\n1.题目乙\nA.甲 B.乙\n参考答案\n第一章\n单选题\n1.A\n第二章\n单选题\n1.B")
        assertEquals(2, result.questions.size)
        assertEquals(listOf(listOf("A"), listOf("B")), result.questions.map { it.answers })
        assertTrue(result.questions.none { it.needsReview })
    }

    @Test fun aGenericFinalTableDoesNotGuessChapterFromProximity() {
        val result = parse("第一章 绪论\n$first\n第二章 基础\n$first\n参考答案\n1.A")
        assertTrue(result.questions.all { it.answers.isEmpty() && it.reviewReasons.contains("ambiguous_answer_table") })
    }

    @Test fun newTaggedQuestionAfterTableIsNotConsumedAsAnAnswer() {
        val result = parse("$first\n参考答案\n1.A\n2.[判断题]题目乙\n正确答案：错")
        assertEquals(2, result.questions.size)
        assertEquals(listOf(listOf("A"), listOf("×")), result.questions.map { it.answers })
        assertTrue(result.questions.none { it.needsReview })
    }

    @Test fun malformedRowIsRejectedAtomicallyInsteadOfApplyingPartialAnswers() {
        val result = parse("$first\n$second\n参考答案\n1.A 2.B 未知说明")
        assertTrue(result.questions.all { it.answers.isEmpty() })
        assertTrue(result.anomalies.any { it.type == "unparsed_answer_table" })
        assertNull(AnswerTableParser.parse("(1.A"))
        assertNull(AnswerTableParser.parse("1.A 2."))
    }

    @Test fun duplicateEntriesAndZeroPaddedDuplicateNumbersRemainAmbiguous() {
        val result = parse("$first\n参考答案\n1.A 01.B")
        assertTrue(result.questions.single().answers.isEmpty())
        assertTrue(result.questions.single().reviewReasons.contains("ambiguous_answer_table"))
    }

    @Test fun inlineAnswerConflictAndUnknownOptionAreNotOverwritten() {
        val q = parse("$first\n答案：A\n参考答案\n1.B").questions.single()
        assertEquals(listOf("A"), q.answers)
        assertTrue(q.reviewReasons.contains("conflicting_answers"))
        val invalid = parse("$first\n参考答案\n1.Z").questions.single()
        assertTrue(invalid.answers.isEmpty())
        assertTrue(invalid.reviewReasons.contains("answer_not_in_options"))
    }

    @Test fun unusedAnswerEntriesRetainSourceLineDiagnostics() {
        val result = parse("$first\n参考答案\n2.A")
        assertTrue(result.anomalies.any { it.type == "unparsed_answer_table" && it.line == 4 && it.page == 0 })
    }

    @Test fun zeroPaddedAnswersMatchTheNumericSourceNumber() {
        assertEquals(listOf("A"), parse("$first\n参考答案\n001.A").questions.single().answers)
    }

    @Test fun mixedNumberingDoesNotGenerateFalseMissingNumberDiagnostics() {
        val result = parse("1.[单选题]甲\nA.甲 B.乙\n答案：A\n2.[判断题]乙\n正确答案：对\n3.[单选题]丙\nA.甲 B.乙\n答案：B")
        assertFalse(result.anomalies.any { it.type == "missing_number" || it.type == "duplicate_number" })
    }

    @Test fun sectionNumberResetsAreDistinctButTrueDuplicatesAreReported() {
        val result = parse("一、单选题\n$first\n答案：A\n二、单选题\n$first\n答案：A")
        assertFalse(result.anomalies.any { it.type == "duplicate_number" })
        assertTrue(parse("$first\n答案：A\n$first\n答案：B").anomalies.any { it.type == "duplicate_number" })
    }

    @Test fun fullWidthStructuralCharactersDoNotChangeTheQuestionContent() {
        val result = parse("１．[单选题]温度为１．５℃，代号ＡＩ。\nＡ．甲\nＢ．乙\n正确答案：Ａ")
        val q = result.questions.single()
        assertEquals("1", q.originalNumber)
        assertEquals("温度为１．５℃，代号ＡＩ。", q.stem)
        assertEquals(listOf("A", "B"), q.options.map { it.key })
        assertEquals(listOf("A"), q.answers)
        assertFalse(q.needsReview)
    }

    @Test fun fullWidthBracketAnswersAndLowercaseOptionsAreRecognized() {
        val q = parse("１．[多选题]题目（Ａ、Ｂ）\na.甲 b.乙 c.丙").questions.single()
        assertEquals(listOf("A", "B"), q.answers)
        assertFalse(q.needsReview)
    }

    @Test fun chapterMentionsInsideAnAnswerAreNotAlwaysNewSections() {
        val q = parse("1.[简答题]介绍各部分内容。\n答案：\n第一章介绍基础概念。\n第二章说明应用。").questions.single()
        assertTrue(q.referenceAnswer.contains("第二章"))
    }

    @Test fun tableTokensDoNotTurnRangesOrProseIntoAnswers() {
        for (row in listOf("1-5 ABCDE", "1.选A", "答案是 1.A", "1.A 2.B 3.未知")) assertNull(row, AnswerTableParser.parse(row))
        assertEquals("AC", AnswerTableParser.parse("1.A、C").single().answer)
    }

    @Test fun explicitAnswerTableAfterFillDoesNotBecomePartOfBlankAnswer() {
        val result = parse("$first\n2.[填空题]一周有()天\n正确答案：\n(1)7\n答案表\n1.A")
        assertEquals(2, result.questions.size)
        assertEquals(listOf(listOf("A"), listOf("7")), result.questions.map { it.answers })
        assertTrue(result.questions.none { it.needsReview })
    }

    @Test fun fullWidthDecimalContinuationIsNotAQuestionBoundary() {
        val result = parse("$first\n答案：A\n３．１４是圆周率的近似值。")
        assertEquals(1, result.questions.size)
    }

    @Test fun localTableFollowedByChapterAndSubheadingKeepsChapterIdentity() {
        val result = parse("第一章 绪论\n单选题\n$first\n参考答案\n1.A\n第二章 基础\n单选题\n$first\n参考答案\n第二章\n单选题\n1.B")
        assertEquals(listOf(listOf("A"), listOf("B")), result.questions.map { it.answers })
        assertTrue(result.questions.none { it.needsReview })
    }
}
