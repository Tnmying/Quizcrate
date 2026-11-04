package com.offlinequiz.app

import com.offlinequiz.app.model.PracticeDraft
import com.offlinequiz.app.model.PracticeJudge
import com.offlinequiz.app.model.PracticeUserAnswer
import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.model.QuizOption
import com.offlinequiz.app.model.toPracticeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeJudgeTest {

    private fun options(vararg pairs: Pair<String, String>): List<QuizOption> =
        pairs.mapIndexed { index, pair -> QuizOption(pair.first, pair.second, markerIndex = index) }

    private fun snapshot(question: com.offlinequiz.app.model.StoredQuestion) = question.toPracticeSnapshot()

    @Test
    fun singleChoiceRequiresExactlyOneMatchingKey() {
        val question = snapshot(
            testQuestion(
                type = QuestionTypes.SINGLE_CHOICE,
                stem = "题干",
                options = options("A" to "甲", "B" to "乙"),
                answers = listOf("A")
            )
        )
        assertTrue(PracticeJudge.judge(question, PracticeUserAnswer(selectedKeys = listOf("A")))!!)
        assertFalse(PracticeJudge.judge(question, PracticeUserAnswer(selectedKeys = listOf("B")))!!)
        assertFalse(PracticeJudge.judge(question, PracticeUserAnswer(selectedKeys = listOf("A", "B")))!!)
        assertFalse(PracticeJudge.judge(question, PracticeUserAnswer())!!)
    }

    @Test
    fun multipleChoiceRequiresExactSetWithNoPartialCredit() {
        val question = snapshot(
            testQuestion(
                type = QuestionTypes.MULTIPLE_CHOICE,
                stem = "题干",
                options = options("A" to "甲", "B" to "乙", "C" to "丙"),
                answers = listOf("A", "B")
            )
        )
        assertTrue(PracticeJudge.judge(question, PracticeUserAnswer(selectedKeys = listOf("B", "A")))!!)
        assertFalse(PracticeJudge.judge(question, PracticeUserAnswer(selectedKeys = listOf("A")))!!)
        assertFalse(PracticeJudge.judge(question, PracticeUserAnswer(selectedKeys = listOf("A", "B", "C")))!!)
        assertFalse(PracticeJudge.judge(question, PracticeUserAnswer(selectedKeys = listOf("A", "A")))!!)
    }

    @Test
    fun trueFalseAcceptsKnownVariantsButRejectsUnknownReference() {
        val question = snapshot(
            testQuestion(
                type = QuestionTypes.TRUE_FALSE,
                stem = "题干",
                answers = listOf("√")
            )
        )
        assertTrue(PracticeJudge.judge(question, PracticeUserAnswer(selectedKeys = listOf("√")))!!)
        assertTrue(PracticeJudge.judge(question, PracticeUserAnswer(selectedKeys = listOf("对")))!!)
        assertFalse(PracticeJudge.judge(question, PracticeUserAnswer(selectedKeys = listOf("×")))!!)
        assertFalse(PracticeJudge.judge(question, PracticeUserAnswer())!!)

        val invalidReference = snapshot(
            testQuestion(type = QuestionTypes.TRUE_FALSE, stem = "题干", answers = listOf("?"))
        )
        assertNull(PracticeJudge.trueFalseValue("?"))
        assertFalse(PracticeJudge.isValidTrueFalseReference(invalidReference))
    }

    @Test
    fun fillBlankUsesConservativeLayoutNormalizationAndOrderedBlanks() {
        val question = snapshot(
            testQuestion(
                type = QuestionTypes.FILL_BLANK,
                stem = "____、____、____",
                answers = listOf("1.5", "不能", "A B")
            )
        )
        assertTrue(
            PracticeJudge.judge(
                question,
                PracticeUserAnswer(blankAnswers = listOf("1.5", "不能", "A B"))
            )!!
        )
        assertTrue(
            PracticeJudge.judge(
                question,
                PracticeUserAnswer(blankAnswers = listOf("１．５", "不能", "A B"))
            )!!
        )
        // Numeric, negation and internal-space differences must not be swallowed.
        assertFalse(
            PracticeJudge.judge(question, PracticeUserAnswer(blankAnswers = listOf("15", "不能", "A B")))!!
        )
        assertFalse(
            PracticeJudge.judge(question, PracticeUserAnswer(blankAnswers = listOf("1.5", "能", "A B")))!!
        )
        assertFalse(
            PracticeJudge.judge(question, PracticeUserAnswer(blankAnswers = listOf("1.5", "不能", "AB")))!!
        )
        assertFalse(
            PracticeJudge.judge(question, PracticeUserAnswer(blankAnswers = listOf("1.5", "不能")))!!
        )
        assertFalse(
            PracticeJudge.judge(question, PracticeUserAnswer(blankAnswers = listOf("不能", "1.5", "A B")))!!
        )
        assertEquals("A B", PracticeJudge.normalizeFillText("  Ａ　Ｂ  "))
        assertEquals("A B", PracticeJudge.normalizeFillText("A   B"))
        assertEquals("15", PracticeJudge.normalizeFillText("１５"))
        // Case is preserved: the normalizer is layout-only, not a case-insensitive matcher.
        assertEquals("CPU", PracticeJudge.normalizeFillText("ＣＰＵ"))
        assertFalse(PracticeJudge.normalizeFillText("CPU") == PracticeJudge.normalizeFillText("cpu"))
    }

    @Test
    fun fillBlankAcceptsPerBlankAcceptableAnswersAndMergedSingleBlank() {
        val question = snapshot(
            testQuestion(
                type = QuestionTypes.FILL_BLANK,
                stem = "____、____",
                answers = listOf("甲", "乙"),
                acceptableAnswers = listOf(listOf("甲-A"), listOf("乙-B", "乙C"))
            )
        )
        assertTrue(
            PracticeJudge.judge(
                question,
                PracticeUserAnswer(blankAnswers = listOf("甲-A", "乙C"))
            )!!
        )
        assertFalse(
            PracticeJudge.judge(
                question,
                PracticeUserAnswer(blankAnswers = listOf("甲A", "乙C"))
            )!!
        )

        val merged = snapshot(
            testQuestion(
                type = QuestionTypes.FILL_BLANK,
                stem = "前____后",
                answers = listOf("甲、乙")
            )
        )
        assertTrue(PracticeJudge.judge(merged, PracticeUserAnswer(blankAnswers = listOf("甲、乙")))!!)
        assertFalse(PracticeJudge.judge(merged, PracticeUserAnswer(blankAnswers = listOf("甲")))!!)
        assertFalse(PracticeJudge.judge(merged, PracticeUserAnswer(blankAnswers = listOf("乙")))!!)
    }

    @Test
    fun fillBlankFullWidthPunctuationIsWidthNormalizedNotDeleted() {
        val question = snapshot(
            testQuestion(
                type = QuestionTypes.FILL_BLANK,
                stem = "____",
                answers = listOf("甲，乙")
            )
        )
        assertTrue(PracticeJudge.judge(question, PracticeUserAnswer(blankAnswers = listOf("甲,乙")))!!)
        assertFalse(PracticeJudge.judge(question, PracticeUserAnswer(blankAnswers = listOf("甲乙")))!!)
    }

    @Test
    fun shortAnswerHasNoAutomaticJudgement() {
        val question = snapshot(
            testQuestion(
                type = QuestionTypes.SHORT_ANSWER,
                stem = "简答",
                answers = emptyList(),
                referenceAnswer = "参考答案"
            )
        )
        assertNull(PracticeJudge.judge(question, PracticeUserAnswer(text = "我的作答")))
        assertTrue(PracticeJudge.hasAnswer(question, PracticeDraft(text = "")))
    }

    @Test
    fun hasAnswerDoesNotTreatEmptyChoiceOrBlankInputAsAnswered() {
        val single = testQuestion(
            type = QuestionTypes.SINGLE_CHOICE,
            stem = "题干",
            options = options("A" to "甲", "B" to "乙"),
            answers = listOf("A")
        ).toPracticeSnapshot()
        assertFalse(PracticeJudge.hasAnswer(single, PracticeDraft()))
        assertTrue(PracticeJudge.hasAnswer(single, PracticeDraft(selectedKeys = listOf("A"))))

        val fill = testQuestion(
            type = QuestionTypes.FILL_BLANK,
            stem = "____、____",
            answers = listOf("甲", "乙")
        ).toPracticeSnapshot()
        assertFalse(PracticeJudge.hasAnswer(fill, PracticeDraft(blankAnswers = listOf("甲", ""))))
        assertTrue(PracticeJudge.hasAnswer(fill, PracticeDraft(blankAnswers = listOf("甲", "乙"))))
    }
}
