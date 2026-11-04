package com.offlinequiz.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.offlinequiz.app.backup.BackupError
import com.offlinequiz.app.backup.BackupException
import com.offlinequiz.app.backup.BackupOperations
import com.offlinequiz.app.backup.PreparedExport
import com.offlinequiz.app.backup.PreparedRestore
import com.offlinequiz.app.backup.RestoreSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

sealed interface BackupUiState {
    data object Idle : BackupUiState
    data object Exporting : BackupUiState
    data object Preparing : BackupUiState
    data class Preview(val summary: RestoreSummary) : BackupUiState
    data object Committing : BackupUiState
    data class Success(val message: String, val libraryIds: List<String> = emptyList()) : BackupUiState
    data class Error(val message: String) : BackupUiState
}

/**
 * Request-identity state machine for the backup screen. Android URI/ContentResolver access is
 * injected as lambdas; [BackupOperations] is injectable, so A-cancel → B-start → A-late tests are
 * deterministic without a device or fixed sleeps.
 */
class BackupController(
    private val operations: BackupOperations,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    var state by mutableStateOf<BackupUiState>(BackupUiState.Idle)
        private set

    var leaveBlocked by mutableStateOf(false)
        private set

    private data class Request(
        val id: Long,
        val cancel: AtomicBoolean = AtomicBoolean(false),
        var job: Job? = null
    )

    private val nextId = AtomicLong()
    private var active: Request? = null
    private var prepared: PreparedRestore? = null
    private var preparedOwner: Long = -1L

    private val busy: Boolean
        get() = state is BackupUiState.Exporting ||
            state is BackupUiState.Preparing ||
            state is BackupUiState.Committing

    fun export(openTarget: () -> OutputStream) {
        if (busy) return
        val request = newRequest()
        state = BackupUiState.Exporting
        request.job = scope.launch {
            val delivered = AtomicReference<PreparedExport?>(null)
            val opened = AtomicReference<OutputStream?>(null)
            var output: OutputStream? = null
            var closed = false
            try {
                val prepared = withContext(io) {
                    operations.prepareExport(
                        { request.cancel.get() },
                        { delivered.set(it) }
                    )
                }
                if (!isCurrent(request) || request.cancel.get()) {
                    operations.cancelExport(delivered.get() ?: prepared)
                    return@launch
                }
                // Open the target only after local generation + full validation. The reference is
                // written inside the block so a cancellation at the return boundary still leaves
                // the controller with the handle to close.
                val target = withContext(io + NonCancellable) {
                    openTarget().also { opened.set(it) }
                }
                output = target
                if (!isCurrent(request) || request.cancel.get()) {
                    closeQuietly(target)
                    closed = true
                    operations.cancelExport(prepared)
                    return@launch
                }
                withContext(io) {
                    operations.publishExport(prepared, target, { request.cancel.get() })
                }
                // "Exported" includes a successful final close; providers may report the final
                // write failure only from close().
                withContext(io) { target.close() }
                closed = true
                if (isCurrent(request)) {
                    state = BackupUiState.Success("备份已导出。文件含题库和练习记录，未加密。")
                }
            } catch (e: CancellationException) {
                delivered.get()?.let { operations.cancelExport(it) }
                if (isCurrent(request)) state = BackupUiState.Idle
            } catch (e: BackupException) {
                if (isCurrent(request)) state = BackupUiState.Error(e.message)
            } catch (e: Exception) {
                if (isCurrent(request)) state = BackupUiState.Error("导出未完成：${e.message ?: "目标写入失败"}")
            } finally {
                if (!closed) closeQuietly(opened.get() ?: output)
                delivered.get()?.let { operations.cancelExport(it) }
                finish(request)
            }
        }
    }

    fun prepare(openInput: () -> InputStream) {
        if (busy) return
        val request = newRequest()
        state = BackupUiState.Preparing
        request.job = scope.launch {
            val delivered = AtomicReference<PreparedRestore?>(null)
            try {
                val prepared = withContext(io) {
                    openInput().use { input ->
                        operations.prepare(
                            input,
                            { request.cancel.get() },
                            { delivered.set(it) }
                        )
                    }
                }
                if (!isCurrent(request) || request.cancel.get()) {
                    operations.cancel(delivered.get() ?: prepared)
                    return@launch
                }
                this@BackupController.prepared = prepared
                preparedOwner = request.id
                state = BackupUiState.Preview(prepared.summary)
            } catch (e: CancellationException) {
                delivered.get()?.let { operations.cancel(it) }
                if (isCurrent(request)) state = BackupUiState.Idle
            } catch (e: BackupException) {
                delivered.get()?.let { operations.cancel(it) }
                if (isCurrent(request)) {
                    state = if (e.error == BackupError.CANCELLED) {
                        BackupUiState.Idle
                    } else {
                        BackupUiState.Error(e.message)
                    }
                }
            } catch (e: Exception) {
                delivered.get()?.let { operations.cancel(it) }
                if (isCurrent(request)) {
                    state = BackupUiState.Error("读取备份失败：${e.message ?: "读写错误"}")
                }
            } finally {
                delivered.get()?.let { candidate ->
                    if (this@BackupController.prepared !== candidate) {
                        operations.cancel(candidate)
                    }
                }
                finish(request)
            }
        }
    }

    fun confirm() {
        val restore = prepared ?: return
        if (state !is BackupUiState.Preview) return
        val request = newRequest()
        state = BackupUiState.Committing
        request.job = scope.launch {
            try {
                val result = withContext(io) { operations.commit(restore) }
                prepared = null
                preparedOwner = -1L
                state = BackupUiState.Success(
                    "已作为新副本导入 ${result.libraryIds.size} 个题库，现有题库未改变。",
                    result.libraryIds
                )
            } catch (e: CancellationException) {
                finishCommit(restore)
            } catch (e: BackupException) {
                finishCommit(restore, e.message)
            } catch (e: Exception) {
                finishCommit(restore, "恢复失败，未写入任何数据")
            } finally {
                finish(request)
            }
        }
    }

    /** A cancelled/failed commit checks [PreparedRestore.committed] before reporting anything. */
    private fun finishCommit(restore: PreparedRestore, message: String? = null) {
        if (restore.committed) {
            prepared = null
            preparedOwner = -1L
            val ids = restore.plan.libraries.map { it.library.id }
            state = BackupUiState.Success(
                "已作为新副本导入 ${ids.size} 个题库，现有题库未改变。",
                ids
            )
        } else {
            prepared = null
            preparedOwner = -1L
            state = BackupUiState.Error(message ?: "恢复已取消，未写入任何数据")
        }
    }

    fun cancelPreview() {
        if (state is BackupUiState.Committing) return
        active?.let { request ->
            request.cancel.set(true)
            request.job?.cancel()
        }
        active = null
        // The read Job has already finished for a ready Preview; the PreparedRestore still owns
        // the temp ZIP and must be cancelled even though there is no active Job.
        prepared?.let { operations.cancel(it) }
        prepared = null
        state = BackupUiState.Idle
        leaveBlocked = false
    }

    fun tryLeave(): Boolean {
        if (state is BackupUiState.Committing) {
            leaveBlocked = true
            return false
        }
        val request = active
        if (request != null) {
            request.cancel.set(true)
            request.job?.cancel()
            active = null
        }
        prepared?.let { operations.cancel(it) }
        prepared = null
        preparedOwner = -1L
        state = BackupUiState.Idle
        leaveBlocked = false
        return true
    }

    fun consumeLeaveBlocked() {
        leaveBlocked = false
    }

    fun close() {
        active?.job?.cancel()
        active = null
        prepared?.let { operations.cancel(it) }
        prepared = null
        preparedOwner = -1L
    }

    private fun newRequest(): Request {
        val request = Request(nextId.incrementAndGet())
        active = request
        leaveBlocked = false
        return request
    }

    private fun isCurrent(request: Request): Boolean = active?.id == request.id

    private fun finish(request: Request) {
        if (active?.id == request.id) active = null
    }

    private fun closeQuietly(output: OutputStream?) {
        try {
            output?.close()
        } catch (_: Exception) {
        }
    }
}
