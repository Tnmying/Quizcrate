package com.offlinequiz.app

import com.offlinequiz.app.data.LibraryStore
import com.offlinequiz.app.data.PracticeRepository
import com.offlinequiz.app.data.PracticeStore
import com.offlinequiz.app.data.PracticeSubmitResult
import com.offlinequiz.app.data.DraftSaveOutcome
import com.offlinequiz.app.model.LibraryRecord
import com.offlinequiz.app.model.LibrarySummary
import com.offlinequiz.app.model.PracticeAnswerKinds
import com.offlinequiz.app.model.PracticeDraft
import com.offlinequiz.app.model.PracticeHistoryEntry
import com.offlinequiz.app.model.PracticeModes
import com.offlinequiz.app.model.PracticePhases
import com.offlinequiz.app.model.PracticeQuestionSnapshot
import com.offlinequiz.app.model.PracticeRecordedAnswer
import com.offlinequiz.app.model.PracticeResults
import com.offlinequiz.app.model.PracticeSessionRecord
import com.offlinequiz.app.model.PracticeSessionSummary
import com.offlinequiz.app.model.PracticeSources
import com.offlinequiz.app.model.PracticeStatuses
import com.offlinequiz.app.model.PracticeUserAnswer
import com.offlinequiz.app.model.PracticeTotals
import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.model.QuizOption
import com.offlinequiz.app.model.StoredQuestion
import com.offlinequiz.app.viewmodel.PracticeViewModel
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Entry-level regression for the practice screen: it drives the real [PracticeViewModel] against an
 * in-memory [PracticeStore], so it would fail on the 03A defect where the first `load()` returned
 * before ever reaching the repository and `loading` stayed true forever.
 *
 * The Android Application is allocated without running its constructor (no database/PDF setup in a
 * JVM test); the ViewModel only stores it. No production behaviour is stubbed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PracticeViewModelEntryTest {

    @Test
    fun firstLoadReadsTheSessionShowsQuestionsAndEndsLoading() = viewModelTest {
        val store = FakePracticeStore()
        store.sessions["s"] = session("s", listOf(single("q1"), single("q2")))
        val viewModel = viewModel(store)

        viewModel.load("s")
        advanceUntilIdle()

        assertEquals("the first load must reach the repository", 1, store.reads("s"))
        assertFalse("a finished load must clear loading", viewModel.loading)
        assertNull(viewModel.error)
        assertNotNull(viewModel.state)
        assertEquals(2, viewModel.state?.session?.total)
        assertEquals(0, viewModel.state?.session?.currentIndex)
        assertEquals(PracticePhases.ANSWERING, viewModel.state?.session?.phase)
        assertEquals(listOf("q1", "q2"), viewModel.state?.session?.questions?.map { it.questionId })
        assertNotNull(viewModel.state?.currentQuestion)
    }

    @Test
    fun firstLoadOfMissingSessionShowsChineseErrorAndEndsLoading() = viewModelTest {
        val store = FakePracticeStore()
        val viewModel = viewModel(store)

        viewModel.load("missing")
        advanceUntilIdle()

        assertEquals("a missing session must still be read", 1, store.reads("missing"))
        assertFalse("a missing session must not spin forever", viewModel.loading)
        assertNull(viewModel.state)
        val error = viewModel.error
        assertNotNull("a missing session must show a message", error)
        assertTrue("the error must be user readable Chinese", error.orEmpty().contains("练习读取失败"))
    }

    @Test
    fun readFailureEndsLoadingAndRetrySucceeds() = viewModelTest {
        val store = FakePracticeStore()
        store.sessions["s"] = session("s", listOf(single("q1")))
        store.readsToFail = 1
        val viewModel = viewModel(store)

        viewModel.load("s")
        advanceUntilIdle()

        assertEquals(1, store.reads("s"))
        assertFalse(viewModel.loading)
        assertNull(viewModel.state)
        assertNotNull(viewModel.error)

        viewModel.retry()
        advanceUntilIdle()

        assertEquals("retry must read again", 2, store.reads("s"))
        assertFalse(viewModel.loading)
        assertNull(viewModel.error)
        assertNotNull("retry must recover the session", viewModel.state)
        assertNotNull(viewModel.state?.currentQuestion)
    }

    @Test
    fun duplicateLoadWhileReadingPerformsOneRead() = viewModelTest {
        val store = FakePracticeStore()
        store.sessions["s"] = session("s", listOf(single("q1")))
        val viewModel = viewModel(store)

        viewModel.load("s")
        viewModel.load("s")
        advanceUntilIdle()

        assertEquals("duplicate calls must not start parallel reads", 1, store.reads("s"))
        assertFalse(viewModel.loading)
        assertNotNull(viewModel.state)
    }

    @Test
    fun reloadAfterSuccessDoesNotReadAgain() = viewModelTest {
        val store = FakePracticeStore()
        store.sessions["s"] = session("s", listOf(single("q1")))
        val viewModel = viewModel(store)

        viewModel.load("s")
        advanceUntilIdle()
        viewModel.load("s")
        advanceUntilIdle()

        assertEquals(1, store.reads("s"))
        assertFalse(viewModel.loading)
    }

    @Test
    fun lateCallbackOfSupersededSessionCannotReplaceCurrentState() = viewModelTest {
        val store = FakePracticeStore()
        store.sessions["a"] = session("a", listOf(single("qa")))
        store.sessions["b"] = session("b", listOf(single("qb")))
        store.sessions["c"] = session("c", listOf(single("qc")))
        val gate = CompletableDeferred<Unit>()
        store.gates["b"] = gate
        val viewModel = viewModel(store)

        viewModel.load("a")
        advanceUntilIdle()
        assertEquals("a", viewModel.state?.session?.id)

        viewModel.load("b")
        runCurrent()
        assertEquals(1, store.reads("b"))
        assertTrue("the superseded load is still running", viewModel.loading)

        viewModel.load("c")
        advanceUntilIdle()
        assertEquals("c", viewModel.state?.session?.id)
        assertFalse(viewModel.loading)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("a late callback must not replace the shown session", "c", viewModel.state?.session?.id)
        assertNull(viewModel.error)
    }

    @Test
    fun savedDraftIsRestoredWithoutRewritingIt() = viewModelTest {
        val store = FakePracticeStore()
        store.sessions["s"] = session(
            "s",
            listOf(single("q1"), single("q2")),
            draft = PracticeDraft(selectedKeys = listOf("B")),
            draftRevision = 3L
        )
        val viewModel = viewModel(store)

        viewModel.load("s")
        advanceUntilIdle()

        assertFalse(viewModel.loading)
        assertEquals("saved selection must be shown again", listOf("B"), viewModel.draft.selectedKeys)
        assertEquals(listOf("q1", "q2"), viewModel.state?.session?.questions?.map { it.questionId })
        assertEquals("loading must not write the draft", 0, store.draftSaveCalls)
        assertEquals(3L, store.sessions.getValue("s").draftRevision)
    }

    @Test
    fun reentryKeepsUnsavedInputInsteadOfReplacingIt() = viewModelTest {
        val store = FakePracticeStore()
        store.sessions["s"] = session("s", listOf(single("q1")))
        val viewModel = viewModel(store)

        viewModel.load("s")
        advanceUntilIdle()
        viewModel.selectOption("B")
        assertEquals(listOf("B"), viewModel.draft.selectedKeys)

        // Rotation / re-entry re-invokes load() on the surviving ViewModel.
        viewModel.load("s")
        assertEquals("re-entry must not drop the pending input", listOf("B"), viewModel.draft.selectedKeys)
        advanceUntilIdle()

        assertEquals(1, store.reads("s"))
        assertEquals(1, store.draftSaveCalls)
        assertEquals(listOf("B"), store.lastSavedDraft?.selectedKeys)
    }

    @Test
    fun restoredFeedbackIsShownWithoutResubmitting() = viewModelTest {
        val store = FakePracticeStore()
        val question = single("q1")
        store.sessions["s"] = session(
            "s",
            listOf(question, single("q2")),
            phase = PracticePhases.FEEDBACK,
            draftRevision = 1L
        )
        store.answerMap["s:0"] = recorded(
            sessionId = "s",
            position = 0,
            questionId = "q1",
            kind = PracticeAnswerKinds.AUTOMATIC,
            result = PracticeResults.CORRECT,
            userAnswer = PracticeUserAnswer(selectedKeys = listOf("A"))
        )
        val viewModel = viewModel(store)

        viewModel.load("s")
        advanceUntilIdle()

        assertFalse(viewModel.loading)
        assertEquals(PracticePhases.FEEDBACK, viewModel.state?.session?.phase)
        assertEquals(PracticeResults.CORRECT, viewModel.state?.feedback?.result)
        assertEquals(listOf("A"), viewModel.state?.feedback?.userAnswer?.selectedKeys)
        assertTrue("feedback must not reopen an empty draft", viewModel.draft.selectedKeys.isEmpty())
        assertEquals("q1", viewModel.state?.currentQuestion?.questionId)

        viewModel.submitAutomatic()
        advanceUntilIdle()

        assertEquals("a restored feedback position must not be submitted again", 0, store.submitCalls)
        assertEquals(1, store.answerMap.size)
    }

    @Test
    fun restoredCompletedSessionShowsSavedSummary() = viewModelTest {
        val store = FakePracticeStore()
        store.sessions["done"] = session(
            "done",
            listOf(single("q1"), single("q2")),
            currentIndex = 2,
            phase = PracticePhases.COMPLETED,
            status = PracticeStatuses.COMPLETED,
            draftRevision = 2L
        )
        store.answerMap["done:0"] = recorded(
            sessionId = "done",
            position = 0,
            questionId = "q1",
            kind = PracticeAnswerKinds.AUTOMATIC,
            result = PracticeResults.CORRECT,
            userAnswer = PracticeUserAnswer(selectedKeys = listOf("A"))
        )
        store.answerMap["done:1"] = recorded(
            sessionId = "done",
            position = 1,
            questionId = "q2",
            kind = PracticeAnswerKinds.SKIPPED,
            result = PracticeResults.SKIPPED,
            userAnswer = PracticeUserAnswer()
        )
        val viewModel = viewModel(store)

        viewModel.load("done")
        advanceUntilIdle()

        assertFalse(viewModel.loading)
        assertEquals(PracticePhases.COMPLETED, viewModel.state?.session?.phase)
        assertEquals(PracticeStatuses.COMPLETED, viewModel.state?.session?.status)
        assertNull(viewModel.state?.currentQuestion)
        assertEquals(2, viewModel.state?.summary?.answered)
        assertEquals(1, viewModel.state?.summary?.automaticCorrect)
        assertEquals(1, viewModel.state?.summary?.skipped)
        assertEquals(0, store.submitCalls)
    }

    @Test
    fun duplicateSubmitRecordsOnlyOneAnswer() = viewModelTest {
        val store = FakePracticeStore()
        store.sessions["s"] = session("s", listOf(single("q1")))
        val viewModel = viewModel(store)

        viewModel.load("s")
        advanceUntilIdle()
        viewModel.selectOption("A")
        viewModel.submitAutomatic()
        viewModel.submitAutomatic()
        advanceUntilIdle()

        assertEquals(1, store.submitCalls)
        assertEquals(1, store.answerMap.size)
        assertEquals(PracticePhases.FEEDBACK, viewModel.state?.session?.phase)
        assertNotNull(viewModel.state?.feedback)
        assertFalse("feedback must finish with saving cleared", viewModel.saving)
    }

    @Test
    fun correctionSavesDraftBeforeOpeningAndDoesNotSubmitAnswer() = viewModelTest {
        val store = FakePracticeStore()
        store.sessions["s"] = session("s", listOf(single("q1")))
        val vm = viewModel(store)
        vm.load("s")
        advanceUntilIdle()
        vm.selectOption("B")
        var opened = ""
        vm.requestCorrection { opened = it }
        assertEquals("", opened)
        assertTrue(vm.exiting)
        advanceUntilIdle()
        assertEquals("q1", opened)
        assertEquals(listOf("B"), store.sessions.getValue("s").draft.selectedKeys)
        assertEquals(0, store.submitCalls)
        assertTrue(store.answerMap.isEmpty())
        assertEquals(PracticePhases.ANSWERING, vm.state?.session?.phase)
    }

    @Test
    fun correctionDraftFailureKeepsInputAndDoesNotNavigate() = viewModelTest {
        val store = FakePracticeStore()
        store.sessions["s"] = session("s", listOf(single("q1")))
        val vm = viewModel(store)
        vm.load("s")
        advanceUntilIdle()
        vm.selectOption("B")
        store.failDraftSave = true
        var opened = false
        vm.requestCorrection { opened = true }
        advanceUntilIdle()
        assertFalse(opened)
        assertTrue(vm.draftSaveFailed)
        assertEquals(listOf("B"), vm.draft.selectedKeys)
        assertFalse(vm.exiting)
        store.failDraftSave = false
        vm.requestCorrection { opened = true }
        advanceUntilIdle()
        assertTrue(opened)
    }

    @Test
    fun correctionIsBlockedWhileSubmittingOrSavingExitDraft() = viewModelTest {
        val store = FakePracticeStore()
        store.sessions["s"] = session("s", listOf(single("q1")))
        val vm = viewModel(store)
        vm.load("s")
        advanceUntilIdle()
        vm.selectOption("A")
        vm.submitAutomatic()
        var opened = 0
        vm.requestCorrection { opened++ }
        assertEquals(0, opened)
        advanceUntilIdle()
        vm.requestCorrection { opened++ }
        assertEquals(1, opened)
        assertEquals(1, store.answerMap.size)
        assertEquals(PracticePhases.FEEDBACK, vm.state?.session?.phase)

        val fresh = viewModel(store)
        store.sessions["t"] = session("t", listOf(single("q2")))
        fresh.load("t")
        advanceUntilIdle()
        fresh.requestCorrection { opened++ }
        fresh.requestCorrection { opened++ }
        advanceUntilIdle()
        assertEquals(2, opened)
    }

    @Test
    fun feedbackNavigationIsBlockedWhileAdvancing() = viewModelTest {
        val store = FakePracticeStore()
        store.sessions["s"] = session("s", listOf(single("q1"), single("q2")))
        val vm = viewModel(store)
        vm.load("s")
        advanceUntilIdle()
        vm.selectOption("A")
        vm.submitAutomatic()
        advanceUntilIdle()
        vm.next()
        var opened = false
        vm.requestCorrection { opened = true }
        vm.requestExit { opened = true }
        assertFalse(opened)
        advanceUntilIdle()
        assertFalse(opened)
        assertEquals(1, vm.state?.session?.currentIndex)
    }

    @Test
    fun skipGoesStraightToNextAndLastSkipCompletesWithoutEffectiveAnswers() = viewModelTest {
        val store = FakePracticeStore()
        store.sessions["s"] = session("s", listOf(single("q1"), single("q2")))
        val vm = viewModel(store)
        vm.load("s")
        advanceUntilIdle()
        vm.skip()
        vm.skip()
        advanceUntilIdle()
        assertEquals(1, vm.state?.session?.currentIndex)
        assertEquals(PracticePhases.ANSWERING, vm.state?.session?.phase)
        assertNull(vm.state?.feedback)
        assertEquals(1, store.answerMap.size)
        vm.skip()
        advanceUntilIdle()
        assertEquals(PracticePhases.COMPLETED, vm.state?.session?.phase)
        assertEquals(0, vm.state?.summary?.totals?.effective)
        assertFalse(vm.saving)
    }

    @Test
    fun interruptedSkipFeedbackResumesStraightToNextWithoutAnotherAnswer() = viewModelTest {
        val store = FakePracticeStore()
        store.sessions["s"] = session("s", listOf(single("q1"), single("q2")), phase = PracticePhases.FEEDBACK)
        store.answerMap["s:0"] = recorded("s", 0, "q1", PracticeAnswerKinds.SKIPPED,
            PracticeResults.SKIPPED, PracticeUserAnswer())
        val vm = viewModel(store)
        vm.load("s")
        advanceUntilIdle()
        assertEquals(1, vm.state?.session?.currentIndex)
        assertEquals(PracticePhases.ANSWERING, vm.state?.session?.phase)
        assertNull(vm.state?.feedback)
        assertEquals(0, store.submitCalls)
        assertFalse(vm.saving)
    }

    @Test
    fun failedSkipAdvanceCanRetryWithoutDuplicateOrReferenceReveal() = viewModelTest {
        val store = FakePracticeStore()
        store.sessions["s"] = session("s", listOf(single("q1"), single("q2")))
        val vm = viewModel(store)
        vm.load("s")
        advanceUntilIdle()
        store.failAdvance = true
        vm.skip()
        advanceUntilIdle()
        assertEquals(PracticeAnswerKinds.SKIPPED, vm.state?.feedback?.kind)
        assertFalse(vm.draft.referenceRevealed)
        assertNotNull(vm.message)
        store.failAdvance = false
        vm.next()
        advanceUntilIdle()
        assertEquals(1, vm.state?.session?.currentIndex)
        assertEquals(1, store.submitCalls)
        assertEquals(1, store.answerMap.size)
    }

    @Test
    fun peekIsLockedWrongFeedbackAndPreventsFollowingSkipOrSubmit() = viewModelTest {
        val store = FakePracticeStore()
        store.sessions["s"] = session("s", listOf(single("q1")))
        val vm = viewModel(store)
        vm.load("s")
        advanceUntilIdle()
        vm.peek()
        vm.skip()
        vm.submitAutomatic()
        var exited = false
        vm.requestExit { exited = true }
        assertFalse(exited)
        advanceUntilIdle()
        assertEquals(PracticeAnswerKinds.PEEKED, vm.state?.feedback?.kind)
        assertEquals(PracticeResults.FORGOTTEN, vm.state?.feedback?.result)
        assertEquals(PracticeTotals(0, 1), vm.state?.summary?.totals)
        vm.skip()
        vm.selectOption("A")
        vm.submitAutomatic()
        advanceUntilIdle()
        assertEquals(1, store.submitCalls)
        assertEquals(1, store.answerMap.size)
    }

    @Test
    fun skipRecoversWhenAdvanceCommittedButFirstReadBackFailed() = viewModelTest {
        val store = FakePracticeStore()
        store.sessions["s"] = session("s", listOf(single("q1"), single("q2")))
        val vm = viewModel(store)
        vm.load("s")
        advanceUntilIdle()
        store.failReadAfterAdvance = true
        vm.skip()
        advanceUntilIdle()
        assertEquals(1, vm.state?.session?.currentIndex)
        assertEquals(PracticePhases.ANSWERING, vm.state?.session?.phase)
        assertNull(vm.state?.feedback)
        assertEquals(1, store.submitCalls)
        assertEquals(1, store.answerMap.size)
        assertFalse(vm.saving)
    }

    @Test
    fun failedPeekKeepsDraftAndDoesNotRevealOrRecordAnswer() = viewModelTest {
        val store = FakePracticeStore()
        store.sessions["s"] = session("s", listOf(single("q1")))
        val vm = viewModel(store)
        vm.load("s")
        advanceUntilIdle()
        vm.selectOption("B")
        store.failSubmit = true
        vm.peek()
        advanceUntilIdle()
        assertEquals(PracticePhases.ANSWERING, vm.state?.session?.phase)
        assertNull(vm.state?.feedback)
        assertFalse(vm.draft.referenceRevealed)
        assertEquals(listOf("B"), vm.draft.selectedKeys)
        assertTrue(store.answerMap.isEmpty())
        assertNotNull(vm.message)
        assertFalse(vm.saving)
        store.failSubmit = false
        vm.peek()
        advanceUntilIdle()
        assertEquals(PracticeAnswerKinds.PEEKED, vm.state?.feedback?.kind)
        assertEquals(1, store.answerMap.size)
    }

    private fun viewModelTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun viewModel(store: FakePracticeStore): PracticeViewModel {
        val repository = PracticeRepository(
            libraryStore = FakeLibraryStore(),
            practiceStore = store,
            clock = { 5_000L },
            idFactory = { "generated-answer-id" }
        )
        return PracticeViewModel(applicationWithoutAndroidRuntime(), repository)
    }

    private fun single(id: String): PracticeQuestionSnapshot = snapshot(
        id = id,
        position = 0,
        type = QuestionTypes.SINGLE_CHOICE,
        options = listOf(QuizOption("A", "甲", markerIndex = 0), QuizOption("B", "乙", markerIndex = 1)),
        answers = listOf("A")
    )

    private fun snapshot(
        id: String,
        position: Int,
        type: String,
        options: List<QuizOption> = emptyList(),
        answers: List<String> = emptyList()
    ): PracticeQuestionSnapshot = PracticeQuestionSnapshot(
        questionId = id,
        libraryId = "lib",
        position = position,
        type = type,
        originalNumber = "${position + 1}",
        stem = "题干 $id",
        options = options,
        answers = answers,
        acceptableAnswers = if (type == QuestionTypes.FILL_BLANK) answers.map { listOf(it) } else emptyList(),
        referenceAnswer = null,
        editedAt = null
    )

    private fun session(
        id: String,
        questions: List<PracticeQuestionSnapshot>,
        currentIndex: Int = 0,
        phase: String = PracticePhases.ANSWERING,
        status: String = PracticeStatuses.ACTIVE,
        draft: PracticeDraft = PracticeDraft(),
        draftRevision: Long = 0L
    ): PracticeSessionRecord = PracticeSessionRecord(
        id = id,
        libraryId = "lib",
        mode = PracticeModes.SEQUENTIAL,
        status = status,
        questions = questions,
        currentIndex = currentIndex,
        phase = phase,
        draft = draft,
        draftRevision = draftRevision,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        completedAt = if (status == PracticeStatuses.COMPLETED) 2_000L else null
    )

    private fun recorded(
        sessionId: String,
        position: Int,
        questionId: String,
        kind: String,
        result: String,
        userAnswer: PracticeUserAnswer
    ): PracticeRecordedAnswer = PracticeRecordedAnswer(
        id = "answer-$sessionId-$position",
        sessionId = sessionId,
        questionId = questionId,
        position = position,
        kind = kind,
        result = result,
        userAnswer = userAnswer,
        submittedAt = 3_000L
    )

    /**
     * Allocates the Application without running its constructor: the ViewModel only stores it, and
     * a JVM test must not open a Room database or initialise PDFBox assets.
     */
    private fun applicationWithoutAndroidRuntime(): OfflineQuizApplication {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, OfflineQuizApplication::class.java) as OfflineQuizApplication
    }

    private class FakePracticeStore : PracticeStore {
        val sessions = linkedMapOf<String, PracticeSessionRecord>()
        val answerMap = linkedMapOf<String, PracticeRecordedAnswer>()
        private val readsBySession = mutableMapOf<String, Int>()
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        var readsToFail = 0
        var draftSaveCalls = 0
        var submitCalls = 0
        var lastSavedDraft: PracticeDraft? = null
        var failDraftSave = false
        var failAdvance = false
        var failReadAfterAdvance = false
        var failSubmit = false

        fun reads(sessionId: String): Int = readsBySession[sessionId] ?: 0

        override fun observeActiveSession(libraryId: String): Flow<PracticeSessionSummary?> = flowOf(null)

        override suspend fun activeSession(libraryId: String): PracticeSessionSummary? = null

        override suspend fun createSession(session: PracticeSessionRecord) {
            sessions[session.id] = session
        }

        override suspend fun session(id: String): PracticeSessionRecord? {
            readsBySession[id] = (readsBySession[id] ?: 0) + 1
            gates[id]?.await()
            if (readsToFail > 0) {
                readsToFail -= 1
                throw IOException("simulated read failure")
            }
            return sessions[id]
        }

        override suspend fun answers(sessionId: String): List<PracticeRecordedAnswer> =
            answerMap.values.filter { it.sessionId == sessionId }.sortedBy { it.position }

        override suspend fun answerAt(sessionId: String, position: Int): PracticeRecordedAnswer? =
            answerMap["$sessionId:$position"]

        override suspend fun submitAnswer(
            sessionId: String,
            expectedPosition: Int,
            answer: PracticeRecordedAnswer,
            referenceSnapshotJson: String
        ): PracticeSubmitResult? {
            submitCalls += 1
            if (failSubmit) return null
            val session = sessions[sessionId] ?: return null
            if (session.status != PracticeStatuses.ACTIVE) return null
            if (session.phase != PracticePhases.ANSWERING) return null
            if (session.currentIndex != expectedPosition) return null
            val key = "$sessionId:$expectedPosition"
            answerMap[key]?.let { return PracticeSubmitResult(it, alreadyRecorded = true) }
            answerMap[key] = answer
            sessions[sessionId] = session.copy(
                phase = PracticePhases.FEEDBACK,
                draft = PracticeDraft(),
                draftRevision = session.draftRevision + 1,
                updatedAt = answer.submittedAt
            )
            return PracticeSubmitResult(answer, alreadyRecorded = false)
        }

        override suspend fun advanceSession(
            sessionId: String,
            expectedPosition: Int,
            nextIndex: Int,
            completed: Boolean,
            now: Long
        ): PracticeSessionRecord? {
            if (failAdvance) return null
            val session = sessions[sessionId] ?: return null
            if (session.status != PracticeStatuses.ACTIVE) return null
            if (session.phase != PracticePhases.FEEDBACK) return null
            if (session.currentIndex != expectedPosition) return null
            if (answerMap["$sessionId:$expectedPosition"] == null) return null
            val updated = session.copy(
                currentIndex = nextIndex,
                phase = if (completed) PracticePhases.COMPLETED else PracticePhases.ANSWERING,
                status = if (completed) PracticeStatuses.COMPLETED else PracticeStatuses.ACTIVE,
                draft = PracticeDraft(),
                draftRevision = session.draftRevision + 1,
                updatedAt = now,
                completedAt = if (completed) now else null
            )
            sessions[sessionId] = updated
            if (failReadAfterAdvance) {
                readsToFail = 1
                failReadAfterAdvance = false
            }
            return updated
        }

        override suspend fun saveDraft(
            sessionId: String,
            position: Int,
            draft: PracticeDraft,
            revision: Long,
            now: Long
        ): DraftSaveOutcome {
            draftSaveCalls += 1
            if (failDraftSave) return DraftSaveOutcome.Failed("草稿保存失败，请重试")
            val session = sessions[sessionId] ?: return DraftSaveOutcome.Stale
            if (session.status != PracticeStatuses.ACTIVE) return DraftSaveOutcome.Stale
            if (session.phase != PracticePhases.ANSWERING) return DraftSaveOutcome.Stale
            if (session.currentIndex != position) return DraftSaveOutcome.Stale
            if (session.draftRevision >= revision) return DraftSaveOutcome.Stale
            sessions[sessionId] = session.copy(draft = draft, draftRevision = revision, updatedAt = now)
            lastSavedDraft = draft
            return DraftSaveOutcome.Saved
        }

        override suspend fun libraryStatsSnapshot(libraryId: String) =
            com.offlinequiz.app.model.PracticeStatsSnapshot(historyForLibrary(libraryId),
                com.offlinequiz.app.model.PracticeRoundCounts.fromSessions(sessions.values.filter { it.libraryId == libraryId }))

        override suspend fun historyForLibrary(libraryId: String): List<PracticeHistoryEntry> =
            answerMap.values
                .filter { sessions[it.sessionId]?.libraryId == libraryId }
                .map { it.toHistoryEntry() }

        override suspend fun historyForQuestion(
            libraryId: String,
            questionId: String
        ): List<PracticeHistoryEntry> =
            answerMap.values
                .filter {
                    it.questionId == questionId && sessions[it.sessionId]?.libraryId == libraryId
                }
                .map { it.toHistoryEntry() }

        private fun PracticeRecordedAnswer.toHistoryEntry(): PracticeHistoryEntry =
            PracticeHistoryEntry(
                answer = this,
                referenceSnapshot = null,
                sessionSource = sessions[sessionId]?.source ?: PracticeSources.NORMAL
            )
    }

    private class FakeLibraryStore : LibraryStore {
        override fun observeSummaries(): Flow<List<LibrarySummary>> = flowOf(emptyList())
        override fun observeLibrary(id: String): Flow<LibraryRecord?> = flowOf(null)
        override fun observeQuestions(libraryId: String): Flow<List<StoredQuestion>> = flowOf(emptyList())
        override suspend fun questions(libraryId: String): List<StoredQuestion> = emptyList()
        override suspend fun library(id: String): LibraryRecord? = null
        override suspend fun question(id: String): StoredQuestion? = null
        override suspend fun createLibrary(library: LibraryRecord, questions: List<StoredQuestion>) = Unit
        override suspend fun renameLibrary(id: String, name: String, updatedAt: Long) = Unit
        override suspend fun saveQuestion(question: StoredQuestion, updatedAt: Long) = Unit
        override suspend fun libraryIds(): List<String> = emptyList()
    }
}
