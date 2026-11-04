package com.offlinequiz.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DeletedLibrary(val pdfRelativePath: String?)

interface LibraryMaintenanceStore {
    suspend fun endRound(libraryId: String, expectedSessionId: String, now: Long): Boolean
    suspend fun resetPractice(libraryId: String): Boolean
    suspend fun deleteLibrary(libraryId: String): DeletedLibrary?
}

@Dao
interface LibraryMaintenanceDao : LibraryMaintenanceStore {
    @Query("SELECT * FROM libraries WHERE id = :libraryId")
    suspend fun targetLibrary(libraryId: String): LibraryEntity?

    @Query("UPDATE practice_sessions SET status = 'superseded', updatedAt = :now WHERE libraryId = :libraryId AND id = :expectedSessionId AND status = 'active'")
    suspend fun endActiveRound(libraryId: String, expectedSessionId: String, now: Long): Int

    override suspend fun endRound(libraryId: String, expectedSessionId: String, now: Long): Boolean =
        endActiveRound(libraryId, expectedSessionId, now) == 1

    // The existing session->answer foreign key cascades; questions/PDFs are untouched.
    @Query("DELETE FROM practice_sessions WHERE libraryId = :libraryId")
    suspend fun clearSessions(libraryId: String)

    @Transaction
    override suspend fun resetPractice(libraryId: String): Boolean {
        if (targetLibrary(libraryId) == null) return false
        clearSessions(libraryId)
        return true
    }

    @Query("DELETE FROM libraries WHERE id = :libraryId")
    suspend fun deleteLibraryRow(libraryId: String): Int

    @Transaction
    override suspend fun deleteLibrary(libraryId: String): DeletedLibrary? {
        val target = targetLibrary(libraryId) ?: return null
        check(deleteLibraryRow(libraryId) == 1)
        return DeletedLibrary(target.pdfRelativePath)
    }
}

sealed interface MaintenanceOutcome {
    data class Done(val message: String) : MaintenanceOutcome
    data class Failed(val message: String) : MaintenanceOutcome
}

/** Only invoked after an explicit confirmation. Database commit precedes private-PDF cleanup. */
class LibraryMaintenanceRepository(
    private val store: LibraryMaintenanceStore,
    private val deletePdf: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()

    suspend fun endRound(libraryId: String, expectedSessionId: String): MaintenanceOutcome = commit {
        if (store.endRound(libraryId, expectedSessionId, clock()))
            MaintenanceOutcome.Done("本轮已结束，已有作答保留，未增加完成遍数")
        else MaintenanceOutcome.Failed("本轮状态已变化，请关闭提示后重试")
    }

    suspend fun resetPractice(libraryId: String): MaintenanceOutcome = commit {
        if (store.resetPractice(libraryId)) MaintenanceOutcome.Done("统计和错题本已重置，题目和 PDF 保留")
        else MaintenanceOutcome.Failed("题库不存在，未重置")
    }

    suspend fun deleteLibrary(libraryId: String): MaintenanceOutcome = commit {
        val deleted = store.deleteLibrary(libraryId)
            ?: return@commit MaintenanceOutcome.Failed("题库不存在，请刷新后重试")
        val pdfCleaned = deleted.pdfRelativePath?.let { path ->
            libraryId.matches(Regex("[A-Za-z0-9_-]{1,80}")) &&
                path == "libraries/$libraryId/source.pdf" && runCatching { deletePdf(path) }.isSuccess
        } ?: true
        MaintenanceOutcome.Done(if (pdfCleaned) "题库及关联记录已删除"
            else "题库及关联记录已删除，PDF 私有副本稍后自动清理")
    }

    private suspend fun commit(action: suspend () -> MaintenanceOutcome): MaintenanceOutcome =
        mutex.withLock {
            // Once confirmed and started, leaving/destroying a screen cannot split the action.
            withContext(NonCancellable + Dispatchers.IO) {
                try { action() } catch (_: Exception) {
                    MaintenanceOutcome.Failed("操作保存失败，请重试")
                }
            }
        }
}
