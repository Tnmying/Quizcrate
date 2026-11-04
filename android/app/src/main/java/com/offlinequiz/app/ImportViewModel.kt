package com.offlinequiz.app

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlinequiz.app.data.PdfCopyException
import com.offlinequiz.app.data.RepositoryException
import com.offlinequiz.app.data.UriPdfArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ImportUiState {
    /** Saved is a brief handoff to the new library; don't race that navigation with a back event. */
    val blocksBack: Boolean get() = this is Saving || this is Saved

    fun requestBack(onBack: () -> Unit): Boolean {
        if (blocksBack) return false
        onBack()
        return true
    }

    data object Idle : ImportUiState
    data object Parsing : ImportUiState
    data class Preview(
        val imported: ImportedPdf,
        val suggestedName: String,
        val saveError: String? = null
    ) : ImportUiState
    data object Saving : ImportUiState
    data class Saved(val libraryId: String) : ImportUiState
    data class Error(val message: String) : ImportUiState
}

class ImportViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as OfflineQuizApplication
    private var sourceUri: Uri? = null

    var state by mutableStateOf<ImportUiState>(ImportUiState.Idle)
        private set

    var libraryName by mutableStateOf("")
        private set

    fun importPdf(uri: Uri) {
        if (state is ImportUiState.Parsing || state is ImportUiState.Saving) return
        sourceUri = uri
        libraryName = ""
        state = ImportUiState.Parsing
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    PdfBoxAndroidParser.parse(getApplication(), uri)
                }
                val suggested = suggestedName(imported.fileName)
                libraryName = suggested
                state = ImportUiState.Preview(imported, suggested)
            } catch (e: PdfImportException) {
                state = ImportUiState.Error(e.message ?: "导入失败，请重试")
            } catch (e: Exception) {
                Log.e("OfflineQuiz", "PDF import failed", e)
                state = ImportUiState.Error("导入失败，请重试或换一个 PDF")
            }
        }
    }

    fun updateLibraryName(value: String) {
        libraryName = value.take(80)
    }

    /** Read the latest state at event time, even before Compose has rendered a Saving frame. */
    fun requestBack(onBack: () -> Unit) {
        state.requestBack(onBack)
    }

    fun saveLibrary() {
        val preview = state as? ImportUiState.Preview ?: return
        val uri = sourceUri
        if (uri == null) {
            state = preview.copy(saveError = "文件选择结果已失效，请重新选择 PDF")
            return
        }
        val name = libraryName.trim()
        if (name.isEmpty()) {
            state = preview.copy(saveError = "题库名称不能为空")
            return
        }
        state = ImportUiState.Saving
        viewModelScope.launch {
            try {
                val libraryId = withContext(Dispatchers.IO) {
                    app.repository.importLibrary(
                        name = name,
                        fileName = preview.imported.fileName,
                        pageCount = preview.imported.pageCount,
                        result = preview.imported.result,
                        archive = UriPdfArchive(app.pdfStore, app.contentResolver, uri)
                    )
                }
                state = ImportUiState.Saved(libraryId)
            } catch (e: RepositoryException) {
                state = preview.copy(saveError = e.message ?: "保存失败，题库未创建")
            } catch (e: PdfCopyException) {
                state = preview.copy(saveError = e.message ?: "保存 PDF 私有副本失败")
            } catch (e: Exception) {
                Log.e("OfflineQuiz", "library save failed", e)
                state = preview.copy(saveError = "保存失败，题库未创建")
            }
        }
    }

    fun reset() {
        state = ImportUiState.Idle
        libraryName = ""
        sourceUri = null
    }

    private fun suggestedName(fileName: String): String {
        val base = fileName.substringBeforeLast('.', fileName).trim()
        return (base.ifEmpty { "新题库" }).take(80)
    }
}
