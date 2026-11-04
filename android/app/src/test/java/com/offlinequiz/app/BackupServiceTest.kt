package com.offlinequiz.app

import com.offlinequiz.app.backup.BackupAnswer
import com.offlinequiz.app.backup.BackupDataStore
import com.offlinequiz.app.backup.BackupError
import com.offlinequiz.app.backup.BackupException
import com.offlinequiz.app.backup.BackupLibraryData
import com.offlinequiz.app.backup.BackupPdfSource
import com.offlinequiz.app.backup.BackupService
import com.offlinequiz.app.backup.BackupSnapshot
import com.offlinequiz.app.backup.RestorePlan
import com.offlinequiz.app.model.LibraryRecord
import com.offlinequiz.app.model.PracticeAnswerKinds
import com.offlinequiz.app.model.PracticePhases
import com.offlinequiz.app.model.PracticeResults
import com.offlinequiz.app.model.PracticeSessionRecord
import com.offlinequiz.app.model.PracticeStatuses
import com.offlinequiz.app.model.PracticeUserAnswer
import com.offlinequiz.app.model.StoredQuestion
import com.offlinequiz.app.model.toPracticeSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class BackupServiceTest {

    private class FakeStore(
        private val snapshot: BackupSnapshot,
        private val pdfs: Map<String, ByteArray>,
        var failInsert: Boolean = false
    ) : BackupDataStore {
        var inserted: RestorePlan? = null
        var insertCalls = 0

        override suspend fun readSnapshot(): BackupSnapshot = snapshot
        override suspend fun insert(plan: RestorePlan) {
            insertCalls += 1
            if (failInsert) throw IllegalStateException("simulated insert failure")
            inserted = plan
        }

        override fun openPdf(record: LibraryRecord): BackupPdfSource? =
            pdfs[record.id]?.let { bytes -> BackupPdfSource { ByteArrayInputStream(bytes) } }
    }

    private fun dir(name: String): File {
        val file = File(System.getProperty("java.io.tmpdir"), "offlinequiz-03d2-$name-${UUID.randomUUID()}")
        file.mkdirs()
        return file
    }

    private fun fixture(): Triple<FakeStore, File, File> {
        val question = practiceQuestion("q1", position = 5).copy(libraryId = "lib")
        val session = practiceSession(
            "s",
            listOf(question),
            libraryId = "lib",
            status = PracticeStatuses.COMPLETED,
            phase = PracticePhases.COMPLETED,
            currentIndex = 1
        ).copy(completedAt = 2_000L, updatedAt = 2_000L)
        val answer = BackupAnswer(
            practiceAnswer(
                "a1",
                question,
                PracticeAnswerKinds.SKIPPED,
                PracticeResults.SKIPPED,
                2_000L,
                sessionId = "s",
                position = 0,
                userAnswer = PracticeUserAnswer()
            ),
            question.toPracticeSnapshot()
        )
        val record = testLibrary("lib").copy(schemaVersion = 3)
        val snapshot = BackupSnapshot(
            listOf(BackupLibraryData(record, listOf(question), listOf(session), listOf(answer)))
        )
        val store = FakeStore(
            snapshot,
            mapOf("lib" to "%PDF-1.4\n%%EOF\n".toByteArray())
        )
        return Triple(store, dir("work"), dir("pdf"))
    }

    private suspend fun exportBytes(service: BackupService): ByteArray {
        val output = ByteArrayOutputStream()
        service.export(output)
        return output.toByteArray()
    }

    @Test
    fun exportPreviewCommitCreatesNewCopyAndPdf() = runTest {
        val (store, work, pdfRoot) = fixture()
        val service = BackupService(store, work, pdfRoot, 3)
        val bytes = exportBytes(service)
        val prepared = service.prepare(ByteArrayInputStream(bytes))
        assertEquals(1, prepared.summary.libraryCount)
        assertEquals(1, prepared.summary.answerCount)
        assertEquals(1, prepared.summary.pdfIncludedLibraries)

        val result = service.commit(prepared)
        val plan = store.inserted!!
        assertEquals(1, plan.libraries.size)
        assertEquals(1, store.insertCalls)
        assertEquals(1, result.libraryIds.size)
        assertEquals(1, plan.libraries[0].questions.size)
        assertEquals(1, plan.libraries[0].sessions.size)
        assertEquals(1, plan.libraries[0].answers.size)
        assertEquals(5, plan.libraries[0].sessions[0].questions[0].position)
        assertEquals(0, plan.libraries[0].answers[0].answer.position)
        assertTrue(plan.libraries[0].library.name == "测试题库")
        val pdf = File(pdfRoot, "libraries/${result.libraryIds[0]}/source.pdf")
        assertTrue(pdf.isFile)
        assertTrue(prepared.committed)
        assertFalse(prepared.tempFile.exists())
    }

    @Test
    fun repeatedImportCreatesIndependentCopies() = runTest {
        val (store, work, pdfRoot) = fixture()
        val service = BackupService(store, work, pdfRoot, 3)
        val bytes = exportBytes(service)
        val first = service.commit(service.prepare(ByteArrayInputStream(bytes)))
        val second = service.commit(service.prepare(ByteArrayInputStream(bytes)))
        assertEquals(2, store.insertCalls)
        assertTrue(first.libraryIds.single() != second.libraryIds.single())
        assertTrue(File(pdfRoot, "libraries/${first.libraryIds.single()}/source.pdf").isFile)
        assertTrue(File(pdfRoot, "libraries/${second.libraryIds.single()}/source.pdf").isFile)
    }

    @Test
    fun invalidInputNeverInsertsAndCleansTemp() = runTest {
        val (store, work, pdfRoot) = fixture()
        val service = BackupService(store, work, pdfRoot, 3)
        try {
            service.prepare(ByteArrayInputStream("not a zip".toByteArray()))
            fail("expected rejection")
        } catch (e: BackupException) {
            assertTrue(e.error == BackupError.NOT_A_BACKUP || e.error == BackupError.CHECKSUM_MISMATCH)
        }
        assertEquals(0, store.insertCalls)
        assertTrue(work.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationDuringValidationCleansTempAndNeverInserts() = runTest {
        val (store, work, pdfRoot) = fixture()
        val service = BackupService(store, work, pdfRoot, 3)
        val bytes = exportBytes(service)
        try {
            service.prepare(ByteArrayInputStream(bytes)) { true }
            fail("expected cancellation")
        } catch (e: BackupException) {
            assertEquals(BackupError.CANCELLED, e.error)
        }
        assertEquals(0, store.insertCalls)
        assertTrue(work.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun insertFailureRollsBackStagedPdfsAndKeepsOriginals() = runTest {
        val (store, work, pdfRoot) = fixture()
        store.failInsert = true
        val service = BackupService(store, work, pdfRoot, 3)
        val prepared = service.prepare(ByteArrayInputStream(exportBytes(service)))
        try {
            service.commit(prepared)
            fail("expected insert failure")
        } catch (_: BackupException) {
            // expected
        }
        assertFalse(prepared.committed)
        assertEquals(1, store.insertCalls)
        assertEquals(null, store.inserted)
        val librariesDir = File(pdfRoot, "libraries")
        assertTrue(librariesDir.listFiles().orEmpty().isEmpty())
        assertFalse(prepared.tempFile.exists())
    }

    @Test
    fun lateCancelAfterCommitKeepsPdfAndDoesNotInsertAgain() = runTest {
        val (store, work, pdfRoot) = fixture()
        val service = BackupService(store, work, pdfRoot, 3)
        val prepared = service.prepare(ByteArrayInputStream(exportBytes(service)))
        val result = service.commit(prepared)
        assertTrue(prepared.committed)
        service.cancel(prepared)
        assertTrue(File(pdfRoot, "libraries/${result.libraryIds.single()}/source.pdf").isFile)
        assertEquals(1, store.insertCalls)
        // Duplicate confirm is idempotent at the service boundary.
        val again = service.commit(prepared)
        assertEquals(result.libraryIds, again.libraryIds)
        assertEquals(1, store.insertCalls)
    }

    @Test
    fun missingPdfStillRestoresData() = runTest {
        val (baseStore, work, pdfRoot) = fixture()
        val noPdf = object : BackupDataStore by baseStore {
            override fun openPdf(record: LibraryRecord): BackupPdfSource? = null
        }
        val service = BackupService(noPdf, work, pdfRoot, 3)
        val bytes = exportBytes(service)
        val prepared = service.prepare(ByteArrayInputStream(bytes))
        assertEquals(1, prepared.summary.pdfMissingLibraries)
        val result = service.commit(prepared)
        assertTrue(File(pdfRoot, "libraries").listFiles().orEmpty().isEmpty())
        assertTrue(File(pdfRoot, "libraries/${result.libraryIds.single()}").exists().not())
    }
}
