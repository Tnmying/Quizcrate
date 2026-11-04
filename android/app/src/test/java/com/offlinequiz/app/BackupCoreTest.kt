package com.offlinequiz.app

import com.offlinequiz.app.backup.BackupAnswer
import com.offlinequiz.app.backup.BackupIdFactory
import com.offlinequiz.app.backup.BackupLibraryHeader
import com.offlinequiz.app.backup.BackupPdfSource
import com.offlinequiz.app.backup.BackupReader
import com.offlinequiz.app.backup.BackupSource
import com.offlinequiz.app.backup.BackupWriter
import com.offlinequiz.app.backup.RestorePlanner
import com.offlinequiz.app.model.BlankSegment
import com.offlinequiz.app.model.BlankSpan
import com.offlinequiz.app.model.LearningBlankRef
import com.offlinequiz.app.model.PracticeAnswerKinds
import com.offlinequiz.app.model.PracticeDraft
import com.offlinequiz.app.model.PracticePhases
import com.offlinequiz.app.model.PracticeQuestionSnapshot
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** End-to-end synthetic backup: export → strict read → validation → restore-as-new-copy plan. */
class BackupCoreTest {

    private class MemorySource(
        val headers: List<BackupLibraryHeader>,
        private val questions: Map<String, List<StoredQuestion>>,
        private val sessions: Map<String, List<PracticeSessionRecord>>,
        private val answers: Map<String, List<BackupAnswer>>,
        private val pdfs: Map<String, ByteArray>
    ) : BackupSource {
        override suspend fun libraries(): List<BackupLibraryHeader> = headers
        override suspend fun questions(libraryId: String): List<StoredQuestion> = questions.getValue(libraryId)
        override suspend fun sessions(libraryId: String): List<PracticeSessionRecord> = sessions.getValue(libraryId)
        override suspend fun answers(libraryId: String): List<BackupAnswer> = answers.getValue(libraryId)
        override suspend fun openPdf(libraryId: String): BackupPdfSource? = pdfs[libraryId]?.let { bytes ->
            BackupPdfSource { ByteArrayInputStream(bytes) }
        }
    }

    private class Fixture(
        val source: MemorySource,
        val questionsA: List<StoredQuestion>,
        val sessionsA: List<PracticeSessionRecord>,
        val answersA: List<BackupAnswer>,
        val pdf: ByteArray
    )

    private fun fixture(): Fixture {
        val q1 = testQuestion(
            id = "q1",
            libraryId = "lib-a",
            position = 0,
            type = QuestionTypes.SINGLE_CHOICE,
            stem = "单选题干",
            options = listOf(QuizOption("A", "甲"), QuizOption("B", "乙")),
            answers = listOf("A")
        ).copy(confirmed = true, confirmedAt = 1L)
        val q2 = testQuestion(
            id = "q2",
            libraryId = "lib-a",
            position = 1,
            type = QuestionTypes.MULTIPLE_CHOICE,
            stem = "多选题干",
            options = listOf(QuizOption("A", "甲"), QuizOption("B", "乙"), QuizOption("C", "丙")),
            answers = listOf("A", "C")
        ).copy(confirmed = true, confirmedAt = 1L)
        val q3 = testQuestion(
            id = "q3",
            libraryId = "lib-a",
            position = 2,
            type = QuestionTypes.TRUE_FALSE,
            stem = "判断题干",
            options = emptyList(),
            answers = listOf("√")
        ).copy(confirmed = true, confirmedAt = 1L)
        val blank = BlankSpan(
            answer = "甲、乙、丙",
            originalAnswer = "甲、乙、丙",
            crossLine = true,
            splitByEnumeration = true,
            sentenceStart = 2,
            sentenceEnd = 12,
            segments = listOf(BlankSegment(1, 3, 1.5f, 20.5f, 3.25f, "甲、乙、丙")),
            learningBlanks = listOf(
                LearningBlankRef("甲", 2, 3),
                LearningBlankRef("乙", 4, 5),
                LearningBlankRef("丙", 6, 7)
            )
        )
        val q4 = testQuestion(
            id = "q4",
            libraryId = "lib-a",
            position = 3,
            type = QuestionTypes.FILL_BLANK,
            stem = "填空____、____、____。",
            answers = listOf("甲", "乙", "丙"),
            acceptableAnswers = listOf(listOf("甲", "a"), listOf("乙"), listOf("丙", "c")),
            blanks = listOf(blank)
        ).copy(confirmed = true, confirmedAt = 1L)
        val q5 = testQuestion(
            id = "q5",
            libraryId = "lib-a",
            position = 4,
            type = QuestionTypes.SHORT_ANSWER,
            stem = "简答题干",
            options = emptyList(),
            answers = emptyList(),
            referenceAnswer = "参考答案：甲乙丙"
        ).copy(confirmed = true, confirmedAt = 1L)
        val q6Pending = testQuestion(
            id = "q6",
            libraryId = "lib-a",
            position = 5,
            type = QuestionTypes.SINGLE_CHOICE,
            stem = "待校对的题目",
            options = listOf(QuizOption("A", "甲"), QuizOption("B", "乙")),
            answers = listOf("A"),
            confirmed = false,
            originalReviewReasons = listOf("empty_stem")
        )
        // The library has the edited stem; the finished session still carries the old snapshot.
        val q1Edited = q1.copy(stem = "改题后的新题干", editedAt = 9_000L)
        val questionsA = listOf(q1Edited, q2, q3, q4, q5, q6Pending)

        val s1 = practiceSession(
            id = "s1",
            questions = listOf(q1, q2, q3, q4, q5, q6Pending),
            libraryId = "lib-a",
            status = PracticeStatuses.ACTIVE,
            phase = PracticePhases.ANSWERING,
            currentIndex = 1,
            createdAt = 1_000L
        ).copy(
            draft = PracticeDraft(
                selectedKeys = listOf("B"),
                blankAnswers = listOf("甲"),
                text = "未完成草稿",
                referenceRevealed = true
            ),
            draftRevision = 7L,
            updatedAt = 2_000L
        )
        val s2 = practiceSession(
            id = "s2",
            questions = listOf(q1, q2, q3, q4, q5, q6Pending),
            libraryId = "lib-a",
            status = PracticeStatuses.COMPLETED,
            phase = PracticePhases.COMPLETED,
            currentIndex = 6,
            createdAt = 1_000L
        ).copy(updatedAt = 5_000L, completedAt = 5_000L)
        val s3 = practiceSession(
            id = "s3",
            questions = listOf(q1, q2, q3, q4, q5, q6Pending),
            libraryId = "lib-a",
            status = PracticeStatuses.SUPERSEDED,
            phase = PracticePhases.FEEDBACK,
            currentIndex = 1,
            source = PracticeSources.WRONGBOOK,
            createdAt = 1_000L
        ).copy(updatedAt = 3_000L)

        fun backupAnswer(
            id: String,
            sessionId: String,
            position: Int,
            question: StoredQuestion,
            kind: String,
            result: String,
            at: Long,
            snapshot: PracticeQuestionSnapshot?
        ): BackupAnswer = BackupAnswer(
            answer = practiceAnswer(
                id = id,
                question = question,
                kind = kind,
                result = result,
                submittedAt = at,
                sessionId = sessionId,
                position = position,
                userAnswer = PracticeUserAnswer(
                    selectedKeys = if (kind == PracticeAnswerKinds.AUTOMATIC) question.answers.take(1) else emptyList(),
                    blankAnswers = if (question.type == QuestionTypes.FILL_BLANK) listOf("甲", "乙", "丙") else emptyList(),
                    text = if (question.type == QuestionTypes.SHORT_ANSWER) "我的简答" else ""
                )
            ),
            referenceSnapshot = snapshot
        )

        val answersA = listOf(
            backupAnswer(
                "a1", "s1", 0, q1, PracticeAnswerKinds.AUTOMATIC, PracticeResults.CORRECT, 1_500L,
                q1.toPracticeSnapshot()
            ),
            backupAnswer(
                "a2", "s2", 0, q1, PracticeAnswerKinds.AUTOMATIC, PracticeResults.CORRECT, 2_000L,
                q1.toPracticeSnapshot()
            ),
            // Missing historical snapshot: preserved as unavailable, never fabricated.
            backupAnswer(
                "a3", "s2", 1, q2, PracticeAnswerKinds.AUTOMATIC, PracticeResults.INCORRECT, 2_100L, null
            ),
            backupAnswer(
                "a4", "s2", 2, q3, PracticeAnswerKinds.SKIPPED, PracticeResults.SKIPPED, 2_200L,
                q3.toPracticeSnapshot()
            ),
            backupAnswer(
                "a5", "s2", 3, q4, PracticeAnswerKinds.SELF_ASSESSMENT, PracticeResults.REMEMBERED, 2_300L,
                q4.toPracticeSnapshot()
            ),
            backupAnswer(
                "a6", "s2", 4, q5, PracticeAnswerKinds.SELF_ASSESSMENT, PracticeResults.FORGOTTEN, 2_400L,
                q5.toPracticeSnapshot()
            ),
            backupAnswer(
                "a7", "s2", 5, q6Pending, PracticeAnswerKinds.AUTOMATIC, PracticeResults.CORRECT, 2_500L,
                q6Pending.toPracticeSnapshot()
            ),
            backupAnswer(
                "a8", "s3", 0, q1, PracticeAnswerKinds.AUTOMATIC, PracticeResults.INCORRECT, 2_600L,
                q1.toPracticeSnapshot()
            ),
            backupAnswer(
                "a9", "s3", 1, q2, PracticeAnswerKinds.AUTOMATIC, PracticeResults.CORRECT, 2_700L,
                q2.toPracticeSnapshot()
            )
        )

        val qb = testQuestion(
            id = "qb1",
            libraryId = "lib-b",
            position = 0,
            type = QuestionTypes.SHORT_ANSWER,
            stem = "第二题库简答",
            options = emptyList(),
            answers = emptyList(),
            referenceAnswer = "第二题库参考"
        ).copy(confirmed = true, confirmedAt = 1L)
        val sb = practiceSession(
            id = "sb1",
            questions = listOf(qb),
            libraryId = "lib-b",
            status = PracticeStatuses.ACTIVE,
            phase = PracticePhases.ANSWERING,
            currentIndex = 0,
            createdAt = 1_000L
        )

        val pdf = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".toByteArray()
        val headers = listOf(
            BackupLibraryHeader(
                record = testLibrary("lib-a", "带PDF题库").copy(
                    schemaVersion = 3,
                    pdfRelativePath = "libraries/lib-a/source.pdf"
                ),
                questionCount = questionsA.size,
                sessionCount = listOf(s1, s2, s3).size,
                answerCount = answersA.size,
                pdfAvailable = true
            ),
            BackupLibraryHeader(
                record = testLibrary("lib-b", "第二题库").copy(schemaVersion = 3, pdfRelativePath = null),
                questionCount = 1,
                sessionCount = 1,
                answerCount = 0,
                pdfAvailable = false
            )
        )
        val source = MemorySource(
            headers = headers,
            questions = mapOf("lib-a" to questionsA, "lib-b" to listOf(qb)),
            sessions = mapOf("lib-a" to listOf(s1, s2, s3), "lib-b" to listOf(sb)),
            answers = mapOf("lib-a" to answersA, "lib-b" to emptyList()),
            pdfs = mapOf("lib-a" to pdf)
        )
        return Fixture(source, questionsA, listOf(s1, s2, s3), answersA, pdf)
    }

    private suspend fun export(source: BackupSource): ByteArray {
        val output = ByteArrayOutputStream()
        BackupWriter().write(source, output, databaseVersion = 3, now = 1_000_000L)
        return output.toByteArray()
    }

    @Test
    fun roundTripPreservesEveryRecordReferenceAndResource() = runTest {
        val fixture = fixture()
        val bundle = BackupReader().read(ByteArrayInputStream(export(fixture.source)))

        assertEquals(2, bundle.libraries.size)
        assertEquals(1_000_000L, bundle.manifest.createdAt)
        assertEquals(3, bundle.manifest.databaseVersion)
        val a = bundle.libraries[0]
        val b = bundle.libraries[1]
        assertEquals("lib-a", a.record.id)
        assertEquals("lib-b", b.record.id)
        assertNull(a.record.pdfRelativePath)
        assertNotNull(a.pdf)
        assertNull(b.pdf)
        assertEquals(fixture.questionsA, a.questions)
        assertEquals(fixture.sessionsA, a.sessions)
        assertEquals(fixture.answersA, a.answers)
        assertEquals(1, b.questions.size)
        assertEquals(1, b.sessions.size)
        assertTrue(b.answers.isEmpty())

        // Edited library question vs. old snapshot: both preserved, not reconciled.
        assertEquals("改题后的新题干", a.questions[0].stem)
        assertEquals("单选题干", a.sessions[0].questions[0].stem)
        // Missing historical snapshot stays null (unavailable), not fabricated.
        assertNull(a.answers.first { it.answer.id == "a3" }.referenceSnapshot)
        // Draft/feedback progress and all three statuses survive.
        assertEquals("未完成草稿", a.sessions[0].draft.text)
        assertTrue(a.sessions[0].draft.referenceRevealed)
        assertEquals(setOf("active", "completed", "superseded"), a.sessions.map { it.status }.toSet())
        // PDF payload is byte-identical.
        val pdfEntry = readZip(export(fixture.source)).first { it.first.endsWith("source.pdf") }
        assertTrue(pdfEntry.second.contentEquals(fixture.pdf))
    }

    @Test
    fun restorePreviewCountsActualAttentionInsteadOfAllUnconfirmedQuestions() = runTest {
        val questions = (0..5).map { testQuestion(id = "q$it", libraryId = "lib", position = it) } +
            testQuestion(id = "q6", libraryId = "lib", position = 6, originalReviewReasons = listOf("empty_stem"))
        val header = BackupLibraryHeader(testLibrary("lib").copy(pdfRelativePath = null), 7, 0, 0, false)
        val source = MemorySource(listOf(header), mapOf("lib" to questions), mapOf("lib" to emptyList()),
            mapOf("lib" to emptyList()), emptyMap())
        val bundle = BackupReader().read(ByteArrayInputStream(export(source)))
        var next = 0
        val plan = RestorePlanner.plan(bundle, BackupIdFactory { "restored-${next++}" }, 3)
        assertEquals(1, plan.summary.pendingReviewQuestions)
        assertTrue(plan.libraries.single().questions.none { it.confirmed })
        assertEquals(1, plan.libraries.single().questions.count(com.offlinequiz.app.model.QuestionReview::needsAttention))
    }

    @Test
    fun restorePlanRemapsEveryIdAndKeepsSemantics() = runTest {
        val fixture = fixture()
        val bundle = BackupReader().read(ByteArrayInputStream(export(fixture.source)))
        val oldIds = buildSet {
            for (library in bundle.libraries) {
                add(library.record.id)
                library.questions.forEach { add(it.id) }
                library.sessions.forEach { add(it.id) }
                library.answers.forEach { add(it.answer.id) }
            }
        }
        var counter = 0
        val factory = BackupIdFactory { "new-${counter++}" }

        val plan = RestorePlanner.plan(bundle, factory, targetSchemaVersion = 3)
        assertEquals(2, plan.summary.libraryCount)
        assertEquals(7, plan.summary.questionCount)
        assertEquals(4, plan.summary.sessionCount)
        assertEquals(9, plan.summary.answerCount)
        assertEquals(1, plan.summary.pdfIncludedLibraries)
        assertEquals(1, plan.summary.pdfMissingLibraries)
        assertEquals(1, plan.summary.pendingReviewQuestions)
        assertEquals(2, plan.summary.activeSessions)
        assertEquals(1, plan.summary.completedSessions)
        assertEquals(1, plan.summary.supersededSessions)

        val newA = plan.libraries[0]
        assertEquals(com.offlinequiz.app.model.PracticeRoundCounts(1, 0),
            com.offlinequiz.app.model.PracticeRoundCounts.fromSessions(newA.sessions))
        assertNotEquals("lib-a", newA.library.id)
        assertTrue(newA.library.id !in oldIds)
        assertEquals(3, newA.library.schemaVersion)
        assertEquals("libraries/${newA.library.id}/source.pdf", newA.library.pdfRelativePath)
        assertNotNull(newA.pdf)
        assertEquals("libraries/${newA.library.id}/source.pdf", newA.pdf!!.relativePath)

        val questionIds = newA.questions.map { it.id }.toSet()
        assertTrue(questionIds.none { it in oldIds })
        assertTrue(newA.questions.all { it.libraryId == newA.library.id })
        assertEquals("改题后的新题干", newA.questions[0].stem)

        val session = newA.sessions[0]
        assertTrue(session.id !in oldIds)
        assertEquals(newA.library.id, session.libraryId)
        assertEquals("未完成草稿", session.draft.text)
        assertTrue(session.questions.all { it.questionId in questionIds })
        assertTrue(session.questions.all { it.libraryId == newA.library.id })

        val answer = newA.answers.first { it.answer.position == 3 }
        val completedSession = newA.sessions.first { it.status == PracticeStatuses.COMPLETED }
        val fillQuestion = newA.questions[3]
        assertEquals(fillQuestion.id, answer.answer.questionId)
        assertEquals("${completedSession.id}:3", answer.idempotencyKey)
        assertEquals("${completedSession.id}:3", "${answer.answer.sessionId}:${answer.answer.position}")
        assertEquals("remembered", answer.answer.result)
        assertNotNull(answer.referenceSnapshot)
        assertEquals(answer.answer.questionId, answer.referenceSnapshot!!.questionId)
        assertEquals(newA.library.id, answer.referenceSnapshot!!.libraryId)

        // Repeated import is independent: a second plan never reuses IDs or the old library.
        counter = 0
        val plan2 = RestorePlanner.plan(bundle, BackupIdFactory { "again-${counter++}" }, 3)
        val ids1 = buildSet {
            plan.libraries.forEach {
                add(it.library.id)
                it.questions.forEach { q -> add(q.id) }
                it.sessions.forEach { s -> add(s.id) }
                it.answers.forEach { a -> add(a.answer.id) }
            }
        }
        val ids2 = buildSet {
            plan2.libraries.forEach {
                add(it.library.id)
                it.questions.forEach { q -> add(q.id) }
                it.sessions.forEach { s -> add(s.id) }
                it.answers.forEach { a -> add(a.answer.id) }
            }
        }
        assertTrue(ids1.intersect(ids2).isEmpty())
        assertTrue(ids1.none { it in oldIds })
        assertTrue(ids2.none { it in oldIds })
    }

    @Test
    fun missingPdfIsExplicitAndStillRestoresData() = runTest {
        val fixture = fixture()
        val noPdfSource = MemorySource(
            headers = listOf(fixture.source.headers[0].copy(pdfAvailable = false)),
            questions = mapOf("lib-a" to fixture.questionsA),
            sessions = mapOf("lib-a" to fixture.sessionsA),
            answers = mapOf("lib-a" to fixture.answersA),
            pdfs = emptyMap()
        )
        val bundle = BackupReader().read(ByteArrayInputStream(export(noPdfSource)))
        assertNull(bundle.libraries[0].pdf)
        assertEquals(6, bundle.libraries[0].questions.size)
        var counter = 0
        val plan = RestorePlanner.plan(bundle, BackupIdFactory { "np-${counter++}" }, 3)
        assertNull(plan.libraries[0].library.pdfRelativePath)
        assertEquals(1, plan.summary.pdfMissingLibraries)
        assertEquals(0, plan.summary.pdfIncludedLibraries)
    }

    @Test
    fun peekOriginSurvivesExportReadAndRestorePlan() = runTest {
        val f = fixture()
        val peeked = f.answersA.mapIndexed { i, a ->
            if (i == 0) a.copy(answer = a.answer.copy(kind = PracticeAnswerKinds.PEEKED,
                result = PracticeResults.FORGOTTEN)) else a
        }
        val source = MemorySource(listOf(f.source.headers[0].copy(pdfAvailable = false)),
            mapOf("lib-a" to f.questionsA), mapOf("lib-a" to f.sessionsA),
            mapOf("lib-a" to peeked), emptyMap())
        val bundle = BackupReader().read(ByteArrayInputStream(export(source)))
        val saved = bundle.libraries.single().answers.first().answer
        assertEquals(PracticeAnswerKinds.PEEKED, saved.kind)
        assertEquals(PracticeResults.FORGOTTEN, saved.result)
        var id = 0
        val restored = RestorePlanner.plan(bundle, BackupIdFactory { "peek-${id++}" }, 3)
        assertEquals(PracticeAnswerKinds.PEEKED, restored.libraries.single().answers.first().answer.kind)
    }

    @Test
    fun isolatedSingleReviewAndRegularActiveRoundSurviveBackupAndRestore() = runTest {
        val f = fixture()
        val single = f.sessionsA.first().copy(id = "single", source = PracticeSources.SINGLE_REVIEW,
            questions = f.sessionsA.first().questions.take(1), currentIndex = 0,
            phase = PracticePhases.ANSWERING, status = PracticeStatuses.ACTIVE,
            draft = PracticeDraft(), completedAt = null)
        val source = MemorySource(listOf(f.source.headers[0].copy(pdfAvailable = false,
            sessionCount = f.sessionsA.size + 1)), mapOf("lib-a" to f.questionsA),
            mapOf("lib-a" to (f.sessionsA + single)), mapOf("lib-a" to f.answersA), emptyMap())
        val bundle = BackupReader().read(ByteArrayInputStream(export(source)))
        assertEquals(PracticeSources.SINGLE_REVIEW, bundle.libraries.single().sessions.last().source)
        assertEquals(1, com.offlinequiz.app.backup.BackupValidator.activeSessionCount(bundle.libraries.single().sessions))
        var id = 0
        val restored = RestorePlanner.plan(bundle, BackupIdFactory { "single-${id++}" }, 3)
        assertEquals(1, restored.libraries.single().sessions.count { it.source == PracticeSources.SINGLE_REVIEW })
        assertEquals(2, restored.libraries.single().sessions.count { it.status == PracticeStatuses.ACTIVE })
    }

    private fun readZip(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val result = mutableListOf<Pair<String, ByteArray>>()
        java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result += entry.name to zip.readBytes()
                zip.closeEntry()
            }
        }
        return result
    }
}
