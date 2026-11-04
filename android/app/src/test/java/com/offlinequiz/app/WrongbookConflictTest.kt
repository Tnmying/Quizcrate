package com.offlinequiz.app

import com.offlinequiz.app.data.PracticeRepository
import com.offlinequiz.app.data.PracticeStatsRepository
import com.offlinequiz.app.data.PracticeStore
import com.offlinequiz.app.model.PracticeAnswerKinds
import com.offlinequiz.app.model.PracticeModes
import com.offlinequiz.app.model.PracticeResults
import com.offlinequiz.app.model.PracticeSessionSummary
import com.offlinequiz.app.model.PracticeSources
import com.offlinequiz.app.model.StoredQuestion
import com.offlinequiz.app.model.toPracticeSnapshot
import com.offlinequiz.app.viewmodel.ActivePracticeState
import com.offlinequiz.app.viewmodel.WrongbookViewModel
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1 regression: starting a wrongbook round must never silently replace an active session, and a
 * failed/pending lookup must block starting until a successful read says otherwise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WrongbookConflictTest {

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

        /** Historical (superseded) wrong answer so a question is due without an active session. */
        fun addHistoricalAnswer(question: StoredQuestion, sessionId: String = "old") {
            base.addSession(
                practiceSession(
                    sessionId,
                    listOf(question),
                    libraryId = libraryId,
                    status = com.offlinequiz.app.model.PracticeStatuses.SUPERSEDED
                )
            )
            base.addAnswer(
                practiceAnswer(
                    "wrong-$sessionId",
                    question,
                    PracticeAnswerKinds.AUTOMATIC,
                    PracticeResults.INCORRECT,
                    100L,
                    sessionId = sessionId
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

    @Test
    fun statisticsReadyButActiveLookupPendingRefusesToStart() = statsViewModelTest {
        val fixture = Fixture()
        val question = practiceQuestion("q1")
        fixture.addQuestion(question)
        fixture.addExistingSession(question)
        val gate = CompletableDeferred<Unit>()
        val delayed = object : PracticeStore by fixture.base {
            override fun observeActiveSession(libraryId: String): Flow<PracticeSessionSummary?> =
                flow {
                    gate.await()
                    emit(fixture.base.activeFlow.value)
                }
        }
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler), delayed)

        viewModel.load(fixture.libraryId)
        advanceUntilIdle()

        assertEquals(1, viewModel.duePracticableCount)
        assertFalse("a pending lookup is not 'no session'", viewModel.practiceLoaded)
        assertEquals(ActivePracticeState.LOADING, viewModel.activePracticeState)

        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        advanceUntilIdle()

        assertTrue("pending lookup must block creation", fixture.newSessions().isEmpty())
        assertNull(viewModel.startedPracticeId)
        assertNotNull(viewModel.message)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.practiceLoaded)
        assertEquals(ActivePracticeState.PRESENT, viewModel.activePracticeState)
        assertTrue(fixture.newSessions().isEmpty())
    }

    @Test
    fun activeLookupFailureRefusesStartAndRetryRecovers() = statsViewModelTest {
        val fixture = Fixture()
        val question = practiceQuestion("q1")
        fixture.addQuestion(question)
        fixture.addHistoricalAnswer(question)
        var failures = 1
        val failing = object : PracticeStore by fixture.base {
            override fun observeActiveSession(libraryId: String): Flow<PracticeSessionSummary?> =
                flow {
                    if (failures > 0) {
                        failures -= 1
                        throw IOException("active lookup failed")
                    }
                    emit(fixture.base.activeFlow.value)
                }
        }
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler), failing)

        viewModel.load(fixture.libraryId)
        advanceUntilIdle()

        assertEquals(ActivePracticeState.FAILED, viewModel.activePracticeState)
        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        advanceUntilIdle()
        assertTrue("failed lookup must block creation", fixture.newSessions().isEmpty())

        viewModel.retryActivePractice()
        advanceUntilIdle()
        assertEquals(ActivePracticeState.NONE, viewModel.activePracticeState)

        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        advanceUntilIdle()
        assertNotNull(viewModel.startedPracticeId)
        assertEquals(1, fixture.newSessions().size)
    }

    @Test
    fun knownEmptyActiveStateStartsNormally() = statsViewModelTest {
        val fixture = Fixture()
        val question = practiceQuestion("q1")
        fixture.addQuestion(question)
        fixture.addHistoricalAnswer(question)
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler))

        viewModel.load(fixture.libraryId)
        advanceUntilIdle()
        assertEquals(ActivePracticeState.NONE, viewModel.activePracticeState)

        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        advanceUntilIdle()

        assertNotNull(viewModel.startedPracticeId)
        val created = fixture.base.sessions.getValue(viewModel.startedPracticeId!!)
        assertEquals(PracticeSources.WRONGBOOK, created.source)
        assertEquals(listOf("q1"), created.questions.map { it.questionId })
    }

    @Test
    fun plainStartWithExistingSessionOnlyAsksForConfirmation() = statsViewModelTest {
        val fixture = Fixture()
        val question = practiceQuestion("q1")
        fixture.addQuestion(question)
        fixture.addExistingSession(question)
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler))

        viewModel.load(fixture.libraryId)
        advanceUntilIdle()
        assertEquals(ActivePracticeState.PRESENT, viewModel.activePracticeState)
        val before = fixture.base.sessions.size

        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        advanceUntilIdle()

        assertEquals("plain start must not replace the session", before, fixture.base.sessions.size)
        assertNotNull(viewModel.pendingStartMode)
        assertNull(viewModel.startedPracticeId)
        assertEquals("existing", viewModel.activePractice?.id)
    }

    @Test
    fun explicitConfirmationCreatesTheNewRound() = statsViewModelTest {
        val fixture = Fixture()
        val question = practiceQuestion("q1")
        fixture.addQuestion(question)
        fixture.addExistingSession(question)
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler))

        viewModel.load(fixture.libraryId)
        advanceUntilIdle()
        val before = fixture.base.sessions.size
        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        advanceUntilIdle()

        viewModel.confirmStartPractice()
        advanceUntilIdle()

        assertEquals(before + 1, fixture.base.sessions.size)
        assertNotNull(viewModel.startedPracticeId)
        assertNull(viewModel.pendingStartMode)
        val created = fixture.base.sessions.getValue(viewModel.startedPracticeId!!)
        assertEquals(PracticeSources.WRONGBOOK, created.source)
        assertEquals(listOf("q1"), created.questions.map { it.questionId })
        assertEquals("old history is kept", PracticeResults.INCORRECT, fixture.base
            .answerMap.values.first { it.sessionId == "existing" }.result)
    }

    @Test
    fun cancelKeepsTheExistingSessionAndCreatesNothing() = statsViewModelTest {
        val fixture = Fixture()
        val question = practiceQuestion("q1")
        fixture.addQuestion(question)
        fixture.addExistingSession(question)
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler))

        viewModel.load(fixture.libraryId)
        advanceUntilIdle()
        val before = fixture.base.sessions.size
        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        advanceUntilIdle()

        viewModel.cancelPendingStart()
        advanceUntilIdle()

        assertNull(viewModel.pendingStartMode)
        assertEquals(before, fixture.base.sessions.size)
        assertTrue(fixture.newSessions().isEmpty())
        assertEquals("existing", viewModel.activePractice?.id)
    }

    @Test
    fun changedActiveSessionRequiresConfirmationAgain() = statsViewModelTest {
        val fixture = Fixture()
        val question = practiceQuestion("q1")
        fixture.addQuestion(question)
        fixture.addExistingSession(question)
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler))

        viewModel.load(fixture.libraryId)
        advanceUntilIdle()
        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        advanceUntilIdle()
        assertEquals("existing", viewModel.activePractice?.id)

        // Another session became active while the dialog was open.
        fixture.base.addSession(practiceSession("other", listOf(question), libraryId = fixture.libraryId))
        advanceUntilIdle()
        assertEquals("other", viewModel.activePractice?.id)
        val before = fixture.base.sessions.size

        viewModel.confirmStartPractice()
        advanceUntilIdle()

        assertEquals("a changed session must be confirmed again", before, fixture.base.sessions.size)
        assertNotNull(viewModel.pendingStartMode)
        assertNotNull(viewModel.message)

        viewModel.confirmStartPractice()
        advanceUntilIdle()

        assertEquals(before + 1, fixture.base.sessions.size)
        assertNull(viewModel.pendingStartMode)
    }

    @Test
    fun duplicateConfirmationCreatesOnlyOneSession() = statsViewModelTest {
        val fixture = Fixture()
        val question = practiceQuestion("q1")
        fixture.addQuestion(question)
        fixture.addExistingSession(question)
        val viewModel = fixture.viewModel(UnconfinedTestDispatcher(testScheduler))

        viewModel.load(fixture.libraryId)
        advanceUntilIdle()
        val before = fixture.base.sessions.size
        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        advanceUntilIdle()

        viewModel.confirmStartPractice()
        viewModel.confirmStartPractice()
        advanceUntilIdle()

        assertEquals(before + 1, fixture.base.sessions.size)
        assertEquals(1, fixture.newSessions().size)
    }
}
