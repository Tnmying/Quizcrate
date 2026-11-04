package com.offlinequiz.app

import com.offlinequiz.app.data.PracticeStatsRepository
import com.offlinequiz.app.model.PracticeAnswerKinds
import com.offlinequiz.app.model.PracticeResults
import com.offlinequiz.app.model.toPracticeSnapshot
import com.offlinequiz.app.viewmodel.QuestionHistoryViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Entry/retry regression for the per-question history screen (real ViewModel). */
@OptIn(ExperimentalCoroutinesApi::class)
class QuestionHistoryViewModelEntryTest {

    private class Fixture {
        val libraryStore = TestLibraryStore()
        val practiceStore = TestPracticeStore()
        val libraryId = "lib"

        init {
            libraryStore.addLibrary(testLibrary(id = libraryId, name = "题库"))
        }

        fun addAnswer(
            question: com.offlinequiz.app.model.StoredQuestion,
            at: Long,
            kind: String = PracticeAnswerKinds.AUTOMATIC,
            result: String = PracticeResults.INCORRECT,
            userAnswer: com.offlinequiz.app.model.PracticeUserAnswer =
                com.offlinequiz.app.model.PracticeUserAnswer(selectedKeys = listOf("A"))
        ) {
            val sessionId = "session-$at"
            practiceStore.addSession(
                practiceSession(
                    sessionId,
                    listOf(question),
                    libraryId = libraryId,
                    status = com.offlinequiz.app.model.PracticeStatuses.SUPERSEDED
                )
            )
            practiceStore.addAnswer(
                practiceAnswer(
                    "answer-$at",
                    question,
                    kind,
                    result,
                    at,
                    sessionId = sessionId,
                    userAnswer = userAnswer
                ),
                question.toPracticeSnapshot()
            )
        }

        fun viewModel() = QuestionHistoryViewModel(
            applicationWithoutAndroidRuntime(),
            PracticeStatsRepository(libraryStore, practiceStore),
            UnconfinedTestDispatcher()
        )
    }

    @Test
    fun firstLoadShowsHistoryAndEndsLoading() = statsViewModelTest {
        val fixture = Fixture()
        val question = practiceQuestion("q1")
        fixture.libraryStore.addQuestion(question)
        fixture.addAnswer(question, 100)
        fixture.addAnswer(question, 200)
        val viewModel = fixture.viewModel()

        viewModel.load("lib", "q1")
        advanceUntilIdle()

        assertFalse(viewModel.loading)
        assertNull(viewModel.error)
        assertNotNull(viewModel.history)
        assertEquals(2, viewModel.history!!.records.size)
        assertEquals("newest first", 200L, viewModel.history!!.records.first().answer.submittedAt)
        assertEquals("q1", viewModel.history!!.question?.id)
    }

    @Test
    fun historyFailureEndsLoadingAndRetrySucceeds() = statsViewModelTest {
        val fixture = Fixture()
        val question = practiceQuestion("q1")
        fixture.libraryStore.addQuestion(question)
        fixture.addAnswer(question, 100)
        fixture.practiceStore.historyFailures = 1
        val viewModel = fixture.viewModel()

        viewModel.load("lib", "q1")
        advanceUntilIdle()

        assertFalse(viewModel.loading)
        assertNull(viewModel.history)
        assertNotNull(viewModel.error)

        viewModel.retry()
        advanceUntilIdle()

        assertFalse(viewModel.loading)
        assertNull(viewModel.error)
        assertNotNull(viewModel.history)
        assertEquals(1, viewModel.history!!.records.size)
    }

    @Test
    fun missingQuestionStillShowsAnEmptyHistoryWithoutCrashing() = statsViewModelTest {
        val fixture = Fixture()
        val viewModel = fixture.viewModel()

        viewModel.load("lib", "missing")
        advanceUntilIdle()

        assertFalse(viewModel.loading)
        assertNull(viewModel.error)
        assertNotNull(viewModel.history)
        assertNull(viewModel.history!!.question)
        assertTrue(viewModel.history!!.records.isEmpty())
    }

    @Test
    fun alwaysCorrectQuestionShowsItsCountsAndHistory() = statsViewModelTest {
        val fixture = Fixture()
        val question = practiceQuestion("q1")
        fixture.libraryStore.addQuestion(question)
        fixture.addAnswer(question, 100, result = PracticeResults.CORRECT)
        fixture.addAnswer(question, 200, result = PracticeResults.CORRECT)
        val viewModel = fixture.viewModel()

        viewModel.load("lib", "q1")
        advanceUntilIdle()

        assertNull(viewModel.error)
        val stats = viewModel.history!!.stats!!
        assertEquals(2, stats.automaticCorrect)
        assertEquals(0, stats.historicalWrongCount)
        assertTrue(stats.mastery is com.offlinequiz.app.model.PracticeMastery.Passed)
        assertEquals(2, viewModel.history!!.records.size)
    }

    @Test
    fun onlyRememberedSelfAssessmentQuestionIsReachable() = statsViewModelTest {
        val fixture = Fixture()
        val question = practiceQuestion("s1", type = com.offlinequiz.app.model.QuestionTypes.SHORT_ANSWER)
        fixture.libraryStore.addQuestion(question)
        fixture.addAnswer(
            question,
            100,
            kind = PracticeAnswerKinds.SELF_ASSESSMENT,
            result = PracticeResults.REMEMBERED,
            userAnswer = com.offlinequiz.app.model.PracticeUserAnswer(text = "我的回答")
        )
        val viewModel = fixture.viewModel()

        viewModel.load("lib", "s1")
        advanceUntilIdle()

        val stats = viewModel.history!!.stats!!
        assertEquals(1, stats.remembered)
        assertEquals(1, stats.practiceCount)
        assertEquals(1, viewModel.history!!.records.size)
    }

    @Test
    fun onlySkippedQuestionShowsSkippedCountAndNoPracticeCount() = statsViewModelTest {
        val fixture = Fixture()
        val question = practiceQuestion("q1")
        fixture.libraryStore.addQuestion(question)
        fixture.addAnswer(
            question,
            100,
            kind = PracticeAnswerKinds.SKIPPED,
            result = PracticeResults.SKIPPED,
            userAnswer = com.offlinequiz.app.model.PracticeUserAnswer()
        )
        val viewModel = fixture.viewModel()

        viewModel.load("lib", "q1")
        advanceUntilIdle()

        val stats = viewModel.history!!.stats!!
        assertEquals(1, stats.skipped)
        assertEquals(0, stats.practiceCount)
        assertNull(stats.lastPracticeAt)
        assertEquals(1, viewModel.history!!.records.size)
    }

    @Test
    fun neverPracticedQuestionShowsEmptyStateInsteadOfError() = statsViewModelTest {
        val fixture = Fixture()
        val question = practiceQuestion("q1")
        fixture.libraryStore.addQuestion(question)
        val viewModel = fixture.viewModel()

        viewModel.load("lib", "q1")
        advanceUntilIdle()

        assertFalse(viewModel.loading)
        assertNull(viewModel.error)
        val history = viewModel.history!!
        assertEquals("q1", history.question?.id)
        assertEquals(0, history.stats!!.practiceCount)
        assertTrue(history.records.isEmpty())
        assertTrue(history.stats!!.mastery is com.offlinequiz.app.model.PracticeMastery.NotPracticed)
    }
}
