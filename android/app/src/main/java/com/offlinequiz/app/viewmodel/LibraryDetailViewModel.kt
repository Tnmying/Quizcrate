package com.offlinequiz.app.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlinequiz.app.OfflineQuizApplication
import com.offlinequiz.app.data.LibraryRepository
import com.offlinequiz.app.data.PracticeRepository
import com.offlinequiz.app.data.PracticeStatsRepository
import com.offlinequiz.app.data.RepositoryException
import com.offlinequiz.app.data.StartPracticeOutcome
import com.offlinequiz.app.model.LibraryRecord
import com.offlinequiz.app.model.PracticeLibraryReport
import com.offlinequiz.app.model.PracticeSessionSummary
import com.offlinequiz.app.model.StoredQuestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryDetailViewModel(
    application: Application,
    private val repository: LibraryRepository,
    private val practiceRepository: PracticeRepository,
    private val statsRepository: PracticeStatsRepository,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val maintenance: com.offlinequiz.app.data.LibraryMaintenanceRepository? = null
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        (application as OfflineQuizApplication).repository,
        (application as OfflineQuizApplication).practiceRepository,
        (application as OfflineQuizApplication).practiceStatsRepository,
        maintenance = (application as OfflineQuizApplication).maintenanceRepository
    )

    private val app = application as OfflineQuizApplication
    private var loadedId: String? = null
    private var statsJob: Job? = null
    private var activeJob: Job? = null
    private var statsGeneration: Long = 0L

    var library by mutableStateOf<LibraryRecord?>(null)
        private set

    var questions by mutableStateOf<List<StoredQuestion>>(emptyList())
        private set

    var loading by mutableStateOf(true)
        private set

    var activePractice by mutableStateOf<PracticeSessionSummary?>(null)
        private set

    var practiceLoaded by mutableStateOf(false)
        private set

    var practiceLoadFailed by mutableStateOf(false)
        private set

    var startingPractice by mutableStateOf(false)
        private set

    var startedPracticeId by mutableStateOf<String?>(null)
        private set

    var stats by mutableStateOf<PracticeLibraryReport?>(null)
        private set

    var statsLoading by mutableStateOf(false)
        private set

    var statsError by mutableStateOf<String?>(null)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    var maintaining by mutableStateOf(false)
        private set

    var maintenanceError by mutableStateOf<String?>(null)
        private set

    fun clearMaintenanceError() { maintenanceError = null }

    fun endRound(expectedSessionId: String, onDone: () -> Unit) = maintain(onDone) { id, actions ->
        actions.endRound(id, expectedSessionId)
    }

    fun resetStatistics(onDone: () -> Unit) = maintain(onDone) { id, actions -> actions.resetPractice(id) }

    private fun maintain(onDone: () -> Unit,
        action: suspend (String, com.offlinequiz.app.data.LibraryMaintenanceRepository) -> com.offlinequiz.app.data.MaintenanceOutcome
    ) {
        val id = loadedId ?: return
        val actions = maintenance ?: return
        if (maintaining || startingPractice) return
        maintaining = true
        maintenanceError = null
        viewModelScope.launch {
            try {
                when (val result = action(id, actions)) {
                    is com.offlinequiz.app.data.MaintenanceOutcome.Done -> {
                        message = result.message
                        activePractice = null
                        retryActivePractice()
                        stats = null
                        refreshStats(id)
                        onDone()
                    }
                    is com.offlinequiz.app.data.MaintenanceOutcome.Failed -> {
                        maintenanceError = result.message
                        message = result.message
                    }
                }
            } finally { maintaining = false }
        }
    }

    fun load(libraryId: String) {
        if (loadedId == libraryId) {
            // Returning from practice/history must not show stale counters.
            refreshStats(libraryId)
            return
        }
        loadedId = libraryId
        loading = true
        viewModelScope.launch {
            repository.observeLibrary(libraryId)
                .catch {
                    message = "读取题库失败，请返回后重试"
                    loading = false
                }
                .collect { record ->
                    library = record
                }
        }
        viewModelScope.launch {
            repository.observeQuestions(libraryId)
                .catch {
                    message = "读取题目失败，请返回后重试"
                    loading = false
                }
                .collect { list ->
                    questions = list
                    loading = false
                    // Question edits can change the current content version, so statistics are
                    // rebuilt whenever the stored questions change.
                    refreshStats(libraryId)
                }
        }
        observeActivePractice()
    }

    fun retryActivePractice() {
        if (loadedId == null) return
        practiceLoadFailed = false
        practiceLoaded = false
        activePractice = null
        observeActivePractice()
    }

    private fun observeActivePractice() {
        val libraryId = loadedId ?: return
        activeJob?.cancel()
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

    fun retryStats() {
        loadedId?.let { refreshStats(it) }
    }

    /**
     * Starts a normal round. While the active-session lookup has not succeeded, or a session is
     * known to exist, a plain call is refused; "start a new round" must pass [confirmedReplace].
     */
    fun startPractice(mode: String, confirmedReplace: Boolean = false) {
        val libraryId = loadedId ?: return
        if (startingPractice || maintaining) return
        if (!practiceLoaded || practiceLoadFailed) {
            message = "正在确认现有练习状态，请稍候或重试"
            return
        }
        if (activePractice != null && !confirmedReplace) {
            message = "已有未完成练习，请使用“开始新一轮”确认"
            return
        }
        startingPractice = true
        viewModelScope.launch {
            try {
                when (val outcome = practiceRepository.start(libraryId, mode)) {
                    is StartPracticeOutcome.Started -> {
                        startedPracticeId = outcome.sessionId
                    }
                    is StartPracticeOutcome.NoEligible -> {
                        message = noEligibleMessage(outcome)
                    }
                    is StartPracticeOutcome.Failed -> {
                        message = outcome.message
                    }
                }
            } catch (_: Exception) {
                message = "创建练习失败，未写入任何会话"
            } finally {
                startingPractice = false
            }
        }
    }

    fun consumeStartedPractice() {
        startedPracticeId = null
    }

    private fun refreshStats(libraryId: String) {
        val generation = statsGeneration + 1
        statsGeneration = generation
        statsJob?.cancel()
        statsJob = viewModelScope.launch {
            statsLoading = true
            statsError = null
            try {
                val computed = withContext(computeDispatcher) {
                    statsRepository.libraryReport(libraryId)
                }
                if (generation == statsGeneration) stats = computed
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == statsGeneration) {
                    stats = null
                    statsError = "统计数据读取失败，请重试"
                }
            } finally {
                if (generation == statsGeneration) statsLoading = false
            }
        }
    }

    private fun noEligibleMessage(outcome: StartPracticeOutcome.NoEligible): String {
        val report = outcome.report
        return "暂无可练习题目：待校对 ${report.reviewExcluded} 题、结构异常 ${report.structureExcluded} 题、" +
            "未知题型 ${report.unsupportedTypeExcluded} 题。请先校对题目。"
    }

    fun renameLibrary(name: String) {
        val id = loadedId ?: return
        viewModelScope.launch {
            try {
                repository.renameLibrary(id, name)
                message = "题库名称已更新"
            } catch (e: RepositoryException) {
                message = e.message ?: "重命名失败"
            } catch (e: Exception) {
                message = "重命名失败，原名称保持不变"
            }
        }
    }

    fun pdfAvailable(): Boolean = app.pdfStore.resolve(library?.pdfRelativePath) != null

    fun consumeMessage() {
        message = null
    }
}
