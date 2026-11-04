package com.offlinequiz.app

import com.offlinequiz.app.data.PracticeRepository
import com.offlinequiz.app.model.PracticeAnswerKinds
import com.offlinequiz.app.model.PracticeDraft
import com.offlinequiz.app.model.PracticePhases
import com.offlinequiz.app.model.PracticeResults
import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.model.ShortAnswerMatchUnavailable
import com.offlinequiz.app.model.StoredQuestion
import com.offlinequiz.app.viewmodel.PracticeViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration regression through the real PracticeViewModel: input → reveal → match → self-assess →
 * feedback, plus restore/edited-snapshot/skip/next-question isolation. This proves the UI-facing
 * state actually carries the match (not just that the pure algorithm works).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PracticeViewModelShortAnswerMatchTest {

    private class Fixture {
        val libraryStore = TestLibraryStore()
        val practiceStore = TestPracticeStore()
        val libraryId = "lib"

        init {
            libraryStore.addLibrary(testLibrary(id = libraryId, name = "题库"))
        }

        fun viewModel(): PracticeViewModel {
            val repository = PracticeRepository(
                libraryStore = libraryStore,
                practiceStore = practiceStore,
                clock = { 5_000L },
                idFactory = { "generated" }
            )
            return PracticeViewModel(applicationWithoutAndroidRuntime(), repository)
        }

        fun addSession(session: com.offlinequiz.app.model.PracticeSessionRecord) {
            practiceStore.addSession(session)
        }
    }

    private fun shortQuestion(
        id: String,
        reference: String?,
        position: Int = 0,
        stem: String = "简答题干 $id"
    ): StoredQuestion = practiceQuestion(
        id = id,
        position = position,
        type = QuestionTypes.SHORT_ANSWER,
        stem = stem,
        options = emptyList(),
        answers = emptyList(),
        referenceAnswer = reference
    )

    private fun loadSingleQuestion(
        fixture: Fixture,
        question: StoredQuestion
    ): PracticeViewModel {
        fixture.libraryStore.addQuestion(question)
        fixture.addSession(practiceSession("sess", listOf(question), libraryId = fixture.libraryId))
        val viewModel = fixture.viewModel()
        viewModel.load("sess")
        return viewModel
    }

    @Test
    fun matchIsHiddenUntilRevealAndAppearsAfterwards() = statsViewModelTest {
        val fixture = Fixture()
        val viewModel = loadSingleQuestion(fixture, shortQuestion("q1", "光合作用需要光照和水"))
        advanceUntilIdle()

        assertNull("must not compute or leak before reveal", viewModel.shortAnswerMatch)
        viewModel.updateShortText("光合作用需要光照和水")
        assertNull("typing alone must not reveal the score", viewModel.shortAnswerMatch)

        viewModel.revealReference()
        val match = viewModel.shortAnswerMatch
        assertNotNull(match)
        assertTrue(match!!.available)
        assertEquals(100, match.percent)
        assertEquals("光合作用需要光照和水", viewModel.state?.currentQuestion?.referenceAnswer)
    }

    @Test
    fun emptyInputShowsReasonButStillAllowsSelfAssessment() = statsViewModelTest {
        val fixture = Fixture()
        val viewModel = loadSingleQuestion(fixture, shortQuestion("q1", "参考答案"))
        advanceUntilIdle()

        viewModel.revealReference()
        val match = viewModel.shortAnswerMatch
        assertNotNull(match)
        assertFalse(match!!.available)
        assertEquals(ShortAnswerMatchUnavailable.USER_TEXT_EMPTY, match.unavailableReason)
        assertTrue("the empty state must not block self-assessment", viewModel.canSelfAssess())

        viewModel.selfAssess(true)
        advanceUntilIdle()
        assertEquals(PracticeResults.REMEMBERED, viewModel.state?.feedback?.result)
        assertEquals(1, viewModel.state?.summary?.remembered)
        assertEquals(0, viewModel.state?.summary?.automaticCorrect)
    }

    @Test
    fun missingReferenceShowsReasonAndDoesNotFakeAScore() = statsViewModelTest {
        val fixture = Fixture()
        val viewModel = loadSingleQuestion(fixture, shortQuestion("q1", null))
        advanceUntilIdle()

        viewModel.updateShortText("我的回答")
        viewModel.revealReference()
        val match = viewModel.shortAnswerMatch
        assertNotNull(match)
        assertFalse(match!!.available)
        assertEquals(ShortAnswerMatchUnavailable.REFERENCE_EMPTY, match.unavailableReason)
        assertTrue(viewModel.canSelfAssess())
    }

    @Test
    fun selfAssessmentFeedbackKeepsTheSameMatchAndStaysOutOfAutomaticStats() = statsViewModelTest {
        val fixture = Fixture()
        val reference = "光合作用需要光照和水"
        val viewModel = loadSingleQuestion(fixture, shortQuestion("q1", reference))
        advanceUntilIdle()

        viewModel.updateShortText("光合作用需要光照")
        viewModel.revealReference()
        val before = viewModel.shortAnswerMatch!!
        assertTrue(before.available)
        assertTrue(before.percent in 1..99)

        viewModel.selfAssess(true)
        advanceUntilIdle()

        val state = viewModel.state!!
        assertEquals(PracticePhases.FEEDBACK, state.session.phase)
        assertEquals(PracticeAnswerKinds.SELF_ASSESSMENT, state.feedback?.kind)
        assertEquals(PracticeResults.REMEMBERED, state.feedback?.result)
        assertEquals(0, state.summary.automaticCorrect)
        assertEquals(0, state.summary.automaticIncorrect)
        assertEquals(1, state.summary.remembered)

        val after = viewModel.shortAnswerMatch!!
        assertTrue(after.available)
        assertEquals("feedback recomputes from the recorded answer, same reference", before.percent, after.percent)
    }

    @Test
    fun skipRecordsNoMatchAndNoAutomaticResult() = statsViewModelTest {
        val fixture = Fixture()
        val viewModel = loadSingleQuestion(fixture, shortQuestion("q1", "参考答案内容"))
        advanceUntilIdle()

        viewModel.updateShortText("部分作答")
        viewModel.revealReference()
        assertNotNull(viewModel.shortAnswerMatch)

        viewModel.skip()
        advanceUntilIdle()

        assertEquals(PracticePhases.COMPLETED, viewModel.state?.session?.phase)
        assertNull("skip goes directly to the end without reference feedback", viewModel.state?.feedback)
        assertEquals(PracticeResults.SKIPPED, viewModel.state?.answers?.single()?.result)
        assertNull("completed skip is not a self-assessment match context", viewModel.shortAnswerMatch)
        assertEquals(1, viewModel.state?.summary?.skipped)
        assertEquals(0, viewModel.state?.summary?.automaticCorrect)
        assertEquals(0, viewModel.state?.summary?.automaticIncorrect)
    }

    @Test
    fun nextQuestionClearsThePreviousMatchAndUsesItsOwnReference() = statsViewModelTest {
        val fixture = Fixture()
        val q1 = shortQuestion("q1", "甲乙", position = 0)
        val q2 = shortQuestion("q2", "丙丁戊", position = 1)
        fixture.libraryStore.addQuestion(q1)
        fixture.libraryStore.addQuestion(q2)
        fixture.addSession(practiceSession("sess", listOf(q1, q2), libraryId = fixture.libraryId))
        val viewModel = fixture.viewModel()
        viewModel.load("sess")
        advanceUntilIdle()

        viewModel.updateShortText("甲乙")
        viewModel.revealReference()
        assertEquals(100, viewModel.shortAnswerMatch?.percent)
        viewModel.selfAssess(true)
        advanceUntilIdle()
        viewModel.next()
        advanceUntilIdle()

        assertEquals("q2", viewModel.state?.currentQuestion?.questionId)
        assertNull("the previous question's match must not leak", viewModel.shortAnswerMatch)
        assertFalse(viewModel.draft.referenceRevealed)

        viewModel.updateShortText("丙丁")
        viewModel.revealReference()
        assertEquals(
            "the new question must use its own reference, not the previous 100% result",
            33,
            viewModel.shortAnswerMatch?.percent
        )
    }

    @Test
    fun restoredDraftAndRevealRecomputeTheMatchFromTheSnapshot() = statsViewModelTest {
        val fixture = Fixture()
        val question = shortQuestion("q1", "甲乙丙")
        fixture.libraryStore.addQuestion(question)
        val base = practiceSession("sess", listOf(question), libraryId = fixture.libraryId)
        fixture.addSession(
            base.copy(
                draft = PracticeDraft(text = "甲乙", referenceRevealed = true),
                draftRevision = 2L
            )
        )
        val viewModel = fixture.viewModel()
        viewModel.load("sess")
        advanceUntilIdle()

        val match = viewModel.shortAnswerMatch
        assertNotNull("a restored reveal must recompute the match", match)
        assertTrue(match!!.available)
        assertEquals(33, match.percent)
        assertEquals(2.0 / 3.0, match.order2Dice!!, 1e-9)
    }

    @Test
    fun editingTheLibraryDoesNotChangeTheSessionSnapshotReference() = statsViewModelTest {
        val fixture = Fixture()
        val question = shortQuestion("q1", "旧参考答案内容")
        fixture.libraryStore.addQuestion(question)
        fixture.addSession(practiceSession("sess", listOf(question), libraryId = fixture.libraryId))
        val viewModel = fixture.viewModel()
        viewModel.load("sess")
        advanceUntilIdle()

        fixture.libraryStore.saveQuestion(
            fixture.libraryStore.questionMap.getValue("q1").copy(
                referenceAnswer = "新参考答案内容",
                editedAt = 9_000L
            ),
            9_000L
        )

        val reloaded = fixture.viewModel()
        reloaded.load("sess")
        advanceUntilIdle()
        assertEquals("旧参考答案内容", reloaded.state?.currentQuestion?.referenceAnswer)

        reloaded.updateShortText("旧参考答案内容")
        reloaded.revealReference()
        assertEquals("must compare against the fixed session snapshot", 100, reloaded.shortAnswerMatch?.percent)
    }

    @Test
    fun numericSeparatorDifferenceReachesTheViewModel() = statsViewModelTest {
        val fixture = Fixture()
        val viewModel = loadSingleQuestion(fixture, shortQuestion("q1", "温度为1.5摄氏度"))
        advanceUntilIdle()

        viewModel.updateShortText("温度为15摄氏度")
        viewModel.revealReference()

        val match = viewModel.shortAnswerMatch
        assertNotNull(match)
        assertTrue(match!!.available)
        assertTrue("1.5 vs 15 must not be a 100% match", match.percent < 100)
    }

    @Test
    fun fillBlankRevealDoesNotShowShortAnswerMatch() = statsViewModelTest {        val fixture = Fixture()
        val question = practiceQuestion(
            id = "f1",
            type = QuestionTypes.FILL_BLANK,
            stem = "填空____",
            options = emptyList(),
            answers = listOf("甲"),
            referenceAnswer = "参考"
        )
        fixture.libraryStore.addQuestion(question)
        fixture.addSession(practiceSession("sess", listOf(question), libraryId = fixture.libraryId))
        val viewModel = fixture.viewModel()
        viewModel.load("sess")
        advanceUntilIdle()

        viewModel.updateBlank(0, "甲")
        viewModel.revealReference()
        assertNull("the overlap aid is scoped to short answers", viewModel.shortAnswerMatch)
        assertTrue(viewModel.canSelfAssess())
    }
}
