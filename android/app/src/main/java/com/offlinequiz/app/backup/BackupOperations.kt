package com.offlinequiz.app.backup

import java.io.InputStream
import java.io.OutputStream

/** Validated local ZIP that is ready to be published; no system target has been opened yet. */
data class PreparedExport(val tempFile: java.io.File, val manifest: BackupManifest)

/**
 * Injectable boundary for the request controller. The real [BackupService] implements it; tests
 * use gates to create deterministic late-success/late-failure/late-cancel cases.
 */
interface BackupOperations {
    suspend fun prepareExport(
        isCancelled: () -> Boolean,
        onPrepared: (PreparedExport) -> Unit
    ): PreparedExport

    suspend fun publishExport(
        prepared: PreparedExport,
        output: OutputStream,
        isCancelled: () -> Boolean
    )

    suspend fun prepare(
        input: InputStream,
        isCancelled: () -> Boolean,
        onPrepared: (PreparedRestore) -> Unit
    ): PreparedRestore

    suspend fun commit(prepared: PreparedRestore): RestoreResult

    fun cancel(prepared: PreparedRestore)

    fun cancelExport(prepared: PreparedExport)
}
