package com.offlinequiz.app.backup

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Streams a backup ZIP. The manifest is written first (counts/PDF digest), then each library's
 * NDJSON records and optional PDF. The caller must write to a temporary file and only publish it
 * after this method returns; a failure/cancellation may leave a partial stream behind.
 */
class BackupWriter(private val limits: BackupLimits = BackupLimits()) {

    suspend fun write(
        source: BackupSource,
        output: OutputStream,
        databaseVersion: Int,
        now: Long = System.currentTimeMillis()
    ): BackupManifest {
        if (databaseVersion < 1) throw BackupException(BackupError.BAD_FIELD, "数据库版本无效")
        val headers = source.libraries()
        if (headers.size > limits.maxLibraries) {
            throw BackupException(BackupError.TOO_LARGE, "题库数量超过上限 ${limits.maxLibraries}")
        }
        val libraryIds = mutableSetOf<String>()
        for (header in headers) {
            BackupValidator.validateHeader(header, limits)
            if (!libraryIds.add(header.record.id)) {
                throw BackupException(BackupError.DUPLICATE_ID, "题库标识重复：${header.record.id}")
            }
        }

        val pdfSources = capturePdfSources(source, headers)
        val pdfDigests = mutableMapOf<String, PdfDigest>()
        for (header in headers) {
            currentCoroutineContext().ensureActive()
            if (header.pdfAvailable) {
                val pdfSource = pdfSources[header.record.id]
                    ?: throw BackupException(
                        BackupError.BAD_RESOURCE,
                        "题库“${header.record.name}”声明包含 PDF，但无法读取来源文件"
                    )
                pdfDigests[header.record.id] = digestPdf(pdfSource)
            }
        }

        val metas = headers.mapIndexed { index, header ->
            val digest = pdfDigests[header.record.id]
            BackupLibraryMeta(
                ordinal = index,
                record = header.record.copy(pdfRelativePath = null),
                questionCount = header.questionCount,
                sessionCount = header.sessionCount,
                answerCount = header.answerCount,
                pdf = digest?.let {
                    BackupPdfInfo(
                        entry = pdfEntry(index),
                        byteSize = it.byteSize,
                        sha256 = it.sha256
                    )
                }
            )
        }
        val entries = buildList {
            for (header in headers.withIndex()) {
                add(
                    BackupEntryRef(
                        questionsEntry(header.index),
                        BackupEntryKind.QUESTIONS,
                        header.index,
                        header.value.questionCount
                    )
                )
                add(
                    BackupEntryRef(
                        sessionsEntry(header.index),
                        BackupEntryKind.SESSIONS,
                        header.index,
                        header.value.sessionCount
                    )
                )
                add(
                    BackupEntryRef(
                        answersEntry(header.index),
                        BackupEntryKind.ANSWERS,
                        header.index,
                        header.value.answerCount
                    )
                )
                if (header.value.pdfAvailable) {
                    add(BackupEntryRef(pdfEntry(header.index), BackupEntryKind.PDF, header.index, 0))
                }
            }
        }
        val manifest = BackupManifest(
            createdAt = now,
            databaseVersion = databaseVersion,
            libraries = metas,
            entries = entries
        )
        BackupValidator.validateManifest(manifest, limits)

        val budget = Budget(limits)
        val zip = ZipOutputStream(BufferedOutputStream(output, 64 * 1024))
        writeManifest(zip, manifest, budget)
        for ((index, header) in headers.withIndex()) {
            currentCoroutineContext().ensureActive()
            val questions = source.questions(header.record.id)
            val sessions = source.sessions(header.record.id)
            val answers = source.answers(header.record.id)
            if (questions.size != header.questionCount) {
                throw BackupException(BackupError.BAD_MANIFEST, "导出时题目数量发生变化，请重试")
            }
            if (sessions.size != header.sessionCount) {
                throw BackupException(BackupError.BAD_MANIFEST, "导出时练习会话数量发生变化，请重试")
            }
            if (answers.size != header.answerCount) {
                throw BackupException(BackupError.BAD_MANIFEST, "导出时作答记录数量发生变化，请重试")
            }
            BackupValidator.validateLibrary(header.record, questions, sessions, answers, limits)

            writeRecords(zip, questionsEntry(index), questions, budget) { BackupJson.encodeQuestion(it) }
            writeRecords(zip, sessionsEntry(index), sessions, budget) { BackupJson.encodeSession(it) }
            writeRecords(zip, answersEntry(index), answers, budget) { BackupJson.encodeAnswer(it) }

            val digest = pdfDigests[header.record.id]
            if (digest != null) {
                writePdf(zip, pdfEntry(index), header, pdfSources, digest, budget)
            }
            currentCoroutineContext().ensureActive()
        }
        zip.finish()
        zip.flush()
        return manifest
    }

    private fun writeManifest(zip: ZipOutputStream, manifest: BackupManifest, budget: Budget) {
        val bytes = BackupJson.encodeManifest(manifest).toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > limits.maxManifestBytes) {
            throw BackupException(BackupError.TOO_LARGE, "备份清单超过上限")
        }
        zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
        val limited = LimitedOutputStream(zip, limits.maxManifestBytes, budget)
        limited.write(bytes)
        limited.flush()
        zip.closeEntry()
    }

    private suspend fun <T> writeRecords(
        zip: ZipOutputStream,
        path: String,
        records: List<T>,
        budget: Budget,
        encode: (T) -> org.json.JSONObject
    ) {
        zip.putNextEntry(ZipEntry(path))
        val limited = LimitedOutputStream(zip, limits.maxEntryBytes, budget)
        val writer = BufferedWriter(OutputStreamWriter(limited, Charsets.UTF_8), 64 * 1024)
        var index = 0
        for (record in records) {
            if (index % 256 == 0) currentCoroutineContext().ensureActive()
            writer.write(encode(record).toString())
            writer.write("\n")
            index += 1
        }
        writer.flush()
        zip.closeEntry()
    }

    private suspend fun writePdf(
        zip: ZipOutputStream,
        path: String,
        header: BackupLibraryHeader,
        pdfSources: Map<String, BackupPdfSource>,
        expected: PdfDigest,
        budget: Budget
    ) {
        val source = pdfSources[header.record.id]
            ?: throw BackupException(BackupError.BAD_RESOURCE, "PDF 来源已失效，请重试")
        zip.putNextEntry(ZipEntry(path))
        val digest = MessageDigest.getInstance("SHA-256")
        val limited = LimitedOutputStream(zip, limits.maxPdfBytes, budget)
        var size = 0L
        source.open().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                size += read
                if (size > limits.maxPdfBytes) {
                    throw BackupException(BackupError.TOO_LARGE, "PDF 大小超过上限")
                }
                digest.update(buffer, 0, read)
                limited.write(buffer, 0, read)
            }
        }
        limited.flush()
        zip.closeEntry()
        if (size != expected.byteSize || sha256(digest.digest()) != expected.sha256) {
            throw BackupException(BackupError.BAD_RESOURCE, "导出期间 PDF 内容发生变化，请重试")
        }
    }

    private suspend fun digestPdf(source: BackupPdfSource): PdfDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        source.open().use { input ->
            val header = ByteArray(5)
            var read = 0
            while (read < header.size) {
                val count = input.read(header, read, header.size - read)
                if (count < 0) break
                read += count
            }
            if (read != header.size || String(header, Charsets.US_ASCII) != "%PDF-") {
                throw BackupException(BackupError.BAD_RESOURCE, "来源 PDF 文件头无效")
            }
            digest.update(header)
            size += read
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                if (size > limits.maxPdfBytes) {
                    throw BackupException(BackupError.TOO_LARGE, "PDF 大小超过上限")
                }
                digest.update(buffer, 0, count)
            }
        }
        if (size == 0L) throw BackupException(BackupError.BAD_RESOURCE, "来源 PDF 为空")
        return PdfDigest(size, sha256(digest.digest()))
    }

    private class PdfDigest(val byteSize: Long, val sha256: String)

    private class Budget(private val limits: BackupLimits) {
        var totalBytes: Long = 0
            private set

        fun add(bytes: Int) {
            totalBytes += bytes
            if (totalBytes > limits.maxTotalBytes) {
                throw BackupException(BackupError.TOO_LARGE, "备份总解压大小超过上限")
            }
        }
    }

    private class LimitedOutputStream(
        private val out: OutputStream,
        private val perEntryLimit: Long,
        private val budget: Budget
    ) : OutputStream() {
        private var entryBytes = 0L

        override fun write(b: Int) {
            val one = byteArrayOf(b.toByte())
            write(one, 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            entryBytes += len
            if (entryBytes > perEntryLimit) {
                throw BackupException(BackupError.TOO_LARGE, "单个备份条目超过上限")
            }
            budget.add(len)
            out.write(b, off, len)
        }

        override fun flush() = out.flush()
    }

    private fun sha256(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /**
     * Captures PDF providers before the manifest is written so the file can be read twice
     * (digest/validation first, ZIP payload second) without trusting a stale path.
     */
    private suspend fun capturePdfSources(
        source: BackupSource,
        headers: List<BackupLibraryHeader>
    ): Map<String, BackupPdfSource> {
        val captured = mutableMapOf<String, BackupPdfSource>()
        for (header in headers) {
            if (!header.pdfAvailable) continue
            currentCoroutineContext().ensureActive()
            val pdf = source.openPdf(header.record.id)
                ?: throw BackupException(
                    BackupError.BAD_RESOURCE,
                    "题库“${header.record.name}”声明包含 PDF，但无法读取来源文件"
                )
            captured[header.record.id] = pdf
        }
        return captured
    }
}
