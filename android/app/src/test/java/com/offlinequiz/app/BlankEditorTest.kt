package com.offlinequiz.app

import com.offlinequiz.app.model.BlankEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlankEditorTest {

    @Test
    fun addBlankKeepsStemAndAnswerListInSync() {
        val result = BlankEditor.addBlank(
            stem = "请填写____。",
            answers = listOf("甲"),
            acceptableAnswers = listOf(listOf("甲", "A"))
        )
        assertEquals(2, BlankEditor.placeholderCount(result.stem))
        assertEquals(listOf("甲", ""), result.answers)
        assertEquals(listOf(listOf("甲", "A"), emptyList<String>()), result.acceptableAnswers)
    }

    @Test
    fun removeBlankRemovesOnlyTheSelectedPlaceholderAndAnswer() {
        val result = BlankEditor.removeBlank(
            stem = "前____中____后",
            answers = listOf("甲", "乙", "丙"),
            acceptableAnswers = listOf(listOf("甲"), listOf("乙"), listOf("丙")),
            index = 1
        )
        assertEquals("前____中后", result.stem)
        assertEquals(listOf("甲", "丙"), result.answers)
        assertEquals(listOf(listOf("甲"), listOf("丙")), result.acceptableAnswers)
        assertEquals(1, BlankEditor.placeholderCount(result.stem))
    }

    @Test
    fun mergeWithEnumerationCommaRemovesThatCommaAndClearsPartialAcceptedAnswers() {
        val result = BlankEditor.mergeBlank(
            stem = "前____、____后",
            answers = listOf("甲", "乙"),
            acceptableAnswers = listOf(listOf("A"), listOf("B")),
            index = 1
        )
        assertFalse(result.rejected)
        assertEquals("前____后", result.stem)
        assertEquals(listOf("甲、乙"), result.answers)
        assertEquals(listOf(emptyList<String>()), result.acceptableAnswers)
        assertEquals(1, BlankEditor.placeholderCount(result.stem))
        assertFalse(result.answers.first().contains("、A"))
        assertFalse(result.answers.first().contains("、B"))
        assertFalse(result.acceptableAnswers.flatten().contains("A"))
        assertFalse(result.acceptableAnswers.flatten().contains("B"))
        assertTrue(result.notice!!.contains("可接受答案已清空"))
    }

    @Test
    fun mergeThreeBlanksKeepsOtherAnswersAndAcceptedAnswersInOrder() {
        val result = BlankEditor.mergeBlank(
            stem = "前____、____；____后",
            answers = listOf("甲", "乙", "丙"),
            acceptableAnswers = listOf(listOf("甲-A"), listOf("乙-B"), listOf("丙-C")),
            index = 1
        )
        assertFalse(result.rejected)
        // Two placeholders and the "、" between them become one placeholder; "；" stays for blank 3.
        assertEquals("前____；____后", result.stem)
        assertEquals(listOf("甲、乙", "丙"), result.answers)
        assertEquals(listOf(emptyList<String>(), listOf("丙-C")), result.acceptableAnswers)
        assertEquals(2, BlankEditor.placeholderCount(result.stem))

        val mergeLast = BlankEditor.mergeBlank(
            stem = "前____、____；____后",
            answers = listOf("甲", "乙", "丙"),
            acceptableAnswers = listOf(listOf("甲-A"), listOf("乙-B"), listOf("丙-C")),
            index = 2
        )
        assertFalse(mergeLast.rejected)
        assertEquals("前____、____后", mergeLast.stem)
        assertEquals(listOf("甲", "乙；丙"), mergeLast.answers)
        assertEquals(listOf(listOf("甲-A"), emptyList<String>()), mergeLast.acceptableAnswers)
        assertEquals(2, BlankEditor.placeholderCount(mergeLast.stem))
    }

    @Test
    fun everyWhitelistedPunctuationSeparatorMergesWithoutDuplicatingItInTheStem() {
        val separators = listOf("、", "，", ",", "；", ";", "：", ":", "。", ".", "！", "!", "？", "?")
        for (separator in separators) {
            val result = BlankEditor.mergeBlank(
                stem = "前____${separator}____后",
                answers = listOf("甲", "乙"),
                acceptableAnswers = listOf(emptyList(), emptyList()),
                index = 1
            )
            assertFalse("separator=$separator", result.rejected)
            assertEquals("separator=$separator", "前____后", result.stem)
            assertEquals("separator=$separator", listOf("甲${separator}乙"), result.answers)
            assertEquals("separator=$separator", 1, BlankEditor.placeholderCount(result.stem))
        }
    }

    @Test
    fun mergeWhitespaceOnlyGapUsesASpaceAndNeverInventsEnumerationComma() {
        val result = BlankEditor.mergeBlank(
            stem = "前____ ____后",
            answers = listOf("甲", "乙"),
            acceptableAnswers = listOf(emptyList(), emptyList()),
            index = 1
        )
        assertFalse(result.rejected)
        assertEquals("前____后", result.stem)
        assertEquals(listOf("甲 乙"), result.answers)
        assertFalse(result.answers.single().contains("、"))
        assertEquals(listOf(emptyList<String>()), result.acceptableAnswers)

        val newlineGap = BlankEditor.mergeBlank(
            stem = "前____\n____后",
            answers = listOf("甲", "乙"),
            acceptableAnswers = listOf(emptyList(), emptyList()),
            index = 1
        )
        assertFalse(newlineGap.rejected)
        assertEquals("前____后", newlineGap.stem)
        assertEquals(listOf("甲 乙"), newlineGap.answers)
    }

    @Test
    fun mergeRejectsTextBetweenAndLeavesStemAnswersAndAcceptedAnswersUnchanged() {
        val stem = "____和____"
        val answers = listOf("甲", "乙")
        val accepted = listOf(listOf("A"), listOf("B"))
        val result = BlankEditor.mergeBlank(stem, answers, accepted, 1)

        assertTrue(result.rejected)
        assertTrue(result.rejectedReason!!.contains("正文"))
        assertEquals(stem, result.stem)
        assertEquals(answers, result.answers)
        assertEquals(accepted, result.acceptableAnswers)

        val afterText = BlankEditor.mergeBlank("____后又____", listOf("甲", "乙"), listOf(emptyList(), emptyList()), 1)
        assertTrue(afterText.rejected)
        assertEquals("____后又____", afterText.stem)
        assertEquals(listOf("甲", "乙"), afterText.answers)
    }

    @Test
    fun mergeRejectsPlaceholderAnswerMismatchAndLeavesEverythingUnchanged() {
        val stem = "____、____"
        val answers = listOf("甲")
        val accepted = listOf(listOf("A"))
        val result = BlankEditor.mergeBlank(stem, answers, accepted, 1)

        assertTrue(result.rejected)
        assertTrue(result.rejectedReason!!.contains("不一致"))
        assertEquals(stem, result.stem)
        assertEquals(answers, result.answers)
        assertEquals(accepted, result.acceptableAnswers)
    }

    @Test
    fun mergeRejectsEmptyReferenceAnswerWithoutDroppingTheOtherAnswer() {
        val stem = "____、____"
        val answers = listOf("甲", " ")
        val result = BlankEditor.mergeBlank(stem, answers, listOf(emptyList(), emptyList()), 1)

        assertTrue(result.rejected)
        assertTrue(result.rejectedReason!!.contains("非空"))
        assertEquals(stem, result.stem)
        assertEquals(answers, result.answers)
    }

    @Test
    fun placeholderCountIgnoresSingleUnderscore() {
        assertEquals(2, BlankEditor.placeholderCount("a_b____c__d"))
        assertEquals(0, BlankEditor.placeholderCount("a_b_c"))
    }
}
