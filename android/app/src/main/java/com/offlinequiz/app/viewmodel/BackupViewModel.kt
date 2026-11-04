package com.offlinequiz.app.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlinequiz.app.OfflineQuizApplication
import com.offlinequiz.app.backup.BackupError
import com.offlinequiz.app.backup.BackupException
import com.offlinequiz.app.backup.BackupService
import com.offlinequiz.app.data.QuizDatabase
import com.offlinequiz.app.data.RoomBackupDataStore
import java.io.File

/**
 * Thin Android adapter: resolves SAF URIs only when the controller asks for them. Export opens the
 * target OutputStream after local generation + validation; no shared cancel flag or shared request
 * state remains here.
 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as OfflineQuizApplication
    private val service = BackupService(
        store = RoomBackupDataStore(app.database, app.pdfStore),
        workDir = File(app.cacheDir, "backup"),
        pdfRoot = app.filesDir,
        targetSchemaVersion = QuizDatabase.VERSION
    )
    private val controller = BackupController(service, viewModelScope)

    val state: BackupUiState get() = controller.state
    val leaveBlocked: Boolean get() = controller.leaveBlocked

    fun export(uri: Uri) {
        controller.export {
            app.contentResolver.openOutputStream(uri)
                ?: throw BackupException(BackupError.IO, "无法打开保存位置")
        }
    }

    fun prepare(uri: Uri) {
        controller.prepare {
            app.contentResolver.openInputStream(uri)
                ?: throw BackupException(BackupError.IO, "无法读取所选备份")
        }
    }

    fun confirm() = controller.confirm()

    fun cancelPreview() = controller.cancelPreview()

    fun tryLeave(): Boolean = controller.tryLeave()

    fun consumeLeaveBlocked() = controller.consumeLeaveBlocked()

    override fun onCleared() {
        controller.close()
        super.onCleared()
    }
}
