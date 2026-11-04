package com.offlinequiz.app

import com.offlinequiz.app.model.*
import org.junit.Assert.*
import org.junit.Test

class QuestionReviewTest {
    @Test fun normalUnconfirmedImportIsPracticableAndNotAnError() {
        val question = testQuestion()
        assertFalse(question.confirmed)
        assertFalse(QuestionReview.needsAttention(question))
        assertEquals("", QuestionReview.label(question))
        assertEquals(EligibilityStatus.ELIGIBLE, PracticeEligibility.classify(question))
    }

    @Test fun exampleWithSevenUnreviewedQuestionsOnlyNeedsOneCorrection() {
        val questions = (0..5).map { testQuestion(id = "q$it", position = it) } +
            testQuestion(id = "q6", position = 6, originalReviewReasons = listOf("ambiguous"))
        val summary = QuestionReview.summary(testLibrary(), questions)
        assertEquals(7, summary.questionCount)
        assertEquals(1, summary.pendingCount)
        assertEquals(7, summary.unreviewedCount)
        assertEquals(0, summary.confirmedCount)
        assertEquals(listOf("q6"), questions.filter(QuestionFilter.ATTENTION::includes).map { it.id })
        assertEquals(6, PracticeEligibility.evaluate(questions).eligible.size)
        assertTrue(questions.none { it.confirmed })
    }

    @Test fun explicitConfirmationResolvesImportDoubtButKeepsEvidence() {
        val question = testQuestion(originalReviewReasons = listOf("ambiguous"))
        assertTrue(QuestionReview.needsAttention(question))
        val confirmed = question.copy(confirmed = true, confirmedAt = 123)
        assertFalse(QuestionReview.needsAttention(confirmed))
        assertEquals("", QuestionReview.label(confirmed))
        assertEquals(question.originalReviewReasons, confirmed.originalReviewReasons)
        assertTrue(confirmed.confirmed)
    }

    @Test fun missingAnswersAndBrokenStructureStillNeedAttentionEvenIfConfirmed() {
        val missing = testQuestion(answers = listOf(""), confirmed = true)
        assertTrue(QuestionReview.needsAttention(missing))
        assertEquals(EligibilityStatus.STRUCTURE_INVALID, PracticeEligibility.classify(missing))
        val mismatched = testQuestion(stem = "题目____和____", answers = listOf("甲"))
        assertTrue(QuestionFilter.ATTENTION.includes(mismatched))
        val badJudgment = testQuestion(type = QuestionTypes.TRUE_FALSE, stem = "判断", answers = listOf("也许"))
        assertTrue(QuestionReview.needsAttention(badJudgment))
    }

    @Test fun unsupportedTypeIsNotAllowedByConfirmation() {
        val question = testQuestion(type = "image", confirmed = true)
        assertTrue(QuestionReview.needsAttention(question))
        assertEquals(EligibilityStatus.UNSUPPORTED_TYPE, PracticeEligibility.classify(question))
    }

    @Test fun filtersSeparateReviewFactFromAttentionAndHandleNoPendingItems() {
        val valid = testQuestion()
        assertFalse(valid.confirmed)
        assertFalse(QuestionFilter.ATTENTION.includes(valid))
        assertTrue(QuestionFilter.ALL.includes(valid))
        assertEquals(QuestionFilter.ALL, QuestionFilter.restore("unknown"))
        assertTrue(listOf(valid).filter(QuestionFilter.ATTENTION::includes).isEmpty())
        val summary = QuestionReview.summary(testLibrary(), listOf(valid))
        assertEquals(0, summary.pendingCount)
        assertEquals(0, summary.confirmedCount)
        assertEquals(1, summary.unreviewedCount)
    }
}
