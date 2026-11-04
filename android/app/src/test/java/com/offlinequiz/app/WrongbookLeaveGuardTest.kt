package com.offlinequiz.app

import com.offlinequiz.app.data.PracticeRepository
import com.offlinequiz.app.data.PracticeStatsRepository
import com.offlinequiz.app.data.PracticeStore
import com.offlinequiz.app.model.PracticeAnswerKinds
import com.offlinequiz.app.model.PracticeModes
import com.offlinequiz.app.model.PracticeResults
import com.offlinequiz.app.model.PracticeSessionRecord
import com.offlinequiz.app.model.PracticeSessionSummary
import com.offlinequiz.app.model.StoredQuestion
import com.offlinequiz.app.model.toPracticeSnapshot
import com.offlinequiz.app.viewmodel.WrongbookViewModel
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3 regression for the single leave guard. Every user-triggered navigation out of the wrongbook
 * screen (back/history/review/error-state back) must consult [WrongbookViewModel.tryLeaveScreen]:
 * blocked while committing, allowed again after success/failure, and leaving during the
 * confirmation re-read must invalidate that request first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WrongbookLeaveGuardTest {

    private class Fixture {
        val libraryStore = TestLibraryStore()
        val base = TestPracticeStore()
        val libraryId = "lib"
        private var counter = 0

        init {
            libraryStore.addLibrary(testLibrary(id = libraryId, name = "题库"))
        }

        fun addQuestion(question: StoredQuestion) {
            libraryStore.addQuestion(question)
        }

        fun addExistingSession(question: StoredQuestion) {
            base.addSession(practiceSession("existing", listOf(question), libraryId = libraryId))
            base.addAnswer(
                practiceAnswer(
                    "wrong",
                    question,
                    PracticeAnswerKinds.AUTOMATIC,
                    PracticeResults.INCORRECT,
                    100L,
                    sessionId = "existing"
                ),
                question.toPracticeSnapshot()
            )
        }

        fun viewModel(
            dispatcher: CoroutineDispatcher,
            store: PracticeStore = base
        ): WrongbookViewModel = WrongbookViewModel(
            applicationWithoutAndroidRuntime(),
            PracticeStatsRepository(libraryStore, store),
            PracticeRepository(
                libraryStore = libraryStore,
                practiceStore = store,
                clock = { 5_000L },
                idFactory = {
                    counter += 1
                    "new-$counter"
                }
            ),
            dispatcher
        )

        fun newSessions(): List<String> = base.sessions.keys.filter { it.startsWith("new-") }
    }

    private fun fixtureWithExisting(): Pair<Fixture, StoredQuestion> {
        val fixture = Fixture()
        val question = practiceQuestion("q1")
        fixture.addQuestion(question)
        fixture.addExistingSession(question)
        return fixture to question
    }

    @Test
    fun leaveIsBlockedWhileCommittingAndAllowedAfterSuccess() = statsViewModelTest {
        val (fixture, _) = fixtureWithExisting()
        val writeGate = CompletableDeferred<Unit>()
        val store = object : PracticeStore by fixture.base {
            override suspend fun createSession(session: PracticeSessionRecord) {
                writeGate.await()
                fixture.base.createSession(session)
            }
        }
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler), store)
        viewModel.load(fixture.libraryId)
        advanceUntilIdle()
        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        viewModel.confirmStartPractice()
        advanceUntilIdle()
        assertTrue(viewModel.committing)

        // Every navigation entry in the screen funnels through this guard.
        assertFalse("leaving must be refused while the write is in flight", viewModel.tryLeaveScreen())
        assertNotNull(viewModel.message)
        assertTrue(viewModel.message!!.contains("不能取消或离开"))
        assertTrue(fixture.newSessions().isEmpty())

        writeGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, fixture.newSessions().size)
        assertNotNull(viewModel.startedPracticeId)
        assertFalse(viewModel.committing)
        assertTrue("leaving is allowed again once the write finished", viewModel.tryLeaveScreen())
    }

    @Test
    fun leaveIsAllowedAfterACommitFailureAndNoSessionIsCreated() = statsViewModelTest {
        val (fixture, _) = fixtureWithExisting()
        val store = object : PracticeStore by fixture.base {
            override suspend fun createSession(session: PracticeSessionRecord) {
                throw IOException("write failed")
            }
        }
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler), store)
        viewModel.load(fixture.libraryId)
        advanceUntilIdle()
        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        viewModel.confirmStartPractice()
        advanceUntilIdle()

        assertFalse(viewModel.committing)
        assertNull(viewModel.startedPracticeId)
        assertTrue(fixture.newSessions().isEmpty())
        assertTrue("a failed write must restore the leave paths", viewModel.tryLeaveScreen())
    }

    @Test
    fun leaveDuringConfirmRecheckInvalidatesTheRequestFirst() = statsViewModelTest {
        val (fixture, _) = fixtureWithExisting()
        val gate = CompletableDeferred<Unit>()
        val store = object : PracticeStore by fixture.base {
            override suspend fun activeSession(libraryId: String): PracticeSessionSummary? {
                // Non-cancellable read: even if it returns late, the invalidated token must win.
                withContext(NonCancellable) { gate.await() }
                return null
            }
        }
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler), store)
        viewModel.load(fixture.libraryId)
        advanceUntilIdle()
        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        viewModel.confirmStartPractice()
        runCurrent()
        assertTrue(viewModel.starting)

        assertTrue("leaving during the re-read is allowed after invalidating it", viewModel.tryLeaveScreen())
        assertNull(viewModel.pendingStartMode)
        assertFalse(viewModel.starting)

        gate.complete(Unit)
        advanceUntilIdle()

        assertNull(viewModel.startedPracticeId)
        assertTrue(fixture.newSessions().isEmpty())
    }

    @Test
    fun leaveIsAllowedWhenNothingIsInFlight() = statsViewModelTest {
        val (fixture, _) = fixtureWithExisting()
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler))
        viewModel.load(fixture.libraryId)
        advanceUntilIdle()

        assertTrue(viewModel.tryLeaveScreen())
        assertTrue(fixture.newSessions().isEmpty())
        assertNull(viewModel.pendingStartMode)
    }

    @Test
    fun successfulCommitNavigatesOnceAndLeavesAreReopened() = statsViewModelTest {
        val (fixture, _) = fixtureWithExisting()
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler))
        viewModel.load(fixture.libraryId)
        advanceUntilIdle()
        val startedBefore = fixture.base.sessions.size

        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        viewModel.confirmStartPractice()
        advanceUntilIdle()

        assertEquals(startedBefore + 1, fixture.base.sessions.size)
        val firstStarted = viewModel.startedPracticeId
        assertNotNull(firstStarted)
        assertFalse(viewModel.committing)
        assertTrue(viewModel.tryLeaveScreen())
        assertEquals("the completion navigation is set once", firstStarted, viewModel.startedPracticeId)
    }
}
