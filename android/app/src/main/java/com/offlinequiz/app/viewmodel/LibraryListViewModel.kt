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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryListViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as OfflineQuizApplication
    private val repository = app.repository

    val libraries: StateFlow<List<LibrarySummary>> = repository.observeSummaries()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
