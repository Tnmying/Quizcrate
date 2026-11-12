package com.offlinequiz.app.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlinequiz.app.OfflineQuizApplication
import com.offlinequiz.app.data.RepositoryException
import com.offlinequiz.app.model.LibrarySummary
import com.offlinequiz.app.model.PracticeSessionSummary
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryListViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as OfflineQuizApplication
    private val repository = app.repository

    var librariesLoading by mutableStateOf(true)
        private set
    var librariesError by mutableStateOf<String?>(null)
        private set
    private val reload = MutableStateFlow(0)
    fun retryLibraries() { reload.value++ }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val libraries: StateFlow<List<LibrarySummary>> = reload.flatMapLatest {
        repository.observeSummaries()
            .onStart { librariesLoading = true; librariesError = null }
            .onEach { librariesLoading = false }
            .catch { librariesLoading = false; librariesError = "题库读取失败，请重试"; emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun latestPractice(libraries: List<LibrarySummary>): PracticeSessionSummary? =
        withContext(Dispatchers.IO) {
            com.offlinequiz.app.model.latestUnfinishedPractice(
                libraries.mapNotNull { app.practiceRepository.activeSession(it.library.id) }
            )
        }

    suspend fun learningStats(libraries: List<LibrarySummary>) = withContext(Dispatchers.IO) {
        libraries.associate { it.library.id to app.practiceStatsRepository.libraryReport(it.library.id).overview }
    }

    var message by mutableStateOf<String?>(null)
        private set

    var deleting by mutableStateOf(false)
        private set

    var deleteError by mutableStateOf<String?>(null)
        private set

    fun clearDeleteError() { deleteError = null }

    fun deleteLibrary(id: String, onDone: () -> Unit) {
        if (deleting) return
        deleting = true
        deleteError = null
        viewModelScope.launch {
            try {
                when (val result = app.maintenanceRepository.deleteLibrary(id)) {
                    is com.offlinequiz.app.data.MaintenanceOutcome.Done -> {
                        message = result.message
                        onDone()
                    }
                    is com.offlinequiz.app.data.MaintenanceOutcome.Failed -> {
                        deleteError = result.message
                        message = result.message
                    }
                }
            } finally { deleting = false }
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // Reclaim folders left by an interrupted import, keeping any folder still referenced.
            runCatching {
                app.pdfStore.cleanupOrphans(repository.allLibraryIds().toSet())
            }
        }
    }

    fun renameLibrary(id: String, name: String) {
        if (deleting) return
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

    fun consumeMessage() {
        message = null
    }
}
