package com.offlinequiz.app

import com.offlinequiz.app.data.QuestionMapper
import com.offlinequiz.app.data.toEntity
import com.offlinequiz.app.data.toModel
import com.offlinequiz.app.model.*
import org.junit.Assert.*
import org.junit.Test

class FillAnswerVariantsTest {
    private fun question(answer: String, accepted: List<String> = emptyList()) = testQuestion(
        type = QuestionTypes.FILL_BLANK, stem = "答案是____。", answers = listOf(answer),
        acceptableAnswers = listOf(accepted))

    @Test fun importsSplitPrimaryAndAcceptedFormsWithoutReview() {
        val parsed = TextImportParser.parse("1.[填空题]答案是()。\n正确答案：\n(1)大众的\\大众")
        assertFalse(parsed.result.questions.single().needsReview)
        val q = QuestionMapper.fromParseResult(parsed.result, "lib") { "q" }.single()
        assertEquals(listOf("大众的"), q.answers)
        assertEquals(listOf(listOf("大众")), q.acceptableAnswers)
        assertEquals(listOf("大众的\\大众"), q.originalAnswers)
        assertEquals(EligibilityStatus.ELIGIBLE, PracticeEligibility.classify(q))
    }

    @Test fun quotedAlternativeIsAcceptedAsWritten() {
        val q = question("五位一体\\\"五位一体\"").withFillAnswerVariants()
        assertEquals(listOf("五位一体"), q.answers)
        assertEquals(listOf(listOf("\"五位一体\"")), q.acceptableAnswers)
        for (answer in listOf("五位一体", "\"五位一体\"")) {
            assertTrue(PracticeJudge.judge(q.toPracticeSnapshot(), PracticeUserAnswer(blankAnswers = listOf(answer)))!!)
        }
        assertFalse(PracticeJudge.judge(q.toPracticeSnapshot(), PracticeUserAnswer(blankAnswers = listOf("四个全面")))!!)
    }

    @Test fun oldImportedRowsNormalizeWithoutDestroyingOriginalEvidence() {
        val old = question("大众的\\\\大众").copy(originalAnswers = listOf("大众的\\\\大众"),
            originalNeedsReview = true, originalReviewReasons = listOf("answer_variants_need_review"))
        val loaded = old.toEntity().toModel()
        assertEquals(listOf("大众的"), loaded.answers)
        assertEquals(listOf(listOf("大众")), loaded.acceptableAnswers)
        assertEquals(old.originalAnswers, loaded.originalAnswers)
        assertEquals(old.originalReviewReasons, loaded.originalReviewReasons)
        assertEquals(EligibilityStatus.ELIGIBLE, PracticeEligibility.classify(loaded))
        assertEquals(loaded, loaded.toEntity().toModel())
    }

    @Test fun oldPracticeSnapshotAcceptsEitherForm() {
        val oldSnapshot = question("大众的\\大众").toPracticeSnapshot().copy(answers = listOf("大众的\\大众"), acceptableAnswers = listOf(emptyList()))
        for (answer in listOf("大众的", "大众")) {
            assertTrue(PracticeJudge.judge(oldSnapshot, PracticeUserAnswer(blankAnswers = listOf(answer)))!!)
        }
        assertFalse(PracticeJudge.judge(oldSnapshot, PracticeUserAnswer(blankAnswers = listOf("大众的\\大众")))!!)
    }

    @Test fun eachBlankAcceptsItsOwnFormsWithoutCreatingMoreBlanks() {
        val q = question("甲\\乙").copy(stem = "____与____", answers = listOf("甲\\乙", "丙\\丁"), acceptableAnswers = listOf(emptyList(), emptyList())).withFillAnswerVariants()
        assertEquals(2, q.answers.size)
        assertTrue(PracticeJudge.judge(q.toPracticeSnapshot(), PracticeUserAnswer(blankAnswers = listOf("乙", "丁")))!!)
        assertFalse(PracticeJudge.judge(q.toPracticeSnapshot(), PracticeUserAnswer(blankAnswers = listOf("丁", "乙")))!!)
    }

    @Test fun explicitAcceptedFormsAreMergedAndDeduplicated() {
        val q = question("甲\\乙\\甲", listOf("乙", "丙\\丁")).withFillAnswerVariants()
        assertEquals(listOf("甲"), q.answers)
        assertEquals(listOf(listOf("乙", "丙", "丁")), q.acceptableAnswers)
        assertEquals(q, q.withFillAnswerVariants())
    }

    @Test fun otherImportWarningsAreNotCleared() {
        val q = question("甲\\乙").copy(originalAnswers = listOf("甲\\乙"), originalNeedsReview = true,
            originalReviewReasons = listOf("answer_variants_need_review", "text_blank_count_mismatch"))
        assertEquals(EligibilityStatus.NEEDS_REVIEW, PracticeEligibility.classify(q.withFillAnswerVariants()))
    }

    @Test fun pathsAndIncompleteSeparatorsRemainLiteral() {
        for (value in listOf("C:\\temp\\file", "\\alpha", "甲\\", "\\乙")) assertEquals(listOf(value), FillAnswerVariants.forms(value))
        val parsed = TextImportParser.parse("1.[填空题]()\n答案：甲\\")
        assertTrue(parsed.result.questions.single().reviewReasons.contains("invalid_answer_variants"))
    }

    @Test fun commasSlashesAndMeaningfulSymbolsAreNotAlternativeSeparators() {
        for (value in listOf("甲、乙", "1/2", "1.5", "-1", "不能")) assertEquals(listOf(value), FillAnswerVariants.forms(value))
    }

    @Test fun emptyPrimaryIsNotReplacedByAnAcceptedForm() {
        val q = question("", listOf("甲")).withFillAnswerVariants()
        assertEquals(listOf(""), q.answers)
        assertTrue(QuestionValidator.validate(q).isNotEmpty())
    }
}
