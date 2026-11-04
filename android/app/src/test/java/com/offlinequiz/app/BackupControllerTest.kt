package com.offlinequiz.app

import com.offlinequiz.app.backup.BackupError
import com.offlinequiz.app.backup.BackupException
import com.offlinequiz.app.backup.BackupManifest
import com.offlinequiz.app.backup.BackupOperations
import com.offlinequiz.app.backup.PreparedExport
import com.offlinequiz.app.backup.PreparedRestore
import com.offlinequiz.app.backup.RestorePlan
import com.offlinequiz.app.backup.RestoreResult
import com.offlinequiz.app.backup.RestoreSummary
import com.offlinequiz.app.viewmodel.BackupController
import com.offlinequiz.app.viewmodel.BackupUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.InputStream
import java.util.UUID

/** Deterministic request-identity/state tests for the controller actually used by BackupScreen. */
class BackupControllerTest {

    private fun summary() = RestoreSummary(1, 1, 1, 0, 0, 1, 0, 0, 0, 0)

    private fun restore(): PreparedRestore {
        val s = summary()
        return PreparedRestore(
            1L,
            File(System.getProperty("java.io.tmpdir"), "bc-${UUID.randomUUID()}.zip"),
            RestorePlan(s, emptyList()),
            s,
            BackupManifest(0L, 3, emptyList(), emptyList())
        )
    }

    private fun exportFile(): PreparedExport =
        PreparedExport(
            File(System.getProperty("java.io.tmpdir"), "bc-export-${UUID.randomUUID()}.zip"),
            BackupManifest(0L, 3, emptyList(), emptyList())
        )

    private class FakeOps : BackupOperations {
        var exportGate: CompletableDeferred<Unit>? = null
        var exportError: Throwable? = null
        var exportPrepared: PreparedExport? = null
        var exportNonCancellable = false
        var openCount = 0
        var publishCalls = 0
        var cancelExportCalls = 0

        var prepareGate: CompletableDeferred<Unit>? = null
        var prepareError: Throwable? = null
        var prepareResult: PreparedRestore? = null
        var prepareNonCancellable = false
        var cancelPreparedCalls = 0

        var commitGate: CompletableDeferred<Unit>? = null
        var commitError: Throwable? = null
        var commitCalls = 0

        override suspend fun prepareExport(
            isCancelled: () -> Boolean,
            onPrepared: (PreparedExport) -> Unit
        ): PreparedExport {
            await(exportGate, exportNonCancellable)
            exportError?.let { throw it }
            val prepared = exportPrepared ?: error("no export fixture")
            onPrepared(prepared)
            return prepared
        }

        override suspend fun publishExport(
            prepared: PreparedExport,
            output: OutputStream,
            isCancelled: () -> Boolean
        ) {
            publishCalls += 1
            output.write(1)
        }

        override suspend fun prepare(
            input: InputStream,
            isCancelled: () -> Boolean,
            onPrepared: (PreparedRestore) -> Unit
        ): PreparedRestore {
            await(prepareGate, prepareNonCancellable)
            prepareError?.let { throw it }
            val prepared = prepareResult ?: error("no prepare fixture")
            onPrepared(prepared)
            return prepared
        }

        override suspend fun commit(prepared: PreparedRestore): RestoreResult {
            commitCalls += 1
            commitGate?.await()
            commitError?.let { throw it }
            return RestoreResult(emptyList(), RestoreSummary(1, 1, 1, 0, 0, 1, 0, 0, 0, 0))
        }

        override fun cancel(prepared: PreparedRestore) {
            cancelPreparedCalls += 1
        }

        override fun cancelExport(prepared: PreparedExport) {
            cancelExportCalls += 1
        }

        private suspend fun await(gate: CompletableDeferred<Unit>?, nonCancellable: Boolean) {
            if (gate == null) return
            if (nonCancellable) {
                withContext(NonCancellable) { gate.await() }
            } else {
                gate.await()
            }
        }
    }

    @Test
    fun targetOpensOnlyAfterPrepareSucceeds() = runTest {
        val ops = FakeOps()
        val controller = BackupController(ops, this, StandardTestDispatcher(testScheduler))
        ops.exportError = BackupException(BackupError.IO, "boom")
        controller.export {
            ops.openCount += 1
            ByteArrayOutputStream()
        }
        advanceUntilIdle()
        assertEquals(0, ops.openCount)
        assertTrue(controller.state is BackupUiState.Error)

        ops.exportError = null
        ops.exportPrepared = exportFile()
        controller.export {
            ops.openCount += 1
            ByteArrayOutputStream()
        }
        advanceUntilIdle()
        assertEquals(1, ops.openCount)
        assertEquals(1, ops.publishCalls)
        assertTrue(controller.state is BackupUiState.Success)
    }

    @Test
    fun cancelledExportACannotTouchLateB() = runTest {
        val ops = FakeOps()
        val controller = BackupController(ops, this, StandardTestDispatcher(testScheduler))
        val aGate = CompletableDeferred<Unit>()
        ops.exportGate = aGate
        ops.exportNonCancellable = true
        ops.exportPrepared = exportFile()
        controller.export {
            ops.openCount += 100
            ByteArrayOutputStream()
        }
        runCurrent()
        assertTrue(controller.state is BackupUiState.Exporting)
        controller.tryLeave()
        assertTrue(controller.state is BackupUiState.Idle)

        ops.exportGate = null
        controller.export {
            ops.openCount += 1
            ByteArrayOutputStream()
        }
        advanceUntilIdle()
        val bState = controller.state
        assertTrue(bState is BackupUiState.Success)

        aGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, ops.openCount) // only B opened; A never opened its target
        assertTrue(controller.state is BackupUiState.Success)
        assertTrue("A's late prepared temp must be cleaned", ops.cancelExportCalls >= 1)
    }

    @Test
    fun cancelledPrepareDeliveryIsCleanedAndDoesNotOverwriteB() = runTest {
        val ops = FakeOps()
        val controller = BackupController(ops, this, StandardTestDispatcher(testScheduler))
        val a = restore()
        ops.prepareResult = a
        val gate = CompletableDeferred<Unit>()
        ops.prepareGate = gate
        ops.prepareNonCancellable = true
        controller.prepare { ByteArrayInputStream(ByteArray(0)) }
        runCurrent()
        controller.tryLeave()

        ops.prepareGate = null
        val b = restore()
        ops.prepareResult = b
        controller.prepare { ByteArrayInputStream(ByteArray(0)) }
        advanceUntilIdle()
        assertTrue(controller.state is BackupUiState.Preview)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(controller.state is BackupUiState.Preview)
        assertTrue("late A prepared resource must be cancelled", ops.cancelPreparedCalls >= 1)
    }

    @Test
    fun duplicateConfirmCallsCommitOnceAndCommittedLateCancelIsSuccess() = runTest {
        val ops = FakeOps()
        val controller = BackupController(ops, this, StandardTestDispatcher(testScheduler))
        val restore = restore()
        ops.prepareResult = restore
        controller.prepare { ByteArrayInputStream(ByteArray(0)) }
        advanceUntilIdle()
        assertTrue(controller.state is BackupUiState.Preview)

        val gate = CompletableDeferred<Unit>()
        ops.commitGate = gate
        controller.confirm()
        runCurrent()
        assertTrue(controller.state is BackupUiState.Committing)
        controller.confirm()
        assertEquals(1, ops.commitCalls)

        restore.committed = true
        ops.commitError = kotlinx.coroutines.CancellationException("late cancel")
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue("committed DB must not be reported as not written", controller.state is BackupUiState.Success)
    }

    @Test
    fun readyPreviewCancelReleasesPreparedAndBlocksConfirm() = runTest {
        val ops = FakeOps()
        val controller = BackupController(ops, this, StandardTestDispatcher(testScheduler))
        ops.prepareResult = restore()
        controller.prepare { ByteArrayInputStream(ByteArray(0)) }
        advanceUntilIdle()
        assertTrue(controller.state is BackupUiState.Preview)

        controller.cancelPreview()
        assertTrue(controller.state is BackupUiState.Idle)
        assertEquals(1, ops.cancelPreparedCalls)
        controller.confirm()
        advanceUntilIdle()
        assertEquals(0, ops.commitCalls)

        controller.cancelPreview()
        assertEquals(1, ops.cancelPreparedCalls) // repeated cancel is idempotent

        ops.prepareResult = restore()
        controller.prepare { ByteArrayInputStream(ByteArray(0)) }
        advanceUntilIdle()
        assertTrue(controller.state is BackupUiState.Preview)
    }

    @Test
    fun failedCommitReturnsToRetryableState() = runTest {
        val ops = FakeOps()
        val controller = BackupController(ops, this, StandardTestDispatcher(testScheduler))
        ops.prepareResult = restore()
        controller.prepare { ByteArrayInputStream(ByteArray(0)) }
        advanceUntilIdle()
        ops.commitError = BackupException(BackupError.IO, "commit failed")
        controller.confirm()
        advanceUntilIdle()
        assertTrue(controller.state is BackupUiState.Error)

        ops.commitError = null
        ops.exportPrepared = exportFile()
        controller.export {
            ops.openCount += 1
            ByteArrayOutputStream()
        }
        advanceUntilIdle()
        assertTrue(controller.state is BackupUiState.Success)
    }

    @Test
    fun targetCloseFailureIsNotSuccess() = runTest {
        val ops = FakeOps()
        val controller = BackupController(ops, this, StandardTestDispatcher(testScheduler))
        ops.exportPrepared = exportFile()
        controller.export {
            object : ByteArrayOutputStream() {
                override fun close() {
                    throw java.io.IOException("provider failed while closing")
                }
            }
        }
        advanceUntilIdle()
        assertTrue(controller.state is BackupUiState.Error)
    }

    @Test
    fun successfulExportClosesOnceAndPublishesOnce() = runTest {
        val ops = FakeOps()
        val controller = BackupController(ops, this, StandardTestDispatcher(testScheduler))
        ops.exportPrepared = exportFile()
        var closes = 0
        controller.export {
            object : ByteArrayOutputStream() {
                override fun close() {
                    closes += 1
                    super.close()
                }
            }
        }
        advanceUntilIdle()
        assertTrue(controller.state is BackupUiState.Success)
        assertEquals(1, ops.publishCalls)
        assertEquals(1, closes)
    }

    @Test
    fun targetHandleDeliveredOnCancellationIsClosed() = runTest {
        val ops = FakeOps()
        val controller = BackupController(ops, this, StandardTestDispatcher(testScheduler))
        ops.exportPrepared = exportFile()
        var closed = false
        controller.export {
            controller.tryLeave()
            object : ByteArrayOutputStream() {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
        }
        advanceUntilIdle()
        assertTrue("handle returned at cancellation must be closed", closed)
        assertTrue(controller.state is BackupUiState.Idle)
    }
}
