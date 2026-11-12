package com.offlinequiz.app.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlinequiz.app.OfflineQuizApplication
import com.offlinequiz.app.data.AdvancePracticeOutcome
import com.offlinequiz.app.data.DraftSaveOutcome
import com.offlinequiz.app.data.PracticeRepository
import com.offlinequiz.app.data.SubmitPracticeOutcome
import com.offlinequiz.app.model.PracticeDraft
import com.offlinequiz.app.model.PracticeJudge
import com.offlinequiz.app.model.PracticePhases
import com.offlinequiz.app.model.PracticeResultSummary
import com.offlinequiz.app.model.PracticeState
import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.model.ShortAnswerMatchResult
import com.offlinequiz.app.model.ShortAnswerTextMatch
import com.offlinequiz.app.model.PracticeAnswerKinds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PracticeViewModel(
    application: Application,
    private val repository: PracticeRepository
) : AndroidViewModel(application) {

    // Production wiring; kept as a secondary constructor so the entry logic can be exercised on
    // the JVM with an injected repository instead of a real database-backed application.
    constructor(application: Application) : this(
        application,
        (application as OfflineQuizApplication).practiceRepository
    )

    private var loadJob: Job? = null
    private var draftJob: Job? = null
    private var loadedSessionId: String? = null
    private var requestedSessionId: String? = null
    private var loadingSessionId: String? = null
    private var loadGeneration: Long = 0L
    private var draftRevision: Long = 0L

    var state by mutableStateOf<PracticeState?>(null)
        private set

    var draft by mutableStateOf(PracticeDraft())
        private set

    // Nothing is in flight when the ViewModel is created. "Showing progress for the first frame"
    // is handled by the screen's state == null branch; this flag only tracks a real load task.
    var loading by mutableStateOf(false)
        private set

    var saving by mutableStateOf(false)
        private set

    var exiting by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    var draftSaveFailed by mutableStateOf(false)
        private set

    /**
     * Display-only text overlap for the current short-answer context. Computed outside Compose
     * whenever the draft/reference/feedback changes; never used for judging or statistics.
     * Null means "not applicable" (other type, not revealed yet, or non-self-assessment feedback).
     */
    var shortAnswerMatch by mutableStateOf<ShortAnswerMatchResult?>(null)
        private set

    /**
     * Entry point of the practice screen. Re-entrance is decided by the running [loadJob], never by
     * [loading]. A newly persisted single review can use its prepared snapshot directly.
     */
    fun load(sessionId: String, initialState: PracticeState? = null) {
        requestedSessionId = sessionId
        if (state == null && initialState?.session?.id == sessionId) {
            loadedSessionId = sessionId
            applyState(initialState)
            return
        }
        startLoad(sessionId)
    }

    /** Retries the last [load] after a missing session or a read failure. */
    fun retry() {
        val sessionId = requestedSessionId ?: return
        startLoad(sessionId)
    }

    private fun startLoad(sessionId: String) {
        if (loadedSessionId == sessionId && state != null) return
        // Keep the existing protection: never replace the screen with a read while a commit or an
        // exit is saving the current input.
        if (saving || exiting) return
        val running = loadJob
        if (running != null && running.isActive && loadingSessionId == sessionId) return
        // A request for another session supersedes the previous one; the generation check below
        // makes sure a late callback cannot replace the session now being shown.
        loadJob?.cancel()
        val generation = loadGeneration + 1
        loadGeneration = generation
        loadingSessionId = sessionId
        loading = true
        error = null
        loadJob = viewModelScope.launch {
            try {
                val loaded = repository.loadState(sessionId)
                if (generation != loadGeneration) return@launch
                if (loaded == null) {
                    error = LOAD_FAILED_MESSAGE
                    return@launch
                }
                loadedSessionId = sessionId
                if (loaded.session.phase == PracticePhases.FEEDBACK &&
                    loaded.feedback?.advancesWithoutFeedback == true) {
                    saving = true
                    try { advanceWithoutFeedback(loaded) } finally { saving = false }
                } else {
                    applyState(loaded)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == loadGeneration) error = LOAD_FAILED_MESSAGE
            } finally {
                if (generation == loadGeneration) {
                    loading = false
                    loadingSessionId = null
                }
            }
        }
    }

    fun selectOption(key: String) {
        val current = answeringState() ?: return
        val question = current.currentQuestion ?: return
        if (question.type == QuestionTypes.SINGLE_CHOICE || question.type == QuestionTypes.TRUE_FALSE) {
            draft = draft.copy(selectedKeys = listOf(key))
        } else if (question.type == QuestionTypes.MULTIPLE_CHOICE) {
            val selected = draft.selectedKeys.map { it.uppercase() }.toMutableSet()
            val normalized = key.uppercase()
            if (!selected.add(normalized)) selected.remove(normalized)
            draft = draft.copy(
                selectedKeys = question.options.map { it.key.uppercase() }.filter { it in selected }.distinct()
            )
        } else {
            return
        }
        scheduleDraftSave()
    }

    fun selectTrueFalse(trueAnswer: Boolean) {
        selectOption(if (trueAnswer) "√" else "×")
    }

    fun updateBlank(index: Int, value: String) {
        val current = answeringState() ?: return
        val question = current.currentQuestion ?: return
        if (index !in question.answers.indices) return
        val values = draft.blankAnswers.toMutableList()
        while (values.size < question.answers.size) values.add("")
        values[index] = value
        draft = draft.copy(blankAnswers = values)
        scheduleDraftSave()
    }

    fun updateShortText(value: String) {
        if (answeringState() == null) return
        draft = draft.copy(text = value)
        refreshShortAnswerMatch()
        scheduleDraftSave()
    }

    fun revealReference() {
        val current = answeringState() ?: return
        val question = current.currentQuestion ?: return
        if (question.type != QuestionTypes.FILL_BLANK && question.type != QuestionTypes.SHORT_ANSWER) return
        if (draft.referenceRevealed) return
        draft = draft.copy(referenceRevealed = true)
        refreshShortAnswerMatch()
        scheduleDraftSave()
    }

    fun canSubmitAutomatic(): Boolean {
        val current = state ?: return false
        val question = current.currentQuestion ?: return false
        if (current.session.phase != PracticePhases.ANSWERING || saving) return false
        if (draft.referenceRevealed) return false
        return PracticeJudge.isAutomaticType(question.type) && PracticeJudge.hasAnswer(question, draft)
    }

    fun canSelfAssess(): Boolean {
        val current = state ?: return false
        val question = current.currentQuestion ?: return false
        if (current.session.phase != PracticePhases.ANSWERING || saving) return false
        return (question.type == QuestionTypes.FILL_BLANK || question.type == QuestionTypes.SHORT_ANSWER) &&
            draft.referenceRevealed
    }

    fun submitAutomatic() {
        val current = answeringState() ?: return
        val question = current.currentQuestion ?: return
        if (!PracticeJudge.isAutomaticType(question.type)) return
        val userAnswer = draft.toUserAnswer()
        if (!PracticeJudge.hasAnswer(question, draft)) {
            message = "请先作答"
            return
        }
        saving = true
        message = null
        draftJob?.cancel()
        viewModelScope.launch {
            val outcome = repository.submitAutomatic(
                current.session.id,
                current.session.currentIndex,
                userAnswer
            )
            finishSubmit(outcome, current.session.currentIndex)
        }
    }

    fun selfAssess(remembered: Boolean) {
        val current = answeringState() ?: return
        val question = current.currentQuestion ?: return
        if (!draft.referenceRevealed) return
        if (question.type != QuestionTypes.FILL_BLANK && question.type != QuestionTypes.SHORT_ANSWER) return
        saving = true
        message = null
        draftJob?.cancel()
        val userAnswer = draft.toUserAnswer()
        viewModelScope.launch {
            val outcome = repository.submitSelfAssessment(
                current.session.id,
                current.session.currentIndex,
                userAnswer,
                remembered
            )
            finishSubmit(outcome, current.session.currentIndex)
        }
    }

    fun peek() {
        val current = answeringState() ?: return
        val userAnswer = draft.toUserAnswer()
        saving = true
        message = null
        draftJob?.cancel()
        viewModelScope.launch {
            finishSubmit(repository.peek(current.session.id, current.session.currentIndex, userAnswer),
                current.session.currentIndex)
        }
    }

    private suspend fun advanceWithoutFeedback(current: PracticeState) {
        when (val outcome = repository.advance(current.session.id, current.session.currentIndex)) {
            is AdvancePracticeOutcome.Advanced -> applyState(outcome.state)
            is AdvancePracticeOutcome.Failed -> {
                // The write may have succeeded while its read-back failed. Reload before
                // offering retry, and allow a later screen re-entry to read again as well.
                loadedSessionId = null
                val recovered = repository.loadState(current.session.id)
                applyState(recovered ?: current)
                if (recovered == null || (recovered.session.currentIndex == current.session.currentIndex &&
                    recovered.session.phase == PracticePhases.FEEDBACK)) message = outcome.message
            }
        }
    }

    fun skip() {
        val current = answeringState() ?: return
        val userAnswer = draft.toUserAnswer()
        saving = true
        message = null
        draftJob?.cancel()
        viewModelScope.launch {
            val outcome = repository.skip(
                current.session.id,
                current.session.currentIndex,
                userAnswer
            )
            try {
                if (outcome is SubmitPracticeOutcome.Recorded &&
                    outcome.answer.kind == PracticeAnswerKinds.SKIPPED) {
                    handleSubmitOutcome(outcome, current.session.currentIndex)
                    state?.let { advanceWithoutFeedback(it) }
                } else {
                    handleSubmitOutcome(outcome, current.session.currentIndex)
                }
            } catch (_: Exception) {
                message = "跳过进度读取失败，请返回后重试"
            } finally { saving = false }
        }
    }

    fun next() {
        val current = state ?: return
        if (saving || current.session.phase != PracticePhases.FEEDBACK) return
        val position = current.session.currentIndex
        saving = true
        message = null
        viewModelScope.launch {
            if (current.feedback?.advancesWithoutFeedback == true) {
                try { advanceWithoutFeedback(current) } finally { saving = false }
                return@launch
            }
            when (val outcome = repository.advance(current.session.id, position)) {
                is AdvancePracticeOutcome.Advanced -> {
                    applyState(outcome.state)
                    saving = false
                }
                is AdvancePracticeOutcome.Failed -> {
                    message = outcome.message
                    saving = false
                }
            }
        }
    }

    fun requestCorrection(onOpenEditor: (String) -> Unit) {
        val current = state ?: return
        val question = current.currentQuestion ?: return
        if (saving || exiting || loading) return
        requestExit { onOpenEditor(question.questionId) }
    }

    fun requestExit(onExit: () -> Unit) {
        if (saving || exiting) return
        val current = state
        if (current == null || current.session.phase != PracticePhases.ANSWERING) {
            onExit()
            return
        }
        draftJob?.cancel()
        exiting = true
        draftRevision += 1
        val revision = draftRevision
        val position = current.session.currentIndex
        viewModelScope.launch {
            val result = repository.saveDraft(current.session.id, position, draft, revision)
            exiting = false
            when (result) {
                is DraftSaveOutcome.Saved, DraftSaveOutcome.Stale -> onExit()
                is DraftSaveOutcome.Failed -> {
                    draftSaveFailed = true
                    message = result.message
                }
            }
        }
    }

    fun consumeMessage() {
        message = null
    }

    private suspend fun finishSubmit(outcome: SubmitPracticeOutcome, position: Int) {
        try {
            handleSubmitOutcome(outcome, position)
            val current = state
            if (outcome is SubmitPracticeOutcome.Recorded &&
                current?.session?.phase == PracticePhases.FEEDBACK &&
                current.session.currentIndex == position &&
                current.feedback?.advancesWithoutFeedback == true
            ) {
                advanceWithoutFeedback(current)
            }
        } catch (_: Exception) {
            message = "作答已保存，但反馈读取失败，请返回题库重试"
        } finally {
            // Keep the input disabled until the read-back has been applied; otherwise an edit
            // accepted in this window would be silently replaced by the committed snapshot.
            saving = false
        }
    }

    private suspend fun handleSubmitOutcome(outcome: SubmitPracticeOutcome, position: Int) {
        when (outcome) {
            is SubmitPracticeOutcome.Recorded -> {
                val current = state
                val loaded = current?.let { repository.loadState(it.session.id) }
                if (loaded != null) {
                    applyState(loaded)
                } else if (current != null) {
                    val answers = (current.answers.filterNot { it.position == position } + outcome.answer)
                        .sortedBy { it.position }
                    val session = current.session.copy(
                        phase = PracticePhases.FEEDBACK,
                        draft = PracticeDraft(),
                        draftRevision = current.session.draftRevision + 1
                    )
                    state = current.copy(
                        session = session,
                        feedback = outcome.answer,
                        answers = answers,
                        summary = PracticeResultSummary.fromAnswers(session.total, answers)
                    )
                    draft = PracticeDraft()
                }
                if (outcome.alreadyRecorded) {
                    message = "该题已提交过，显示已保存的结果"
                }
                draftSaveFailed = false
                refreshShortAnswerMatch()
            }
            is SubmitPracticeOutcome.Rejected -> message = outcome.message
            is SubmitPracticeOutcome.Failed -> message = outcome.message
        }
    }

    private fun applyState(loaded: PracticeState) {
        state = loaded
        draft = loaded.session.draft
        draftRevision = loaded.session.draftRevision
        draftSaveFailed = false
        refreshShortAnswerMatch()
    }

    /**
     * Recomputes the display-only short-answer overlap for the current context. Called on state
     * changes instead of from composition, and it reads the fixed session snapshot so editing the
     * library can never change the reference used here.
     */
    private fun refreshShortAnswerMatch() {
        val current = state
        val question = current?.currentQuestion
        if (current == null || question == null || question.type != QuestionTypes.SHORT_ANSWER) {
            shortAnswerMatch = null
            return
        }
        shortAnswerMatch = when {
            current.session.phase == PracticePhases.ANSWERING && draft.referenceRevealed ->
                ShortAnswerTextMatch.match(draft.text, question.referenceAnswer)
            current.session.phase == PracticePhases.FEEDBACK &&
                current.feedback?.kind == PracticeAnswerKinds.SELF_ASSESSMENT ->
                ShortAnswerTextMatch.match(
                    current.feedback?.userAnswer?.text.orEmpty(),
                    question.referenceAnswer
                )
            else -> null
        }
    }

    private fun answeringState(): PracticeState? {
        val current = state ?: return null
        if (current.session.phase != PracticePhases.ANSWERING || saving || exiting) return null
        return current
    }

    private fun scheduleDraftSave() {
        val current = state ?: return
        if (current.session.phase != PracticePhases.ANSWERING || saving || exiting) return
        draftRevision += 1
        val revision = draftRevision
        val position = current.session.currentIndex
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(350)
            when (val result = repository.saveDraft(current.session.id, position, draft, revision)) {
                is DraftSaveOutcome.Saved -> draftSaveFailed = false
                is DraftSaveOutcome.Stale -> Unit
                is DraftSaveOutcome.Failed -> {
                    if (revision == draftRevision) {
                        draftSaveFailed = true
                        message = result.message
                    }
                }
            }
        }
    }

    private companion object {
        const val LOAD_FAILED_MESSAGE = "练习读取失败，请返回题库后重试"
    }
}
