package com.offlinequiz.app

import com.offlinequiz.app.model.PracticeEligibility
import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.model.QuizOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeEligibilityTest {

    private fun options(vararg pairs: Pair<String, String>): List<QuizOption> =
        pairs.mapIndexed { index, pair -> QuizOption(pair.first, pair.second, markerIndex = index) }

    @Test
    fun normalValidQuestionDoesNotNeedExplicitConfirmation() {
        val normal = testQuestion(
            id = "normal",
            type = QuestionTypes.FILL_BLANK,
            stem = "____",
            answers = listOf("甲"),
            confirmed = false,
            originalReviewReasons = emptyList()
        )
        val report = PracticeEligibility.evaluate(listOf(normal))
        assertEquals(listOf("normal"), report.eligible.map { it.id })
        assertEquals(0, report.reviewExcluded)
        assertEquals(0, report.structureExcluded)
        assertEquals(0, report.unsupportedTypeExcluded)
    }

    @Test
    fun unconfirmedOriginalAnomalyIsExcludedForReview() {
        val review = testQuestion(
            id = "review",
            type = QuestionTypes.SINGLE_CHOICE,
            stem = "题干",
            options = options("A" to "甲", "B" to "乙"),
            answers = listOf("A"),
            originalReviewReasons = listOf("no_answer_in_parentheses"),
            confirmed = false
        )
        val report = PracticeEligibility.evaluate(listOf(review))
        assertTrue(report.eligible.isEmpty())
        assertEquals(1, report.reviewExcluded)
        assertEquals(1, report.excludedTotal)
    }

    @Test
    fun confirmedOriginalAnomalyWithValidContentCanBePractised() {
        val review = testQuestion(
            id = "review-confirmed",
            type = QuestionTypes.SINGLE_CHOICE,
            stem = "题干",
            options = options("A" to "甲", "B" to "乙"),
            answers = listOf("A"),
            originalReviewReasons = listOf("no_answer_in_parentheses"),
            confirmed = true
        )
        val report = PracticeEligibility.evaluate(listOf(review))
        assertEquals(listOf("review-confirmed"), report.eligible.map { it.id })
        assertEquals(0, report.reviewExcluded)
    }

    @Test
    fun structuralErrorsAndUnknownTypesAreExcludedSeparately() {
        val duplicateKeys = testQuestion(
            id = "duplicate",
            type = QuestionTypes.SINGLE_CHOICE,
            stem = "题干",
            options = options("A" to "甲", "A" to "乙"),
            answers = listOf("A")
        )
        val unknown = testQuestion(
            id = "unknown",
            type = QuestionTypes.UNKNOWN,
            stem = "题干",
            answers = emptyList()
        )
        val report = PracticeEligibility.evaluate(listOf(duplicateKeys, unknown))
        assertTrue(report.eligible.isEmpty())
        assertEquals(1, report.structureExcluded)
        assertEquals(1, report.unsupportedTypeExcluded)
    }

    @Test
    fun trueFalseWithUnknownReferenceIsNotEligible() {
        val invalidTrueFalse = testQuestion(
            id = "invalid-tf",
            type = QuestionTypes.TRUE_FALSE,
            stem = "题干",
            answers = listOf("?")
        )
        val report = PracticeEligibility.evaluate(listOf(invalidTrueFalse))
        assertTrue(report.eligible.isEmpty())
        assertEquals(1, report.structureExcluded)
        assertFalse(PracticeEligibility.isStructurallyPracticable(invalidTrueFalse))
    }

    @Test
    fun mixedLibraryReportsEligibleAndExcludedCounts() {
        val valid = testQuestion(id = "valid", stem = "____", answers = listOf("甲"))
        val review = testQuestion(
            id = "review",
            type = QuestionTypes.SHORT_ANSWER,
            stem = "简答",
            answers = emptyList(),
            referenceAnswer = "答案",
            originalReviewReasons = listOf("empty_reference_answer"),
            confirmed = false
        )
        val invalid = testQuestion(
            id = "invalid",
            type = QuestionTypes.MULTIPLE_CHOICE,
            stem = "题干",
            options = options("A" to "甲", "B" to "乙"),
            answers = listOf("A")
        )
        val report = PracticeEligibility.evaluate(listOf(valid, review, invalid))
        assertEquals(listOf("valid"), report.eligible.map { it.id })
        assertEquals(1, report.reviewExcluded)
        assertEquals(1, report.structureExcluded)
        assertEquals(2, report.excludedTotal)
    }
}
