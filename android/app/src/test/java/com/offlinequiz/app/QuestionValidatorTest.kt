package com.offlinequiz.app

import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.model.QuestionValidator
import com.offlinequiz.app.model.QuizOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionValidatorTest {

    private fun options(vararg pairs: Pair<String, String>): List<QuizOption> =
        pairs.mapIndexed { index, pair -> QuizOption(pair.first, pair.second, markerIndex = index) }

    @Test
    fun emptyStemIsRejected() {
        val errors = QuestionValidator.validate(testQuestion(stem = "   "))
        assertTrue(errors.any { it.contains("题干不能为空") })
    }

    @Test
    fun duplicateChoiceKeysAreRejectedCaseInsensitively() {
        val question = testQuestion(
            type = QuestionTypes.SINGLE_CHOICE,
            stem = "题干",
            options = options("A" to "甲", "a" to "乙"),
            answers = listOf("A")
        )
        val errors = QuestionValidator.validate(question)
        assertTrue(errors.any { it.contains("选项标记不能重复") })
    }

    @Test
    fun answerOutsideOptionsIsRejected() {
        val question = testQuestion(
            type = QuestionTypes.SINGLE_CHOICE,
            stem = "题干",
            options = options("A" to "甲", "B" to "乙", "C" to "丙"),
            answers = listOf("D")
        )
        val errors = QuestionValidator.validate(question)
        assertTrue(errors.any { it.contains("正确项不在现有选项中") })
    }

    @Test
    fun singleChoiceWithTwoAnswersIsRejected() {
        val question = testQuestion(
            type = QuestionTypes.SINGLE_CHOICE,
            stem = "题干",
            options = options("A" to "甲", "B" to "乙"),
            answers = listOf("A", "B")
        )
        val errors = QuestionValidator.validate(question)
        assertTrue(errors.any { it.contains("单选题必须有且只有一个正确项") })
    }

    @Test
    fun multipleChoiceNeedsTwoDistinctAnswers() {
        val question = testQuestion(
            type = QuestionTypes.MULTIPLE_CHOICE,
            stem = "题干",
            options = options("A" to "甲", "B" to "乙", "C" to "丙"),
            answers = listOf("A")
        )
        val errors = QuestionValidator.validate(question)
        assertTrue(errors.any { it.contains("多选题至少需要两个正确项") })
    }

    @Test
    fun fillBlankCountMustMatchAnswerGroups() {
        val question = testQuestion(
            type = QuestionTypes.FILL_BLANK,
            stem = "两个空____和____",
            answers = listOf("甲"),
            acceptableAnswers = listOf(emptyList())
        )
        val errors = QuestionValidator.validate(question)
        assertTrue(errors.any { it.contains("题干有 2 个空位") && it.contains("1 组") })
    }

    @Test
    fun validChoiceAndFillPass() {
        val choice = testQuestion(
            type = QuestionTypes.MULTIPLE_CHOICE,
            stem = "题干",
            options = options("A" to "甲", "B" to "乙", "C" to "丙"),
            answers = listOf("A", "C")
        )
        assertEquals(emptyList<String>(), QuestionValidator.validate(choice))

        val fill = testQuestion(
            type = QuestionTypes.FILL_BLANK,
            stem = "两个空____和____",
            answers = listOf("甲", "乙"),
            acceptableAnswers = listOf(listOf("甲"), listOf("乙", "B"))
        )
        assertEquals(emptyList<String>(), QuestionValidator.validate(fill))
    }

    @Test
    fun shortAnswerRequiresReferenceAnswer() {
        val question = testQuestion(
            type = QuestionTypes.SHORT_ANSWER,
            stem = "简答题干",
            answers = emptyList(),
            referenceAnswer = " "
        )
        assertTrue(QuestionValidator.validate(question).any { it.contains("参考答案不能为空") })
    }
}
