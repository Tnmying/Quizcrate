package com.offlinequiz.app

import com.offlinequiz.app.backup.BackupAnswer
import com.offlinequiz.app.backup.BackupError
import com.offlinequiz.app.backup.BackupException
import com.offlinequiz.app.backup.BackupLibraryHeader
import com.offlinequiz.app.backup.BackupLimits
import com.offlinequiz.app.backup.BackupPdfSource
import com.offlinequiz.app.backup.BackupReader
import com.offlinequiz.app.backup.BackupSource
import com.offlinequiz.app.backup.BackupWriter
import com.offlinequiz.app.model.PracticeAnswerKinds
import com.offlinequiz.app.model.PracticeDraft
import com.offlinequiz.app.model.PracticePhases
import com.offlinequiz.app.model.PracticeResults
import com.offlinequiz.app.model.PracticeSessionRecord
import com.offlinequiz.app.model.PracticeStatuses
import com.offlinequiz.app.model.PracticeUserAnswer
import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.model.StoredQuestion
import com.offlinequiz.app.model.toPracticeSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Malicious/corrupt backup rejection: every case must fail before any restore plan exists. */
class BackupRejectionTest {

    private class Fixture {
        val q1 = testQuestion(
            id = "q1",
            libraryId = "lib-a",
            position = 0,
            type = QuestionTypes.SINGLE_CHOICE,
            stem = "单选题干",
            options = listOf(com.offlinequiz.app.model.QuizOption("A", "甲")),
            answers = listOf("A")
        ).copy(confirmed = true, confirmedAt = 1L)
        val q2 = testQuestion(
            id = "q2",
            libraryId = "lib-a",
            position = 1,
            type = QuestionTypes.SHORT_ANSWER,
            stem = "简答题干",
            options = emptyList(),
            answers = emptyList(),
            referenceAnswer = "参考"
        ).copy(confirmed = true, confirmedAt = 1L)
        val session = practiceSession(
            id = "s1",
            questions = listOf(q1, q2),
            libraryId = "lib-a",
            status = PracticeStatuses.ACTIVE,
            phase = PracticePhases.ANSWERING,
            currentIndex = 1,
            createdAt = 1_000L
        ).copy(
            draft = PracticeDraft(text = "草稿", referenceRevealed = true),
            draftRevision = 2L,
            updatedAt = 2_000L
        )
        val answer = BackupAnswer(
            answer = practiceAnswer(
                id = "a1",
                question = q1,
                kind = PracticeAnswerKinds.AUTOMATIC,
                result = PracticeResults.CORRECT,
                submittedAt = 1_500L,
                sessionId = "s1",
                position = 0,
                userAnswer = PracticeUserAnswer(selectedKeys = listOf("A"))
            ),
            referenceSnapshot = q1.toPracticeSnapshot()
        )
        val pdf = "%PDF-1.4\n%%EOF\n".toByteArray()
        val source: BackupSource = object : BackupSource {
            override suspend fun libraries(): List<BackupLibraryHeader> = listOf(
                BackupLibraryHeader(
                    record = testLibrary("lib-a", "反例题库").copy(schemaVersion = 3),
                    questionCount = 2,
                    sessionCount = 1,
                    answerCount = 1,
                    pdfAvailable = true
                )
            )
            override suspend fun questions(libraryId: String) = listOf(q1, q2)
            override suspend fun sessions(libraryId: String) = listOf(session)
            override suspend fun answers(libraryId: String) = listOf(answer)
            override suspend fun openPdf(libraryId: String): BackupPdfSource =
                BackupPdfSource { ByteArrayInputStream(pdf) }
        }
    }

    private suspend fun export(source: BackupSource): ByteArray {
        val output = ByteArrayOutputStream()
        BackupWriter().write(source, output, databaseVersion = 3, now = 1_000_000L)
        return output.toByteArray()
    }

    private fun readZip(bytes: ByteArray): MutableList<Pair<String, ByteArray>> {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val data = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    data.write(buffer, 0, read)
                }
                entries += entry.name to data.toByteArray()
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun writeZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    /**
     * Minimal STORED ZIP writer used only to create duplicate-name entries (java.util.zip refuses
     * them). ZipInputStream reads local headers, which is enough for the rejection test.
     */
    private fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        val central = mutableListOf<ByteArray>()
        for ((name, data) in entries) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val crc = java.util.zip.CRC32().apply { update(data) }.value
            val offset = output.size()
            le32(output, 0x04034b50L)
            le16(output, 20)
            le16(output, 0x0800)
            le16(output, 0)
            le16(output, 0)
            le16(output, 0)
            le32(output, crc)
            le32(output, data.size.toLong())
            le32(output, data.size.toLong())
            le16(output, nameBytes.size)
            le16(output, 0)
            output.write(nameBytes)
            output.write(data)

            val item = ByteArrayOutputStream()
            le32(item, 0x02014b50L)
            le16(item, 20)
            le16(item, 20)
            le16(item, 0x0800)
            le16(item, 0)
            le16(item, 0)
            le16(item, 0)
            le32(item, crc)
            le32(item, data.size.toLong())
            le32(item, data.size.toLong())
            le16(item, nameBytes.size)
            le16(item, 0)
            le16(item, 0)
            le16(item, 0)
            le16(item, 0)
            le32(item, 0)
            le32(item, offset.toLong())
            item.write(nameBytes)
            central += item.toByteArray()
        }
        val centralOffset = output.size()
        var centralSize = 0
        for (item in central) {
            output.write(item)
            centralSize += item.size
        }
        le32(output, 0x06054b50L)
        le16(output, 0)
        le16(output, 0)
        le16(output, entries.size)
        le16(output, entries.size)
        le32(output, centralSize.toLong())
        le32(output, centralOffset.toLong())
        le16(output, 0)
        return output.toByteArray()
    }

    private fun le16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        for (i in 0..(haystack.size - needle.size)) {
            var match = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    private fun le32(out: ByteArrayOutputStream, value: Long) {
        out.write((value and 0xff).toInt())
        out.write(((value ushr 8) and 0xff).toInt())
        out.write(((value ushr 16) and 0xff).toInt())
        out.write(((value ushr 24) and 0xff).toInt())
    }

    private fun updateManifest(bytes: ByteArray, block: (JSONObject) -> Unit): ByteArray {
        val entries = readZip(bytes)
        val index = entries.indexOfFirst { it.first == "manifest.json" }
        val manifest = JSONObject(String(entries[index].second, Charsets.UTF_8))
        block(manifest)
        entries[index] = "manifest.json" to manifest.toString().toByteArray(Charsets.UTF_8)
        return writeZip(entries)
    }

    private fun updateLine(
        bytes: ByteArray,
        entryName: String,
        index: Int,
        block: (JSONObject) -> Unit
    ): ByteArray {
        val entries = readZip(bytes)
        val entryIndex = entries.indexOfFirst { it.first == entryName }
        val text = String(entries[entryIndex].second, Charsets.UTF_8)
        val lines = text.trimEnd('\n').split('\n').toMutableList()
        val obj = JSONObject(lines[index])
        block(obj)
        lines[index] = obj.toString()
        entries[entryIndex] = entryName to (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
        return writeZip(entries)
    }

    private fun replaceEntry(bytes: ByteArray, entryName: String, data: ByteArray): ByteArray {
        val entries = readZip(bytes)
        val index = entries.indexOfFirst { it.first == entryName }
        entries[index] = entryName to data
        return writeZip(entries)
    }

    private fun assertRejected(
        expected: BackupError,
        bytes: ByteArray,
        limits: BackupLimits = BackupLimits()
    ) {
        try {
            BackupReader(limits).read(ByteArrayInputStream(bytes))
            fail("expected BackupException($expected)")
        } catch (e: BackupException) {
            assertEquals("unexpected error ${e.message}", expected, e.error)
        }
    }

    @Test
    fun futureFormatVersionIsRejected() = runTest {
        val bytes = export(Fixture().source)
        assertRejected(
            BackupError.FUTURE_VERSION,
            updateManifest(bytes) { it.put("formatVersion", com.offlinequiz.app.backup.BACKUP_FORMAT_VERSION + 1) }
        )
    }

    @Test
    fun versionOneWithoutTextFieldStillRestores() = runTest {
        val old = updateManifest(export(Fixture().source)) { manifest ->
            manifest.put("formatVersion", 1)
            val libraries = manifest.getJSONArray("libraries")
            for (i in 0 until libraries.length()) libraries.getJSONObject(i).remove("textIncluded")
        }
        val restored = BackupReader().read(ByteArrayInputStream(old))
        assertTrue(restored.libraries.isNotEmpty())
        assertTrue(restored.libraries.all { it.record.sourceText == null })
    }

    @Test
    fun duplicateQuestionIdIsRejected() = runTest {
        val bytes = export(Fixture().source)
        val bad = updateLine(bytes, "libraries/0/questions.ndjson", 1) { it.put("id", "q1") }
        assertRejected(BackupError.DUPLICATE_ID, bad)
    }

    @Test
    fun danglingAnswerSessionIsRejected() = runTest {
        val bytes = export(Fixture().source)
        val bad = updateLine(bytes, "libraries/0/answers.ndjson", 0) {
            it.put("sessionId", "missing-session")
        }
        assertRejected(BackupError.DANGLING_REF, bad)
    }

    @Test
    fun illegalCurrentIndexIsRejected() = runTest {
        val bytes = export(Fixture().source)
        val bad = updateLine(bytes, "libraries/0/sessions.ndjson", 0) {
            it.put("currentIndex", 99)
        }
        assertRejected(BackupError.BAD_INDEX, bad)
    }

    @Test
    fun corruptPdfResourceIsRejected() = runTest {
        val bytes = export(Fixture().source)
        assertRejected(
            BackupError.BAD_RESOURCE,
            replaceEntry(bytes, "libraries/0/resources/source.pdf", "not a pdf".toByteArray())
        )
    }

    @Test
    fun pathTraversalIsRejected() = runTest {
        val bytes = export(Fixture().source)
        val bad = updateManifest(bytes) { manifest ->
            manifest.getJSONArray("entries").getJSONObject(0).put("path", "../evil.ndjson")
        }
        assertRejected(BackupError.BAD_PATH, bad)
    }

    @Test
    fun absolutePathIsRejected() = runTest {
        val bytes = export(Fixture().source)
        val bad = updateManifest(bytes) { manifest ->
            manifest.getJSONArray("entries").getJSONObject(0).put("path", "/tmp/evil.ndjson")
        }
        assertRejected(BackupError.BAD_PATH, bad)
    }

    @Test
    fun unknownZipEntryIsRejected() = runTest {
        val bytes = export(Fixture().source)
        val entries = readZip(bytes)
        entries += "libraries/0/extra.txt" to "x".toByteArray()
        assertRejected(BackupError.UNKNOWN_ENTRY, writeZip(entries))
    }

    @Test
    fun duplicateManifestEntryIsRejected() = runTest {
        val bytes = export(Fixture().source)
        val bad = updateManifest(bytes) { manifest ->
            val entries = manifest.getJSONArray("entries")
            entries.put(entries.getJSONObject(0))
        }
        assertRejected(BackupError.DUPLICATE_ENTRY, bad)
    }

    @Test
    fun duplicateZipEntryIsRejected() = runTest {
        val bytes = export(Fixture().source)
        val entries = readZip(bytes)
        entries += entries.first { it.first == "libraries/0/questions.ndjson" }
        assertRejected(BackupError.DUPLICATE_ENTRY, storedZip(entries))
    }

    @Test
    fun lyingPdfSizeIsRejected() = runTest {
        val bytes = export(Fixture().source)
        val bad = updateManifest(bytes) { manifest ->
            val pdf = manifest.getJSONArray("libraries").getJSONObject(0).getJSONObject("pdf")
            pdf.put("byteSize", pdf.getLong("byteSize") + 1)
        }
        assertRejected(BackupError.CHECKSUM_MISMATCH, bad)
    }

    @Test
    fun overEntryLimitIsRejected() = runTest {
        val bytes = export(Fixture().source)
        assertRejected(BackupError.TOO_LARGE, bytes, BackupLimits(maxEntryBytes = 32))
    }

    @Test
    fun truncatedJsonIsRejected() = runTest {
        val bytes = export(Fixture().source)
        val bad = replaceEntry(
            bytes,
            "libraries/0/questions.ndjson",
            "{\"id\":".toByteArray()
        )
        assertRejected(BackupError.BAD_JSON, bad)
    }

    @Test
    fun invalidUtf8IsRejected() = runTest {
        val bytes = export(Fixture().source)
        val bad = replaceEntry(
            bytes,
            "libraries/0/questions.ndjson",
            byteArrayOf(0xC3.toByte(), 0x28)
        )
        assertRejected(BackupError.BAD_JSON, bad)
    }

    @Test
    fun missingEntryIsRejected() = runTest {
        val bytes = export(Fixture().source)
        val entries = readZip(bytes).filterNot { it.first == "libraries/0/answers.ndjson" }.toMutableList()
        assertRejected(BackupError.MISSING_ENTRY, writeZip(entries))
    }

    @Test
    fun crcMismatchIsRejected() = runTest {
        val bytes = export(Fixture().source)
        val stored = storedZip(readZip(bytes))
        val needle = "\"q1\"".toByteArray(Charsets.UTF_8)
        val index = indexOf(stored, needle)
        assertTrue("fixture should contain q1", index >= 0)
        stored[index + 1] = 'x'.code.toByte()
        assertRejected(BackupError.CHECKSUM_MISMATCH, stored)
    }

    @Test
    fun truncatedZipIsRejected() = runTest {
        val bytes = export(Fixture().source)
        val truncated = bytes.copyOf(bytes.size / 2)
        try {
            BackupReader().read(ByteArrayInputStream(truncated))
            fail("expected a rejected truncated ZIP")
        } catch (_: BackupException) {
            assertTrue(true)
        }
    }

    @Test
    fun cancelledExportLeavesNoUsableOutput() = runTest {
        val source = object : BackupSource {
            override suspend fun libraries(): List<BackupLibraryHeader> {
                delay(1_000)
                return emptyList()
            }

            override suspend fun questions(libraryId: String): List<StoredQuestion> = error("not reached")
            override suspend fun sessions(libraryId: String): List<PracticeSessionRecord> = error("not reached")
            override suspend fun answers(libraryId: String): List<BackupAnswer> = error("not reached")
            override suspend fun openPdf(libraryId: String): BackupPdfSource? = error("not reached")
        }
        val output = ByteArrayOutputStream()
        val job = launch { BackupWriter().write(source, output, databaseVersion = 3) }
        runCurrent()
        job.cancel()
        assertTrue(job.isCancelled)
        assertEquals(0, output.size())
    }
}
