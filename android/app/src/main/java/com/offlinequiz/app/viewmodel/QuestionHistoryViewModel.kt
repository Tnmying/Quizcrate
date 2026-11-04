package com.offlinequiz.app.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlinequiz.app.OfflineQuizApplication
import com.offlinequiz.app.data.PracticeStatsRepository
import com.offlinequiz.app.model.PracticeQuestionHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-question history screen. Same first-load/retry rules as the other entry screens: the running
 * job is the re-entrance guard, and every path ends by clearing `loading`.
 */
class QuestionHistoryViewModel(
    application: Application,
    private val statsRepository: PracticeStatsRepository,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        (application as OfflineQuizApplication).practiceStatsRepository
    )

    private var loadJob: Job? = null
    private var loadingKey: String? = null
    private var loadGeneration: Long = 0L
    private var requestedLibraryId: String? = null
    private var requestedQuestionId: String? = null

    var history by mutableStateOf<PracticeQuestionHistory?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    fun load(libraryId: String, questionId: String) {
        requestedLibraryId = libraryId
        requestedQuestionId = questionId
        startLoad(libraryId, questionId)
    }

    fun retry() {
        val libraryId = requestedLibraryId ?: return
        val questionId = requestedQuestionId ?: return
        startLoad(libraryId, questionId)
    }

    private fun startLoad(libraryId: String, questionId: String) {
        val key = "$libraryId:$questionId"
        val running = loadJob
        if (running != null && running.isActive && loadingKey == key) return
        loadJob?.cancel()
        val generation = loadGeneration + 1
        loadGeneration = generation
        loadingKey = key
        loading = true
        error = null
        loadJob = viewModelScope.launch {
            try {
                val loaded = withContext(computeDispatcher) {
                    statsRepository.questionHistory(libraryId, questionId)
                }
                if (generation != loadGeneration) return@launch
                history = loaded
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == loadGeneration) {
                    history = null
                    error = "题目历史读取失败，请重试"
                }
            } finally {
                if (generation == loadGeneration) {
                    loading = false
                    loadingKey = null
                }
            }
        }
    }
}
