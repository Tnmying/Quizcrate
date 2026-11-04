package com.offlinequiz.app

import com.offlinequiz.app.data.LibraryRepository
import com.offlinequiz.app.data.PracticeRepository
import com.offlinequiz.app.data.PracticeStatsRepository
import com.offlinequiz.app.model.PracticeAnswerKinds
import com.offlinequiz.app.model.PracticeResults
import com.offlinequiz.app.model.toPracticeSnapshot
import com.offlinequiz.app.viewmodel.LibraryDetailViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Entry regression for the statistics card added to the existing library detail screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryDetailStatsEntryTest {

    private class Fixture {
        val libraryStore = TestLibraryStore()
        val practiceStore = TestPracticeStore()
        val libraryId = "lib"

        init {
            libraryStore.addLibrary(testLibrary(id = libraryId, name = "题库"))
        }

        fun addQuestion(question: com.offlinequiz.app.model.StoredQuestion) {
            libraryStore.addQuestion(question)
            libraryStore.publish(libraryId)
        }

        fun addAnswer(question: com.offlinequiz.app.model.StoredQuestion, at: Long) {
            val sessionId = "session-$at"
            practiceStore.addSession(practiceSession(sessionId, listOf(question), libraryId = libraryId))
            practiceStore.addAnswer(
                practiceAnswer(
                    "answer-$at",
                    question,
                    PracticeAnswerKinds.AUTOMATIC,
                    PracticeResults.INCORRECT,
                    at,
                    sessionId = sessionId
                ),
                question.toPracticeSnapshot()
            )
        }

        fun viewModel() = LibraryDetailViewModel(
            applicationWithoutAndroidRuntime(),
            LibraryRepository(libraryStore),
            PracticeRepository(libraryStore, practiceStore, clock = { 5_000L }, idFactory = { "id" }),
            PracticeStatsRepository(libraryStore, practiceStore),
            UnconfinedTestDispatcher()
        )
    }

    @Test
    fun firstLoadShowsOverviewAndWrongbookCounts() = statsViewModelTest {
        val fixture = Fixture()
        val q1 = practiceQuestion("q1")
        fixture.addQuestion(q1)
        fixture.addAnswer(q1, 100)
        val viewModel = fixture.viewModel()

        viewModel.load("lib")
        advanceUntilIdle()

        assertFalse(viewModel.loading)
        assertFalse(viewModel.statsLoading)
        assertNull(viewModel.statsError)
        assertNotNull(viewModel.stats)
        assertEquals(1, viewModel.stats!!.overview.practiceCount)
        assertEquals(1, viewModel.stats!!.overview.automaticIncorrect)
        assertEquals(1, viewModel.stats!!.overview.wrongQuestionCount)
        assertEquals(1, viewModel.stats!!.overview.dueQuestionCount)
        assertEquals(1, viewModel.stats!!.overview.questionCount)
    }

    @Test
    fun statisticsFailureShowsRetryAndRecovers() = statsViewModelTest {
        val fixture = Fixture()
        val q1 = practiceQuestion("q1")
        fixture.addQuestion(q1)
        fixture.addAnswer(q1, 100)
        fixture.libraryStore.failingQuestionReads = 1
        val viewModel = fixture.viewModel()

        viewModel.load("lib")
        advanceUntilIdle()

        assertFalse(viewModel.loading)
        assertFalse("a failed read must not stay in loading", viewModel.statsLoading)
        assertNull(viewModel.stats)
        assertNotNull(viewModel.statsError)

        viewModel.retryStats()
        advanceUntilIdle()

        assertFalse(viewModel.statsLoading)
        assertNull(viewModel.statsError)
        assertNotNull(viewModel.stats)
        assertEquals(1, viewModel.stats!!.overview.practiceCount)
    }
}
