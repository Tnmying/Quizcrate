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
 * R2 regression: cancelling/closing the confirmation dialog must invalidate the whole request, not
 * just clear the pending fields. A late re-read (null/original/different session) must not create a
 * new round, and once the write has been issued the cancel paths must be refused instead of leaving
 * a background write running.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WrongbookCancelTest {

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
    fun cancelWhileConfirmReadPendingReturningNullCreatesNothing() = statsViewModelTest {
        val (fixture, _) = fixtureWithExisting()
        val gate = CompletableDeferred<Unit>()
        val store = object : PracticeStore by fixture.base {
            override suspend fun activeSession(libraryId: String): PracticeSessionSummary? {
                gate.await()
                return null
            }
        }
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler), store)
        viewModel.load(fixture.libraryId)
        advanceUntilIdle()

        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        assertNotNull(viewModel.pendingStartMode)
        viewModel.confirmStartPractice()
        runCurrent()
        assertTrue(viewModel.starting)
        assertFalse(viewModel.committing)

        viewModel.cancelPendingStart()
        assertNull(viewModel.pendingStartMode)
        assertFalse("cancel must release the UI", viewModel.starting)

        gate.complete(Unit)
        advanceUntilIdle()

        assertNull(viewModel.startedPracticeId)
        assertTrue("a cancelled request must not create a session", fixture.newSessions().isEmpty())
    }

    @Test
    fun cancelWhileConfirmReadPendingReturningTheOriginalSessionCreatesNothing() = statsViewModelTest {
        val (fixture, _) = fixtureWithExisting()
        val gate = CompletableDeferred<Unit>()
        val store = object : PracticeStore by fixture.base {
            override suspend fun activeSession(libraryId: String): PracticeSessionSummary? {
                gate.await()
                return fixture.base.activeFlow.value
            }
        }
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler), store)
        viewModel.load(fixture.libraryId)
        advanceUntilIdle()
        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        viewModel.confirmStartPractice()
        runCurrent()

        viewModel.cancelPendingStart()
        gate.complete(Unit)
        advanceUntilIdle()

        assertNull(viewModel.startedPracticeId)
        assertTrue(fixture.newSessions().isEmpty())
        assertEquals("existing", viewModel.activePractice?.id)
    }

    @Test
    fun cancelWhileConfirmReadPendingReturningAnotherSessionDoesNotReopenDialog() = statsViewModelTest {
        val (fixture, _) = fixtureWithExisting()
        val gate = CompletableDeferred<Unit>()
        val store = object : PracticeStore by fixture.base {
            override suspend fun activeSession(libraryId: String): PracticeSessionSummary? {
                gate.await()
                return PracticeSessionSummary(
                    id = "other",
                    libraryId = "lib",
                    mode = PracticeModes.SEQUENTIAL,
                    status = "active",
                    total = 1,
                    currentIndex = 0,
                    phase = "answering",
                    answeredCount = 0,
                    createdAt = 1L,
                    updatedAt = 1L
                )
            }
        }
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler), store)
        viewModel.load(fixture.libraryId)
        advanceUntilIdle()
        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        viewModel.confirmStartPractice()
        runCurrent()

        viewModel.cancelPendingStart()
        gate.complete(Unit)
        advanceUntilIdle()

        assertNull("a late return must not reopen the confirmation", viewModel.pendingStartMode)
        assertNull(viewModel.startedPracticeId)
        assertTrue(fixture.newSessions().isEmpty())
    }

    @Test
    fun cancelThenImmediateRetryCreatesOnceAndLateReturnCannotClobberIt() = statsViewModelTest {
        val (fixture, _) = fixtureWithExisting()
        val gate = CompletableDeferred<Unit>()
        var activeReads = 0
        val store = object : PracticeStore by fixture.base {
            override suspend fun activeSession(libraryId: String): PracticeSessionSummary? {
                activeReads += 1
                if (activeReads == 1) {
                    // Simulate a slow, non-cancellable read that returns after the user retried.
                    withContext(NonCancellable) { gate.await() }
                    return null
                }
                return fixture.base.activeFlow.value
            }
        }
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler), store)
        viewModel.load(fixture.libraryId)
        advanceUntilIdle()

        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        viewModel.confirmStartPractice()
        runCurrent()
        viewModel.cancelPendingStart()

        // Immediately request again; the second confirmation must own the state.
        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        assertNotNull(viewModel.pendingStartMode)
        viewModel.confirmStartPractice()
        advanceUntilIdle()
        assertEquals(1, fixture.newSessions().size)
        val started = viewModel.startedPracticeId
        assertNotNull(started)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("the late first read must not create a second round", 1, fixture.newSessions().size)
        assertEquals(started, viewModel.startedPracticeId)
        assertFalse(viewModel.committing)
        assertFalse(viewModel.starting)
    }

    @Test
    fun cancelDuringCommitIsRefusedAndTheCommittedWriteFinishesOnce() = statsViewModelTest {
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

        assertTrue(viewModel.starting)
        assertTrue("the write has been issued", viewModel.committing)

        viewModel.cancelPendingStart()
        assertTrue("cancel must be refused during the commit phase", viewModel.committing)
        assertNotNull(viewModel.message)
        assertTrue(fixture.newSessions().isEmpty())

        writeGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, fixture.newSessions().size)
        assertNotNull(viewModel.startedPracticeId)
        assertFalse(viewModel.committing)
        assertFalse(viewModel.starting)
    }

    @Test
    fun confirmFailureKeepsDialogAndAllowsRetry() = statsViewModelTest {
        val (fixture, _) = fixtureWithExisting()
        var failures = 1
        val store = object : PracticeStore by fixture.base {
            override suspend fun activeSession(libraryId: String): PracticeSessionSummary? {
                if (failures > 0) {
                    failures -= 1
                    throw java.io.IOException("active read failed")
                }
                return fixture.base.activeFlow.value
            }
        }
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler), store)
        viewModel.load(fixture.libraryId)
        advanceUntilIdle()
        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        viewModel.confirmStartPractice()
        advanceUntilIdle()

        assertFalse(viewModel.starting)
        assertNotNull(viewModel.pendingStartMode)
        assertNotNull(viewModel.message)
        assertTrue(fixture.newSessions().isEmpty())

        viewModel.confirmStartPractice()
        advanceUntilIdle()
        assertEquals(1, fixture.newSessions().size)
    }

    @Test
    fun duplicateConfirmWhilePendingCreatesOneRound() = statsViewModelTest {
        val (fixture, _) = fixtureWithExisting()
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler))
        viewModel.load(fixture.libraryId)
        advanceUntilIdle()
        viewModel.startPractice(PracticeModes.SEQUENTIAL)

        viewModel.confirmStartPractice()
        viewModel.confirmStartPractice()
        advanceUntilIdle()

        assertEquals(1, fixture.newSessions().size)
    }
}
