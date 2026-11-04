package com.offlinequiz.app.backup

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Strict, streaming import. Nothing is extracted to disk and no Room row is written; the caller
 * receives either one fully validated [BackupBundle] or a [BackupException].
 */
class BackupReader(
    private val limits: BackupLimits = BackupLimits(),
    private val isCancelled: () -> Boolean = { false }
) {

    private fun checkCancelled() {
        if (isCancelled()) {
            throw BackupException(BackupError.CANCELLED, "已取消读取备份")
        }
    }

    fun read(input: InputStream): BackupBundle {
        val zip = ZipInputStream(BufferedInputStream(input, 64 * 1024))
        try {
            return readZip(zip)
        } catch (e: BackupException) {
            throw e
        } catch (e: ZipException) {
            throw BackupException(BackupError.CHECKSUM_MISMATCH, "备份 ZIP 损坏或校验失败", e)
        } catch (e: IOException) {
            throw BackupException(BackupError.IO, "读取备份失败：${e.message ?: "读写错误"}", e)
        }
    }

    private fun readZip(zip: ZipInputStream): BackupBundle {
        val budget = Budget(limits)
        val first = nextEntryOrNull(zip)
            ?: throw BackupException(BackupError.NOT_A_BACKUP, "备份文件为空")
        if (first.name != MANIFEST_ENTRY) {
            throw BackupException(BackupError.NOT_A_BACKUP, "备份文件缺少 manifest.json")
        }
        val manifestBytes = readEntryBytes(zip, limits.maxManifestBytes)
        budget.add(manifestBytes.size.toLong())
        val manifest = BackupJson.decodeManifest(decodeUtf8(manifestBytes), limits)
        zip.closeEntry()

        val expected = manifest.entries.associateBy { it.path }
        val accumulators = manifest.libraries.map { LibraryAccumulator(it) }
        val seen = mutableSetOf(MANIFEST_ENTRY)
        var entryCount = 1

        while (true) {
            checkCancelled()
            val entry = nextEntryOrNull(zip) ?: break
            entryCount += 1
            if (entryCount > limits.maxEntries) {
                throw BackupException(BackupError.TOO_LARGE, "备份条目数量超过上限")
            }
            val name = entry.name ?: throw BackupException(BackupError.BAD_PATH, "备份条目缺少名称")
            if (!BackupJson.isSafeEntryPath(name)) {
                throw BackupException(BackupError.BAD_PATH, "备份条目路径不安全：$name")
            }
            if (!seen.add(name)) {
                throw BackupException(BackupError.DUPLICATE_ENTRY, "备份 ZIP 包含重复条目：$name")
            }
            val ref = expected[name]
                ?: throw BackupException(BackupError.UNKNOWN_ENTRY, "备份包含未声明条目：$name")
            val accumulator = accumulators[ref.ordinal]
            when (ref.kind) {
                BackupEntryKind.PDF -> {
                    val pdf = accumulator.meta.pdf
                        ?: throw BackupException(BackupError.BAD_MANIFEST, "未声明的 PDF 条目")
                    readPdf(zip, pdf, budget)
                }
                BackupEntryKind.QUESTIONS -> {
                    val actual = readQuestions(zip, accumulator, ref, budget)
                    if (actual != ref.recordCount) {
                        throw BackupException(BackupError.BAD_MANIFEST, "题目数量与备份清单不一致")
                    }
                }
                BackupEntryKind.SESSIONS -> {
                    val actual = readSessions(zip, accumulator, ref, budget)
                    if (actual != ref.recordCount) {
                        throw BackupException(BackupError.BAD_MANIFEST, "练习会话数量与备份清单不一致")
                    }
                }
                BackupEntryKind.ANSWERS -> {
                    val actual = readAnswers(zip, accumulator, ref, budget)
                    if (actual != ref.recordCount) {
                        throw BackupException(BackupError.BAD_MANIFEST, "作答数量与备份清单不一致")
                    }
                }
            }
            zip.closeEntry()
        }
        if (seen.size - 1 != expected.size) {
            throw BackupException(BackupError.MISSING_ENTRY, "备份缺少清单声明的条目")
        }
        val libraries = accumulators.map { it.toLibrary() }
        val bundle = BackupBundle(manifest, libraries)
        BackupValidator.validateBundle(bundle, limits)
        return bundle
    }

    private fun readQuestions(
        zip: ZipInputStream,
        accumulator: LibraryAccumulator,
        ref: BackupEntryRef,
        budget: Budget
    ): Int {
        val reader = EntryByteReader(zip, limits.maxEntryBytes)
        var count = 0
        while (true) {
            checkCancelled()
            val line = reader.readLine(limits.maxLineBytes) ?: break
            if (line.isBlank()) throw BackupException(BackupError.BAD_JSON, "题目条目包含空行")
            if (count > ref.recordCount) {
                throw BackupException(BackupError.BAD_MANIFEST, "题目数量超过清单声明")
            }
            val obj = parseObject(line)
            accumulator.questions += BackupJson.decodeQuestion(obj, accumulator.meta.record.id, limits)
            count += 1
        }
        budget.add(reader.total)
        return count
    }

    private fun readSessions(
        zip: ZipInputStream,
        accumulator: LibraryAccumulator,
        ref: BackupEntryRef,
        budget: Budget
    ): Int {
        val reader = EntryByteReader(zip, limits.maxEntryBytes)
        var count = 0
        while (true) {
            checkCancelled()
            val line = reader.readLine(limits.maxLineBytes) ?: break
            if (line.isBlank()) throw BackupException(BackupError.BAD_JSON, "练习条目包含空行")
            if (count > ref.recordCount) {
                throw BackupException(BackupError.BAD_MANIFEST, "练习会话数量超过清单声明")
            }
            val obj = parseObject(line)
            accumulator.sessions += BackupJson.decodeSession(obj, accumulator.meta.record.id, limits)
            count += 1
        }
        budget.add(reader.total)
        return count
    }

    private fun readAnswers(
        zip: ZipInputStream,
        accumulator: LibraryAccumulator,
        ref: BackupEntryRef,
        budget: Budget
    ): Int {
        val reader = EntryByteReader(zip, limits.maxEntryBytes)
        var count = 0
        while (true) {
            checkCancelled()
            val line = reader.readLine(limits.maxLineBytes) ?: break
            if (line.isBlank()) throw BackupException(BackupError.BAD_JSON, "作答条目包含空行")
            if (count > ref.recordCount) {
                throw BackupException(BackupError.BAD_MANIFEST, "作答数量超过清单声明")
            }
            val obj = parseObject(line)
            accumulator.answers += BackupJson.decodeAnswer(obj, limits)
            count += 1
        }
        budget.add(reader.total)
        return count
    }

    private fun readPdf(zip: ZipInputStream, expected: BackupPdfInfo, budget: Budget) {
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteArray(5)
        var read = 0
        while (read < header.size) {
            val count = zip.read(header, read, header.size - read)
            if (count < 0) break
            read += count
        }
        if (read != header.size || String(header, Charsets.US_ASCII) != "%PDF-") {
            throw BackupException(BackupError.BAD_RESOURCE, "备份中的 PDF 文件头无效")
        }
        digest.update(header)
        budget.add(header.size.toLong())
        var size = header.size.toLong()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkCancelled()
            val count = zip.read(buffer)
            if (count < 0) break
            size += count
            if (size > limits.maxPdfBytes) {
                throw BackupException(BackupError.TOO_LARGE, "备份中的 PDF 超过大小上限")
            }
            digest.update(buffer, 0, count)
            budget.add(count.toLong())
        }
        if (size != expected.byteSize || sha256(digest.digest()) != expected.sha256) {
            throw BackupException(BackupError.CHECKSUM_MISMATCH, "备份中的 PDF 校验失败")
        }
    }

    private fun readEntryBytes(zip: ZipInputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkCancelled()
            val read = zip.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw BackupException(BackupError.TOO_LARGE, "单个备份条目超过大小上限")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun parseObject(line: String): JSONObject {
        return try {
            JSONObject(line)
        } catch (e: Exception) {
            throw BackupException(BackupError.BAD_JSON, "备份记录不是有效 JSON：${e.message ?: "格式错误"}", e)
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw BackupException(BackupError.BAD_JSON, "备份文本不是有效的 UTF-8", e)
        }
    }

    private fun nextEntryOrNull(zip: ZipInputStream): java.util.zip.ZipEntry? {
        return try {
            zip.nextEntry
        } catch (e: ZipException) {
            throw BackupException(BackupError.CHECKSUM_MISMATCH, "备份 ZIP 损坏或校验失败", e)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class LibraryAccumulator(val meta: BackupLibraryMeta) {
        val questions = mutableListOf<com.offlinequiz.app.model.StoredQuestion>()
        val sessions = mutableListOf<com.offlinequiz.app.model.PracticeSessionRecord>()
        val answers = mutableListOf<BackupAnswer>()

        fun toLibrary(): BackupLibrary = BackupLibrary(
            record = meta.record,
            questions = questions.toList(),
            sessions = sessions.toList(),
            answers = answers.toList(),
            pdf = meta.pdf
        )
    }

    private class Budget(private val limits: BackupLimits) {
        private var total = 0L

        fun add(bytes: Long) {
            total += bytes
            if (total > limits.maxTotalBytes) {
                throw BackupException(BackupError.TOO_LARGE, "备份总解压大小超过上限")
            }
        }
    }

    private class EntryByteReader(
        private val input: InputStream,
        private val maxBytes: Long
    ) {
        private val buffer = ByteArray(64 * 1024)
        private var position = 0
        private var length = 0

        var total = 0L
            private set

        fun readByte(): Int {
            if (position >= length) {
                length = input.read(buffer)
                position = 0
                if (length < 0) return -1
            }
            total += 1
            if (total > maxBytes) {
                throw BackupException(BackupError.TOO_LARGE, "单个备份条目超过大小上限")
            }
            return buffer[position++].toInt() and 0xff
        }

        fun readLine(maxLineBytes: Int): String? {
            val line = ByteArrayOutputStream()
            var any = false
            while (true) {
                val byte = readByte()
                if (byte < 0) {
                    if (!any) return null
                    break
                }
                any = true
                if (byte == '\n'.code) break
                if (line.size() >= maxLineBytes) {
                    throw BackupException(BackupError.TOO_LARGE, "备份单行超过大小上限")
                }
                line.write(byte)
            }
            return decode(line.toByteArray())
        }

        private fun decode(bytes: ByteArray): String {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: CharacterCodingException) {
                throw BackupException(BackupError.BAD_JSON, "备份文本不是有效的 UTF-8", e)
            }
        }
    }
}
