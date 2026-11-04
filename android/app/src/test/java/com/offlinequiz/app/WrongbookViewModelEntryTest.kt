package com.offlinequiz.app

import com.offlinequiz.app.data.PracticeRepository
import com.offlinequiz.app.data.PracticeStatsRepository
import com.offlinequiz.app.model.PracticeAnswerKinds
import com.offlinequiz.app.model.PracticeModes
import com.offlinequiz.app.model.PracticeQuestionSnapshot
import com.offlinequiz.app.model.PracticeResults
import com.offlinequiz.app.model.PracticeSources
import com.offlinequiz.app.model.StoredQuestion
import com.offlinequiz.app.model.toPracticeSnapshot
import com.offlinequiz.app.viewmodel.WrongbookFilter
import com.offlinequiz.app.viewmodel.WrongbookViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Entry/retry regression for the new wrongbook screen, driven through the real ViewModel. */
@OptIn(ExperimentalCoroutinesApi::class)
class WrongbookViewModelEntryTest {

    private class Fixture {
        val libraryStore = TestLibraryStore()
        val practiceStore = TestPracticeStore()
        val libraryId = "lib"
        private var counter = 0

        init {
            libraryStore.addLibrary(testLibrary(id = libraryId, name = "题库"))
        }

        fun addQuestion(question: StoredQuestion) {
            libraryStore.addQuestion(question)
        }

        fun addAnswer(
            question: StoredQuestion,
            kind: String,
            result: String,
            at: Long,
            status: String = com.offlinequiz.app.model.PracticeStatuses.SUPERSEDED,
            snapshot: PracticeQuestionSnapshot? = question.toPracticeSnapshot()
        ) {
            val sessionId = "session-$at"
            practiceStore.addSession(
                practiceSession(
                    sessionId,
                    listOf(question),
                    libraryId = libraryId,
                    status = status
                )
            )
            practiceStore.addAnswer(
                practiceAnswer("answer-$at", question, kind, result, at, sessionId = sessionId),
                snapshot
            )
        }

        fun viewModel(dispatcher: CoroutineDispatcher): WrongbookViewModel {
            val statsRepository = PracticeStatsRepository(libraryStore, practiceStore)
            val practiceRepository = PracticeRepository(
                libraryStore = libraryStore,
                practiceStore = practiceStore,
                clock = { 5_000L },
                idFactory = {
                    counter += 1
                    "generated-$counter"
                }
            )
            return WrongbookViewModel(
                applicationWithoutAndroidRuntime(),
                statsRepository,
                practiceRepository,
                dispatcher
            )
        }
    }

    @Test
    fun firstLoadShowsWrongbookAndEndsLoading() = statsViewModelTest {
        val fixture = Fixture()
        val q1 = practiceQuestion("q1", position = 0)
        val q2 = practiceQuestion("q2", position = 1)
        fixture.addQuestion(q1)
        fixture.addQuestion(q2)
        fixture.addAnswer(q1, PracticeAnswerKinds.AUTOMATIC, PracticeResults.INCORRECT, 100)
        fixture.addAnswer(q2, PracticeAnswerKinds.AUTOMATIC, PracticeResults.CORRECT, 200)
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler))

        viewModel.load("lib")
        advanceUntilIdle()

        assertFalse("a finished load must clear loading", viewModel.loading)
        assertNull(viewModel.error)
        assertNotNull(viewModel.report)
        assertEquals(1, fixture.practiceStore.historyReads)
        assertEquals(1, viewModel.wrongbook.size)
        assertEquals("q1", viewModel.due.single().questionId)
        assertEquals(1, viewModel.duePracticableCount)
    }

    @Test
    fun statisticsFailureEndsLoadingAndRetrySucceeds() = statsViewModelTest {
        val fixture = Fixture()
        val q1 = practiceQuestion("q1")
        fixture.addQuestion(q1)
        fixture.addAnswer(q1, PracticeAnswerKinds.AUTOMATIC, PracticeResults.INCORRECT, 100)
        fixture.practiceStore.historyFailures = 1
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler))

        viewModel.load("lib")
        advanceUntilIdle()

        assertFalse(viewModel.loading)
        assertNull(viewModel.report)
        assertNotNull("a failed read must not look like zero statistics", viewModel.error)

        viewModel.retry()
        advanceUntilIdle()

        assertEquals(2, fixture.practiceStore.historyReads)
        assertFalse(viewModel.loading)
        assertNull(viewModel.error)
        assertNotNull(viewModel.report)
        assertEquals(1, viewModel.due.size)
    }

    @Test
    fun reentrantLoadWhileReadingPerformsOneRead() = statsViewModelTest {
        val fixture = Fixture()
        fixture.addQuestion(practiceQuestion("q1"))
        fixture.addAnswer(
            practiceQuestion("q1"),
            PracticeAnswerKinds.AUTOMATIC,
            PracticeResults.INCORRECT,
            100
        )
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler))

        viewModel.load("lib")
        viewModel.load("lib")
        advanceUntilIdle()

        assertEquals(1, fixture.practiceStore.historyReads)
        assertFalse(viewModel.loading)
    }

    @Test
    fun startRoundUsesOnlyDuePracticableQuestions() = statsViewModelTest {
        val fixture = Fixture()
        val q1 = practiceQuestion("q1", position = 0)
        val q2 = practiceQuestion("q2", position = 1)
        val q3 = practiceQuestion("q3", position = 2)
        listOf(q1, q2, q3).forEach { fixture.addQuestion(it) }
        fixture.addAnswer(q1, PracticeAnswerKinds.AUTOMATIC, PracticeResults.INCORRECT, 100)
        fixture.addAnswer(q2, PracticeAnswerKinds.AUTOMATIC, PracticeResults.INCORRECT, 200)
        fixture.addAnswer(q2, PracticeAnswerKinds.AUTOMATIC, PracticeResults.CORRECT, 300)
        fixture.addAnswer(q3, PracticeAnswerKinds.AUTOMATIC, PracticeResults.CORRECT, 400)
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler))

        viewModel.load("lib")
        advanceUntilIdle()
        assertEquals(2, viewModel.wrongbook.size)
        assertEquals("only q1 is still due", 1, viewModel.due.size)

        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        advanceUntilIdle()

        assertNotNull(viewModel.startedPracticeId)
        val session = fixture.practiceStore.sessions.getValue(viewModel.startedPracticeId!!)
        assertEquals(listOf("q1"), session.questions.map { it.questionId })
        assertEquals(PracticeSources.WRONGBOOK, session.source)
    }

    @Test
    fun roundOfUnpracticableDueQuestionsExplainsAndOffersReview() = statsViewModelTest {
        val fixture = Fixture()
        val review = practiceQuestion(
            "review",
            position = 0,
            confirmed = false,
            reviewReasons = listOf("no_answer_in_parentheses")
        )
        fixture.addQuestion(review)
        fixture.addAnswer(review, PracticeAnswerKinds.AUTOMATIC, PracticeResults.INCORRECT, 100)
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler))

        viewModel.load("lib")
        advanceUntilIdle()
        assertEquals(1, viewModel.due.size)
        assertEquals(0, viewModel.duePracticableCount)

        viewModel.startPractice(PracticeModes.RANDOM)
        advanceUntilIdle()

        assertNull(viewModel.startedPracticeId)
        assertNotNull(viewModel.message)
        assertEquals("review", viewModel.firstUnpracticableId)
        assertTrue(fixture.practiceStore.sessions.none { it.value.source == PracticeSources.WRONGBOOK })
    }

    @Test
    fun roundWithoutDueQuestionsExplainsInsteadOfCrashing() = statsViewModelTest {
        val fixture = Fixture()
        val q1 = practiceQuestion("q1")
        fixture.addQuestion(q1)
        fixture.addAnswer(q1, PracticeAnswerKinds.AUTOMATIC, PracticeResults.CORRECT, 100)
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler))

        viewModel.load("lib")
        advanceUntilIdle()
        assertTrue(viewModel.due.isEmpty())

        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        advanceUntilIdle()

        assertNull(viewModel.startedPracticeId)
        assertNotNull(viewModel.message)
    }

    @Test
    fun filterSwitchesBetweenDueAndAllHistoricalWrongQuestions() = statsViewModelTest {
        val fixture = Fixture()
        val q1 = practiceQuestion("q1")
        fixture.addQuestion(q1)
        fixture.addAnswer(q1, PracticeAnswerKinds.AUTOMATIC, PracticeResults.INCORRECT, 100)
        fixture.addAnswer(q1, PracticeAnswerKinds.AUTOMATIC, PracticeResults.CORRECT, 200)
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler))

        viewModel.load("lib")
        advanceUntilIdle()
        assertTrue(viewModel.due.isEmpty())
        assertEquals(1, viewModel.wrongbook.size)

        viewModel.selectFilter(WrongbookFilter.ALL)
        assertEquals(1, viewModel.visible.size)
        viewModel.selectFilter(WrongbookFilter.DUE)
        assertTrue(viewModel.visible.isEmpty())
    }
    @Test
    fun cardReviewBypassesRegularConflictButBlocksDuplicateTapAndLeaveDuringWrite() = statsViewModelTest {
        val f = Fixture()
        val q = practiceQuestion("q")
        f.addQuestion(q)
        f.addAnswer(q, PracticeAnswerKinds.AUTOMATIC, PracticeResults.INCORRECT, 100)
        val regular = practiceSession("regular", listOf(q))
        f.practiceStore.addSession(regular)
        val vm = f.viewModel(UnconfinedTestDispatcher(testScheduler))
        vm.load("lib")
        advanceUntilIdle()
        vm.reviewQuestion("q")
        assertTrue(vm.starting)
        assertTrue(vm.committing)
        assertFalse(vm.tryLeaveScreen())
        vm.reviewQuestion("q")
        advanceUntilIdle()
        assertNotNull(vm.startedPracticeId)
        assertNull(vm.pendingStartMode)
        assertFalse(vm.starting)
        assertFalse(vm.committing)
        assertEquals(1, f.practiceStore.sessions.values.count { it.source == PracticeSources.SINGLE_REVIEW })
        assertEquals(regular, f.practiceStore.sessions["regular"])
    }

    @Test
    fun unpracticableCardShowsMessageAndCreatesNoReview() = statsViewModelTest {
        val f = Fixture()
        val q = practiceQuestion("q", answers = emptyList())
        f.addQuestion(q)
        f.addAnswer(q, PracticeAnswerKinds.AUTOMATIC, PracticeResults.INCORRECT, 100)
        val vm = f.viewModel(UnconfinedTestDispatcher(testScheduler))
        vm.load("lib")
        advanceUntilIdle()
        val count = f.practiceStore.sessions.size
        vm.reviewQuestion("q")
        advanceUntilIdle()
        assertNull(vm.startedPracticeId)
        assertNotNull(vm.message)
        assertFalse(vm.starting)
        assertEquals(count, f.practiceStore.sessions.size)
    }

}
