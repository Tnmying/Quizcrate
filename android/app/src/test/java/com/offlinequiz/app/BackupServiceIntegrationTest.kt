package com.offlinequiz.app

import com.offlinequiz.app.backup.BackupAnswer
import com.offlinequiz.app.backup.BackupDataStore
import com.offlinequiz.app.backup.BackupLibraryData
import com.offlinequiz.app.backup.BackupPdfSource
import com.offlinequiz.app.backup.BackupService
import com.offlinequiz.app.backup.BackupSnapshot
import com.offlinequiz.app.backup.RestorePlan
import com.offlinequiz.app.model.BlankSpan
import com.offlinequiz.app.model.LibraryRecord
import com.offlinequiz.app.model.PracticeAnswerKinds
import com.offlinequiz.app.model.PracticeDraft
import com.offlinequiz.app.model.PracticeModes
import com.offlinequiz.app.model.PracticePhases
import com.offlinequiz.app.model.PracticeResults
import com.offlinequiz.app.model.PracticeSessionRecord
import com.offlinequiz.app.model.PracticeSources
import com.offlinequiz.app.model.PracticeStatuses
import com.offlinequiz.app.model.PracticeUserAnswer
import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.model.QuizOption
import com.offlinequiz.app.model.StoredQuestion
import com.offlinequiz.app.model.toPracticeSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Full service path with two libraries, five types, three statuses, drafts and optional PDF. */
class BackupServiceIntegrationTest {

    private class Store(
        val snapshot: BackupSnapshot,
        private val pdfs: Map<String, ByteArray>
    ) : BackupDataStore {
        var inserted: RestorePlan? = null
        var calls = 0
        override suspend fun readSnapshot(): BackupSnapshot = snapshot
        override suspend fun insert(plan: RestorePlan) {
            calls += 1
            inserted = plan
        }
        override fun openPdf(record: LibraryRecord): BackupPdfSource? =
            pdfs[record.id]?.let { bytes -> BackupPdfSource { ByteArrayInputStream(bytes) } }
    }

    private fun temp(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "offlinequiz-03d2-int-$name-${UUID.randomUUID()}").apply { mkdirs() }

    private fun snapshot(): Triple<BackupSnapshot, Map<String, ByteArray>, Map<String, Any>> {
        fun q(
            id: String,
            position: Int,
            type: String,
            stem: String,
            options: List<QuizOption> = emptyList(),
            answers: List<String> = emptyList(),
            reference: String? = null,
            confirmed: Boolean = true,
            reasons: List<String> = emptyList()
        ): StoredQuestion = testQuestion(
            id = id,
            libraryId = "lib-a",
            position = position,
            type = type,
            stem = stem,
            options = options,
            answers = answers,
            referenceAnswer = reference,
            confirmed = confirmed,
            originalReviewReasons = reasons
        )
        val q1 = q("q1", 0, QuestionTypes.SINGLE_CHOICE, "单选", listOf(QuizOption("A", "甲")), listOf("A"))
        val q1Edited = q1.copy(stem = "单选（改后）", editedAt = 9_000L)
        val q2 = q("q2", 1, QuestionTypes.MULTIPLE_CHOICE, "多选", listOf(QuizOption("A", "甲"), QuizOption("B", "乙")), listOf("A", "B"))
        val q3 = q("q3", 2, QuestionTypes.TRUE_FALSE, "判断", answers = listOf("√"))
        val blank = BlankSpan("甲、乙", "甲、乙", false, true, 0, 2)
        val q4 = testQuestion(
            id = "q4", libraryId = "lib-a", position = 3, type = QuestionTypes.FILL_BLANK,
            stem = "填空____、____", answers = listOf("甲", "乙"),
            acceptableAnswers = listOf(listOf("甲"), listOf("乙")), blanks = listOf(blank)
        ).copy(confirmed = true, confirmedAt = 1L)
        val q5 = q("q5", 4, QuestionTypes.SHORT_ANSWER, "简答", reference = "参考")
        val q6 = q(
            "q6", 5, QuestionTypes.SINGLE_CHOICE, "待校对", listOf(QuizOption("A", "甲")), listOf("A"),
            confirmed = false, reasons = listOf("empty_stem")
        )
        val questions = listOf(q1Edited, q2, q3, q4, q5, q6)
        val s1 = practiceSession(
            "sa", listOf(q1, q2, q3, q4, q5, q6), libraryId = "lib-a",
            status = PracticeStatuses.ACTIVE, phase = PracticePhases.ANSWERING, currentIndex = 1
        ).copy(draft = PracticeDraft(text = "草稿", referenceRevealed = true), draftRevision = 3L, updatedAt = 2_000L)
        val s2 = practiceSession(
            "sb", listOf(q1, q2, q3, q4, q5, q6), libraryId = "lib-a",
            status = PracticeStatuses.COMPLETED, phase = PracticePhases.COMPLETED, currentIndex = 6
        ).copy(completedAt = 5_000L, updatedAt = 5_000L)
        val s3 = practiceSession(
            "sc", listOf(q1, q2, q3, q4, q5, q6), libraryId = "lib-a",
            status = PracticeStatuses.SUPERSEDED, phase = PracticePhases.FEEDBACK, currentIndex = 1,
            source = PracticeSources.WRONGBOOK
        ).copy(updatedAt = 3_000L)
        fun a(id: String, session: String, position: Int, q: StoredQuestion, kind: String, result: String, at: Long, snap: Boolean = true) =
            BackupAnswer(
                practiceAnswer(id, q, kind, result, at, sessionId = session, position = position, userAnswer = PracticeUserAnswer()),
                if (snap) q.toPracticeSnapshot() else null
            )
        val answers = listOf(
            a("a1", "sa", 0, q1, PracticeAnswerKinds.SKIPPED, PracticeResults.SKIPPED, 1_500L),
            a("a2", "sb", 0, q1, PracticeAnswerKinds.AUTOMATIC, PracticeResults.CORRECT, 2_000L),
            a("a3", "sb", 1, q2, PracticeAnswerKinds.AUTOMATIC, PracticeResults.INCORRECT, 2_100L, snap = false),
            a("a4", "sb", 2, q3, PracticeAnswerKinds.SKIPPED, PracticeResults.SKIPPED, 2_200L),
            a("a5", "sb", 3, q4, PracticeAnswerKinds.SELF_ASSESSMENT, PracticeResults.REMEMBERED, 2_300L),
            a("a6", "sb", 4, q5, PracticeAnswerKinds.SELF_ASSESSMENT, PracticeResults.FORGOTTEN, 2_400L),
            a("a7", "sb", 5, q6, PracticeAnswerKinds.SKIPPED, PracticeResults.SKIPPED, 2_500L),
            a("a8", "sc", 0, q1, PracticeAnswerKinds.AUTOMATIC, PracticeResults.INCORRECT, 2_600L),
            a("a9", "sc", 1, q2, PracticeAnswerKinds.AUTOMATIC, PracticeResults.CORRECT, 2_700L)
        )
        val qb = testQuestion(
            id = "qb", libraryId = "lib-b", position = 0, type = QuestionTypes.SHORT_ANSWER,
            stem = "第二库简答", answers = emptyList(), referenceAnswer = "参考B"
        ).copy(confirmed = true, confirmedAt = 1L)
        val sb = practiceSession(
            "s2", listOf(qb), libraryId = "lib-b",
            status = PracticeStatuses.ACTIVE, phase = PracticePhases.ANSWERING, currentIndex = 0
        )
        val snap = BackupSnapshot(
            listOf(
                BackupLibraryData(
                    testLibrary("lib-a", "完整题库").copy(schemaVersion = 3, pdfRelativePath = "libraries/lib-a/source.pdf"),
                    questions, listOf(s1, s2, s3), answers
                ),
                BackupLibraryData(
                    testLibrary("lib-b", "无PDF题库").copy(schemaVersion = 3, pdfRelativePath = null),
                    listOf(qb), listOf(sb), emptyList()
                )
            )
        )
        val pdfs = mapOf("lib-a" to "%PDF-1.4\n%%EOF\n".toByteArray())
        val meta = mapOf("original" to snap)
        return Triple(snap, pdfs, meta)
    }

    @Test
    fun serviceRoundTripPreservesTwoLibrariesAndProgress() = runTest {
        val (snap, pdfs, _) = snapshot()
        val store = Store(snap, pdfs)
        val work = temp("work")
        val pdfRoot = temp("pdf")
        val service = BackupService(store, work, pdfRoot, 3)
        val bytes = ByteArrayOutputStream().also { service.export(it) }.toByteArray()
        val prepared = service.prepare(ByteArrayInputStream(bytes))
        assertEquals(2, prepared.summary.libraryCount)
        assertEquals(7, prepared.summary.questionCount)
        assertEquals(4, prepared.summary.sessionCount)
        assertEquals(9, prepared.summary.answerCount)
        assertEquals(1, prepared.summary.pdfIncludedLibraries)
        assertEquals(1, prepared.summary.pdfMissingLibraries)
        // q1 is confirmed but has only one option; q6 has unresolved import doubt.
        // Both need attention. Confirmation must never bypass structural validation.
        assertEquals(setOf("q1", "q6"), snap.libraries.flatMap { it.questions }
            .filter(com.offlinequiz.app.model.QuestionReview::needsAttention).map { it.id }.toSet())
        assertEquals(2, prepared.summary.pendingReviewQuestions)

        val result = service.commit(prepared)
        assertEquals(2, result.libraryIds.size)
        assertEquals(1, store.calls)
        val plan = store.inserted!!
        assertEquals(2, plan.libraries.size)
        val a = plan.libraries[0]
        val b = plan.libraries[1]
        assertEquals(1, a.answers.count { it.referenceSnapshot == null })
        assertEquals("草稿", a.sessions.first { it.status == PracticeStatuses.ACTIVE }.draft.text)
        assertEquals(1, b.sessions.count { it.status == PracticeStatuses.ACTIVE })
        assertEquals(0, b.answers.size)
        assertEquals(9_000L, a.questions[0].editedAt)
        assertNotEquals("lib-a", a.library.id)
        assertTrue(File(pdfRoot, "libraries/${a.library.id}/source.pdf").isFile)
        assertTrue(File(pdfRoot, "libraries/${b.library.id}").exists().not())
        // Original snapshot object was not mutated.
        assertEquals(snap, store.snapshot)

        val second = service.commit(service.prepare(ByteArrayInputStream(bytes)))
        assertNotEquals(result.libraryIds[0], second.libraryIds[0])
        assertEquals(2, store.calls)
    }
}
