package com.offlinequiz.app.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** Conservative Android limits: the validated plan is held in memory, so do not imply 1 GiB. */
val ANDROID_BACKUP_LIMITS = BackupLimits(
    maxLibraries = 200,
    maxEntries = 2000,
    maxManifestBytes = 2L * 1024 * 1024,
    maxEntryBytes = 32L * 1024 * 1024,
    maxTotalBytes = 64L * 1024 * 1024,
    maxLineBytes = 2 * 1024 * 1024,
    maxStringBytes = 256 * 1024,
    maxQuestionsPerLibrary = 20_000,
    maxSessionsPerLibrary = 20_000,
    maxAnswersPerLibrary = 200_000,
    maxPdfBytes = 32L * 1024 * 1024
)

const val MAX_BACKUP_FILE_BYTES = 64L * 1024 * 1024

class PreparedRestore(
    val requestId: Long,
    val tempFile: File,
    val plan: RestorePlan,
    val summary: RestoreSummary,
    val manifest: BackupManifest
) {
    @Volatile var committed: Boolean = false
    @Volatile var cancelled: Boolean = false
}

data class RestoreResult(val libraryIds: List<String>, val summary: RestoreSummary)

/**
 * Phone backup service.
 *
 * Export: DB-consistent snapshot → private temp ZIP → read-back validation → copy to the SAF
 * target. Import: SAF input → private temp file → bounded/cancellable validation + plan → user
 * confirmation → PDF staging/verification → one ABORT transaction → success.
 *
 * A failure before the DB transaction removes only directories created by this request. After the
 * transaction commits, [PreparedRestore.committed] is set inside a non-cancellable block so a late
 * cancellation never deletes the committed PDFs or reports a half-restore.
 */
class BackupService(
    private val store: BackupDataStore,
    private val workDir: File,
    private val pdfRoot: File,
    private val targetSchemaVersion: Int,
    private val limits: BackupLimits = ANDROID_BACKUP_LIMITS,
    private val maxFileBytes: Long = MAX_BACKUP_FILE_BYTES
) : BackupOperations {

    private var requestSequence = 0L

    /** Convenience for tests/callers that already own the target stream. */
    suspend fun export(output: OutputStream): BackupManifest {
        val prepared = prepareExport({ false }, {})
        publishExport(prepared, output) { false }
        return prepared.manifest
    }

    override suspend fun prepareExport(
        isCancelled: () -> Boolean,
        onPrepared: (PreparedExport) -> Unit
    ): PreparedExport = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val temp = File(workDir, "export-${UUID.randomUUID()}.zip")
        try {
            checkCancelled(isCancelled)
            val snapshot = store.readSnapshot()
            val source = SnapshotSource(snapshot, store)
            val manifest = FileOutputStream(temp).use { stream ->
                BackupWriter(limits).write(
                    source,
                    stream,
                    databaseVersion = targetSchemaVersion,
                    now = System.currentTimeMillis()
                )
            }
            checkCancelled(isCancelled)
            // Validate exactly what will be handed to the system picker, before it is opened.
            FileInputStream(temp).use { BackupReader(limits, isCancelled).read(it) }
            val prepared = PreparedExport(temp, manifest)
            onPrepared(prepared)
            prepared
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    override suspend fun publishExport(
        prepared: PreparedExport,
        output: OutputStream,
        isCancelled: () -> Boolean
    ): Unit = withContext(Dispatchers.IO) {
        try {
            checkCancelled(isCancelled)
            prepared.tempFile.inputStream().use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    checkCancelled(isCancelled)
                    val read = input.read(buffer)
                    if (read < 0) break
                    currentCoroutineContext().ensureActive()
                    checkCancelled(isCancelled)
                    output.write(buffer, 0, read)
                }
            }
            output.flush()
        } catch (_: kotlinx.coroutines.CancellationException) {
            throw kotlinx.coroutines.CancellationException("已取消导出")
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException(
                BackupError.IO,
                "导出未完成，目标文件可能不完整；请删除该文件后重试",
                e
            )
        } finally {
            prepared.tempFile.delete()
        }
    }

    override fun cancelExport(prepared: PreparedExport) {
        prepared.tempFile.delete()
    }

    override suspend fun prepare(
        input: InputStream,
        isCancelled: () -> Boolean,
        onPrepared: (PreparedRestore) -> Unit
    ): PreparedRestore = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val temp = File(workDir, "import-${UUID.randomUUID()}.zip")
        try {
            copyBounded(input, temp, isCancelled)
            val bundle = FileInputStream(temp).use {
                BackupReader(limits, isCancelled).read(it)
            }
            val plan = RestorePlanner.plan(
                bundle,
                BackupIdFactory { UUID.randomUUID().toString() },
                targetSchemaVersion
            )
            val prepared = PreparedRestore(
                requestId = ++requestSequence,
                tempFile = temp,
                plan = plan,
                summary = plan.summary,
                manifest = bundle.manifest
            )
            onPrepared(prepared)
            prepared
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    /** Backwards-compatible convenience used by older tests. */
    suspend fun prepare(input: InputStream, isCancelled: () -> Boolean = { false }): PreparedRestore =
        prepare(input, isCancelled, {})

    override suspend fun commit(prepared: PreparedRestore): RestoreResult = withContext(Dispatchers.IO) {
        if (prepared.cancelled) {
            throw BackupException(BackupError.CANCELLED, "已取消恢复")
        }
        val libraryIds = prepared.plan.libraries.map { it.library.id }
        if (prepared.committed) return@withContext RestoreResult(libraryIds, prepared.summary)
        currentCoroutineContext().ensureActive()

        val stagedDirs = mutableListOf<File>()
        try {
            stagePdfs(prepared, stagedDirs)
            withContext(NonCancellable) {
                store.insert(prepared.plan)
                prepared.committed = true
                prepared.tempFile.delete()
            }
            RestoreResult(libraryIds, prepared.summary)
        } catch (e: Throwable) {
            if (!prepared.committed) {
                withContext(NonCancellable) {
                    for (dir in stagedDirs) dir.deleteRecursively()
                    prepared.tempFile.delete()
                }
            }
            if (e is BackupException) throw e
            throw BackupException(BackupError.IO, "恢复失败，未写入任何数据", e)
        }
    }

    /** Preview-only cancellation: safe because commit() owns the staged/committed files. */
    override fun cancel(prepared: PreparedRestore) {
        if (prepared.committed) return
        prepared.cancelled = true
        prepared.tempFile.delete()
    }

    /** Called on process start; only private cache temp files, never libraries or PDFs. */
    fun cleanupTempFiles() {
        workDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("export-") || file.name.startsWith("import-")) {
                file.delete()
            }
        }
    }

    private suspend fun stagePdfs(prepared: PreparedRestore, stagedDirs: MutableList<File>) {
        ZipFile(prepared.tempFile).use { zip ->
            for (library in prepared.plan.libraries) {
                val pdf = library.pdf ?: continue
                currentCoroutineContext().ensureActive()
                val targetDir = File(pdfRoot, "libraries/${library.library.id}")
                if (targetDir.exists()) {
                    throw BackupException(
                        BackupError.DUPLICATE_ID,
                        "新副本文件目录已存在，未写入任何数据"
                    )
                }
                if (!targetDir.mkdirs()) {
                    throw BackupException(BackupError.IO, "无法创建新题库的 PDF 目录")
                }
                stagedDirs += targetDir
                val entry = zip.getEntry(pdf.entry)
                    ?: throw BackupException(BackupError.MISSING_ENTRY, "备份中的 PDF 条目缺失")
                val part = File(targetDir, "source.pdf.part")
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            size += read
                            if (size > limits.maxPdfBytes) {
                                throw BackupException(BackupError.TOO_LARGE, "PDF 超过大小上限")
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }
                if (size != pdf.byteSize || sha256(digest.digest()) != pdf.sha256) {
                    throw BackupException(BackupError.CHECKSUM_MISMATCH, "备份中的 PDF 校验失败")
                }
                val target = File(targetDir, "source.pdf")
                if (!part.renameTo(target)) {
                    throw BackupException(BackupError.IO, "无法发布新题库的 PDF")
                }
            }
        }
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw kotlinx.coroutines.CancellationException("已取消备份操作")
    }

    private fun copyBounded(input: InputStream, target: File, isCancelled: () -> Boolean) {
        var total = 0L
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (isCancelled()) throw BackupException(BackupError.CANCELLED, "已取消读取备份")
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxFileBytes) {
                    throw BackupException(BackupError.TOO_LARGE, "备份文件超过手机端大小上限")
                }
                output.write(buffer, 0, read)
            }
            output.flush()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class SnapshotSource(
        private val snapshot: BackupSnapshot,
        private val store: BackupDataStore
    ) : BackupSource {

        private val headers = snapshot.libraries.map { data ->
            BackupLibraryHeader(
                record = data.record,
                questionCount = data.questions.size,
                sessionCount = data.sessions.size,
                answerCount = data.answers.size,
                pdfAvailable = store.openPdf(data.record) != null
            )
        }

        override suspend fun libraries(): List<BackupLibraryHeader> = headers

        override suspend fun questions(libraryId: String): List<com.offlinequiz.app.model.StoredQuestion> =
            snapshot.libraries.first { it.record.id == libraryId }.questions

        override suspend fun sessions(libraryId: String): List<com.offlinequiz.app.model.PracticeSessionRecord> =
            snapshot.libraries.first { it.record.id == libraryId }.sessions

        override suspend fun answers(libraryId: String): List<BackupAnswer> =
            snapshot.libraries.first { it.record.id == libraryId }.answers

        override suspend fun openPdf(libraryId: String): BackupPdfSource? {
            val record = snapshot.libraries.first { it.record.id == libraryId }.record
            return store.openPdf(record)
        }
    }
}
