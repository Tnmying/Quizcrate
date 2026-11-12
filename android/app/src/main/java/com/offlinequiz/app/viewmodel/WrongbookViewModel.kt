package com.offlinequiz.app.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlinequiz.app.OfflineQuizApplication
import com.offlinequiz.app.data.PracticeRepository
import com.offlinequiz.app.data.PracticeStatsRepository
import com.offlinequiz.app.data.StartPracticeOutcome
import com.offlinequiz.app.model.PracticeLibraryReport
import com.offlinequiz.app.model.PracticeQuestionStats
import com.offlinequiz.app.model.PracticeSessionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class WrongbookFilter { DUE, ALL }

/**
 * Four distinct states for the "is there an unfinished practice session?" question.
 * A failed lookup is never treated as "no session": starting a new round is blocked until a
 * successful read says otherwise.
 */
enum class ActivePracticeState { LOADING, FAILED, NONE, PRESENT }

/**
 * Read-only wrongbook/statistics screen state plus the "start a wrongbook round" action.
 *
 * Loading uses the same guarded pattern as the practice entry (running job + generation), never the
 * `loading` flag itself, so the first load cannot dead-lock and a failed read is retryable.
 *
 * Starting a round is additionally protected: [startPractice] never replaces an existing session.
 * With a known session it only opens a confirmation ([pendingStartMode]); the explicit
 * [confirmStartPractice] re-reads the active session and asks again if it changed.
 */
class WrongbookViewModel(
    application: Application,
    private val statsRepository: PracticeStatsRepository,
    private val practiceRepository: PracticeRepository,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        (application as OfflineQuizApplication).practiceStatsRepository,
        (application as OfflineQuizApplication).practiceRepository
    )

    private var loadJob: Job? = null
    private var activeJob: Job? = null
    private var loadingLibraryId: String? = null
    private var loadGeneration: Long = 0L
    private var requestedId: String? = null
    private var pendingExpectedActiveId: String? = null

    /**
     * One in-flight "confirm start" request. Cancel is only a request-invalidation until the
     * actual write starts; see [committing].
     */
    private data class ConfirmRequest(val token: Long, val mode: String, val libraryId: String)

    private var confirmRequest: ConfirmRequest? = null
    private var confirmJob: Job? = null
    private var confirmToken: Long = 0L

    var report by mutableStateOf<PracticeLibraryReport?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var filter by mutableStateOf(WrongbookFilter.DUE)
        private set

    var activePractice by mutableStateOf<PracticeSessionSummary?>(null)
        private set

    /** True only after a successful active-session read (a failure is not "no session"). */
    var practiceLoaded by mutableStateOf(false)
        private set

    var practiceLoadFailed by mutableStateOf(false)
        private set

    var starting by mutableStateOf(false)
        private set

    /**
     * True once the confirmed request has crossed the commit boundary and the repository write has
     * been issued. From here a cancel/back cannot undo it, so all exit paths are blocked and the
     * screen shows "creating".
     */
    var committing by mutableStateOf(false)
        private set

    var startingSingleReview by mutableStateOf(false)
        private set

    var startedPractice by mutableStateOf<StartPracticeOutcome.Started?>(null)
        private set

    val startedPracticeId: String? get() = startedPractice?.sessionId

    var firstUnpracticableId by mutableStateOf<String?>(null)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    /** Non-null while the "existing session" confirmation is on screen. */
    var pendingStartMode by mutableStateOf<String?>(null)
        private set

    val activePracticeState: ActivePracticeState
        get() = when {
            practiceLoadFailed -> ActivePracticeState.FAILED
            !practiceLoaded -> ActivePracticeState.LOADING
            activePractice != null -> ActivePracticeState.PRESENT
            else -> ActivePracticeState.NONE
        }

    val canStartDirectly: Boolean
        get() = activePracticeState == ActivePracticeState.NONE

    val due: List<PracticeQuestionStats> get() = report?.due.orEmpty()
    val wrongbook: List<PracticeQuestionStats> get() = report?.wrongbook.orEmpty()

    val visible: List<PracticeQuestionStats>
        get() = if (filter == WrongbookFilter.DUE) due else wrongbook

    val duePracticableCount: Int get() = due.count { it.practicable }

    fun load(libraryId: String) {
        requestedId = libraryId
        startLoad(libraryId)
        observeActivePractice(libraryId)
    }

    /** Retries the failed statistics read; the screen keeps its own retry button. */
    fun retry() {
        requestedId?.let { startLoad(it) }
    }

    /** Retries the active-session lookup after it failed, without touching statistics. */
    fun retryActivePractice() {
        requestedId?.let { observeActivePractice(it) }
    }

    fun selectFilter(value: WrongbookFilter) {
        filter = value
    }

    /**
     * Plain "start" action used by the visible buttons. It refuses while the active-session lookup
     * is unknown/failed and never replaces a known session; that requires [confirmStartPractice].
     */
    fun startPractice(mode: String) {
        if (starting) return
        if (requestedId == null) return
        val dueNow = due
        if (dueNow.isEmpty()) {
            message = "当前没有待复习的错题"
            return
        }
        val practicableDue = dueNow.filter { it.practicable }
        if (practicableDue.isEmpty()) {
            firstUnpracticableId = dueNow.firstOrNull()?.questionId
            message = "待复习的 ${dueNow.size} 道题当前都不可练习（待校对或结构异常）。请先校对题目。"
            return
        }
        when (activePracticeState) {
            ActivePracticeState.LOADING -> {
                message = "正在确认现有练习会话，请稍候…"
            }
            ActivePracticeState.FAILED -> {
                message = "未能确认现有练习会话，请重试后再开始"
            }
            ActivePracticeState.NONE -> beginStart(mode)
            ActivePracticeState.PRESENT -> {
                pendingStartMode = mode
                pendingExpectedActiveId = activePractice?.id
                message = null
            }
        }
    }

    /**
     * Explicit confirmation for "start a new round although a session exists". Re-reads the active
     * session first: if it changed since the dialog was shown, the confirmation is asked again.
     *
     * The whole request is guarded by a token. Confirm is a two-phase operation:
     * 1. CONFIRMING: the dialog stays open, [cancelPendingStart]/back/outside/continue invalidate the
     *    token and cancel the job; a late return from the re-read can no longer write.
     * 2. COMMITTING: after the token check immediately before the write, cancel is refused and all
     *    exit paths are blocked until the write finishes (state [committing]).
     */
    fun confirmStartPractice() {
        val mode = pendingStartMode ?: return
        val libraryId = requestedId ?: return
        if (starting) return
        val expectedActiveId = pendingExpectedActiveId
        val token = ++confirmToken
        confirmRequest = ConfirmRequest(token, mode, libraryId)
        starting = true
        committing = false
        message = null
        confirmJob = viewModelScope.launch {
            try {
                val fresh = try {
                    practiceRepository.activeSession(libraryId)
                } catch (e: CancellationException) {
                    // A cancelled confirmation must never be turned into a normal failure.
                    throw e
                } catch (_: Exception) {
                    if (isCurrentConfirm(token)) message = "无法确认现有练习会话，未创建任何会话"
                    return@launch
                }
                // Post-suspension authorization check: cancel may have invalidated us meanwhile.
                if (!isCurrentConfirm(token)) return@launch
                if (fresh?.id != expectedActiveId) {
                    pendingExpectedActiveId = fresh?.id
                    activePractice = fresh
                    message = "练习会话已变化，请确认最新会话后再次开始"
                    return@launch
                }
                if (!isCurrentConfirm(token)) return@launch

                // Commit boundary: from this point the request cannot be cancelled.
                committing = true
                pendingStartMode = null
                pendingExpectedActiveId = null
                createRound(mode, libraryId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isCurrentConfirm(token)) message = "创建错题重练失败，未写入任何会话"
            } finally {
                // Only the request that still owns the token may reset shared state; a late finally
                // from an invalidated or superseded request must not touch a newer one.
                if (isCurrentConfirm(token)) {
                    confirmRequest = null
                    confirmJob = null
                    committing = false
                    starting = false
                }
            }
        }
    }

    /**
     * Uniform cancel/close/continue rule for the confirmation dialog. Before the commit boundary it
     * invalidates the request (token + job); after it the action is refused with a visible message.
     */
    fun cancelPendingStart() {
        if (committing) {
            message = "正在创建新一轮，完成前不能取消"
            return
        }
        if (confirmRequest == null && pendingStartMode == null) return
        confirmRequest = null
        confirmJob?.cancel()
        confirmJob = null
        pendingStartMode = null
        pendingExpectedActiveId = null
        starting = false
        message = null
    }

    /** Feedback for the blocked back/continue paths while [committing]. */
    fun notifyCommitInProgress() {
        if (committing) message = if (startingSingleReview) "正在打开单题复习，请稍候"
            else "正在创建新一轮，完成前不能取消或离开"
    }

    /**
     * Single guard for **every** user-triggered navigation out of the wrongbook screen (back,
     * history, review/edit, error-state back). Behaviour:
     * - [committing]: refuse and show the existing Chinese message; the write keeps going.
     * - confirmation dialog open / re-read in flight: invalidate that request first, then allow
     *   leaving (a late return can no longer create or navigate).
     * - otherwise: allow.
     *
     * Successful creation is not routed through here: the auto-navigation to the new practice is a
     * completion path, not a user leave request.
     */
    fun tryLeaveScreen(): Boolean {
        if (committing) {
            notifyCommitInProgress()
            return false
        }
        if (confirmRequest != null || pendingStartMode != null) {
            cancelPendingStart()
        }
        return true
    }

    private fun isCurrentConfirm(token: Long): Boolean = confirmRequest?.token == token

    fun reviewQuestion(questionId: String) {
        val libraryId = requestedId ?: return
        if (starting || committing) return
        val selected = visible.firstOrNull { it.questionId == questionId } ?: return
        if (!selected.practicable) {
            message = "当前题目暂不可练习，请先在题目管理中处理"
            return
        }
        // Any pending full-round confirmation is invalidated before starting this isolated review.
        cancelPendingStart()
        startingSingleReview = true
        starting = true
        committing = true
        message = null
        viewModelScope.launch {
            try {
                when (val result = practiceRepository.startSingleReview(libraryId, questionId)) {
                    is StartPracticeOutcome.Started -> startedPractice = result
                    is StartPracticeOutcome.NoEligible -> message = "当前题目暂不可练习，请先在题目管理中处理"
                    is StartPracticeOutcome.Failed -> message = result.message
                }
            } finally {
                committing = false
                starting = false
                startingSingleReview = false
            }
        }
    }

    fun consumeStartedPractice() {
        startedPractice = null
    }

    fun consumeMessage() {
        message = null
    }

    private fun beginStart(mode: String) {
        val libraryId = requestedId ?: return
        if (starting) return
        starting = true
        message = null
        viewModelScope.launch {
            try {
                createRound(mode, libraryId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                message = "创建错题重练失败，未写入任何会话"
            } finally {
                starting = false
            }
        }
    }

    private suspend fun createRound(mode: String, libraryId: String) {
        val practicableDue = due.filter { it.practicable }
        if (practicableDue.isEmpty()) {
            message = "当前没有可练习的待复习错题"
            pendingStartMode = null
            pendingExpectedActiveId = null
            return
        }
        when (val outcome = practiceRepository.startWrongbook(
            libraryId = libraryId,
            mode = mode,
            dueQuestionIds = practicableDue.map { it.questionId }
        )) {
            is StartPracticeOutcome.Started -> {
                startedPractice = outcome
                firstUnpracticableId = null
                pendingStartMode = null
                pendingExpectedActiveId = null
            }
            is StartPracticeOutcome.NoEligible -> {
                firstUnpracticableId = due.firstOrNull()?.questionId
                message = "可练习的待复习题目为空：待校对 ${outcome.report.reviewExcluded} 题、" +
                    "结构异常 ${outcome.report.structureExcluded} 题。请先校对题目。"
                pendingStartMode = null
                pendingExpectedActiveId = null
            }
            is StartPracticeOutcome.Failed -> {
                message = outcome.message
            }
        }
    }

    private fun startLoad(libraryId: String) {
        val running = loadJob
        if (running != null && running.isActive && loadingLibraryId == libraryId) return
        loadJob?.cancel()
        val generation = loadGeneration + 1
        loadGeneration = generation
        loadingLibraryId = libraryId
        loading = true
        error = null
        loadJob = viewModelScope.launch {
            try {
                val computed = withContext(computeDispatcher) {
                    statsRepository.libraryReport(libraryId)
                }
                if (generation != loadGeneration) return@launch
                report = computed
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == loadGeneration) {
                    report = null
                    error = "统计数据读取失败，请重试"
                }
            } finally {
                if (generation == loadGeneration) {
                    loading = false
                    loadingLibraryId = null
                }
            }
        }
    }

    private fun observeActivePractice(libraryId: String) {
        activeJob?.cancel()
        practiceLoadFailed = false
        practiceLoaded = false
        activePractice = null
        // A pending confirmation is intentionally kept: a reload/rotation must not silently
        // dismiss a dialog the user is looking at (cancel is the explicit way to drop it).
        activeJob = viewModelScope.launch {
            practiceRepository.observeActiveSession(libraryId)
                .catch {
                    activePractice = null
                    practiceLoaded = false
                    practiceLoadFailed = true
                }
                .collect { summary ->
                    activePractice = summary
                    practiceLoaded = true
                    practiceLoadFailed = false
                }
        }
    }
}
