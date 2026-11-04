package com.offlinequiz.app

import com.offlinequiz.app.backup.BackupAnswer
import com.offlinequiz.app.backup.BackupBundle
import com.offlinequiz.app.backup.BackupError
import com.offlinequiz.app.backup.BackupException
import com.offlinequiz.app.backup.BackupIdFactory
import com.offlinequiz.app.backup.BackupLibraryHeader
import com.offlinequiz.app.backup.BackupPdfSource
import com.offlinequiz.app.backup.BackupReader
import com.offlinequiz.app.backup.BackupSource
import com.offlinequiz.app.backup.BackupWriter
import com.offlinequiz.app.backup.RestorePlanner
import com.offlinequiz.app.data.PracticeRepository
import com.offlinequiz.app.data.StartPracticeOutcome
import com.offlinequiz.app.data.SubmitPracticeOutcome
import com.offlinequiz.app.model.LibraryRecord
import com.offlinequiz.app.model.PracticeAnswerKinds
import com.offlinequiz.app.model.PracticeModes
import com.offlinequiz.app.model.PracticePhases
import com.offlinequiz.app.model.PracticeQuestionSnapshot
import com.offlinequiz.app.model.PracticeResults
import com.offlinequiz.app.model.PracticeSessionRecord
import com.offlinequiz.app.model.PracticeStatuses
import com.offlinequiz.app.model.PracticeUserAnswer
import com.offlinequiz.app.model.StoredQuestion
import com.offlinequiz.app.model.toPracticeSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Production-semantics regression: answer.position is the session index, snapshot.position is the
 * library position; progress must match the phase/currentIndex contract.
 */
class BackupSessionSemanticsTest {

    private fun source(
        record: LibraryRecord,
        questions: List<StoredQuestion>,
        sessions: List<PracticeSessionRecord>,
        answers: List<BackupAnswer>
    ): BackupSource = object : BackupSource {
        override suspend fun libraries(): List<BackupLibraryHeader> = listOf(
            BackupLibraryHeader(record, questions.size, sessions.size, answers.size, false)
        )
        override suspend fun questions(libraryId: String) = questions
        override suspend fun sessions(libraryId: String) = sessions
        override suspend fun answers(libraryId: String) = answers
        override suspend fun openPdf(libraryId: String): BackupPdfSource? = null
    }

    private suspend fun export(source: BackupSource): BackupBundle {
        val output = ByteArrayOutputStream()
        BackupWriter().write(source, output, databaseVersion = 3, now = 1_000_000L)
        return BackupReader().read(ByteArrayInputStream(output.toByteArray()))
    }

    private suspend fun assertWriterRejects(expected: BackupError, source: BackupSource) {
        try {
            val output = ByteArrayOutputStream()
            BackupWriter().write(source, output, databaseVersion = 3, now = 1_000_000L)
            fail("expected BackupException($expected)")
        } catch (e: BackupException) {
            assertEquals(e.message, expected, e.error)
        }
    }

    private fun library(questions: List<StoredQuestion>): LibraryRecord =
        testLibrary("lib", "语义测试题库").copy(schemaVersion = 3)

    private fun answer(
        session: PracticeSessionRecord,
        question: StoredQuestion,
        position: Int,
        id: String = "a-$position",
        snapshot: PracticeQuestionSnapshot? = session.questions[position]
    ): BackupAnswer = BackupAnswer(
        answer = practiceAnswer(
            id = id,
            question = question,
            kind = PracticeAnswerKinds.SKIPPED,
            result = PracticeResults.SKIPPED,
            submittedAt = 2_000L,
            sessionId = session.id,
            position = position,
            userAnswer = PracticeUserAnswer()
        ),
        referenceSnapshot = snapshot
    )

    @Test
    fun realWrongbookSubsetKeepsLibraryPositionAndSessionIndex() = runTest {
        val q = practiceQuestion("q5", position = 5).copy(libraryId = "lib")
        val libraryStore = TestLibraryStore()
        libraryStore.addLibrary(testLibrary("lib"))
        libraryStore.addQuestion(q)
        val practiceStore = TestPracticeStore()
        var sequence = 0
        val repository = PracticeRepository(
            libraryStore,
            practiceStore,
            clock = { 1_000L },
            idFactory = { "id-${sequence++}" }
        )
        val started = repository.startWrongbook("lib", PracticeModes.SEQUENTIAL, listOf(q.id))
        assertTrue("wrongbook start must succeed: $started", started is StartPracticeOutcome.Started)
        val sessionId = (started as StartPracticeOutcome.Started).sessionId
        assertTrue(repository.skip(sessionId, 0, PracticeUserAnswer()) is SubmitPracticeOutcome.Recorded)
        val session = practiceStore.session(sessionId)!!
        val answer = practiceStore.answers(sessionId).single()
        val snapshot = practiceStore.snapshotByAnswer[answer.id]!!

        assertEquals("session index", 0, answer.position)
        assertEquals("library position", 5, snapshot.position)
        assertEquals(PracticePhases.FEEDBACK, session.phase)
        assertEquals(0, session.currentIndex)

        val bundle = export(source(library(listOf(q)), listOf(q), listOf(session), listOf(BackupAnswer(answer, snapshot))))
        val stored = bundle.libraries.single()
        assertEquals(1, stored.answers.size)
        assertEquals(0, stored.answers.single().answer.position)
        assertEquals(5, stored.answers.single().referenceSnapshot!!.position)

        var counter = 0
        val plan = RestorePlanner.plan(bundle, BackupIdFactory { "new-${counter++}" }, 3)
        val restored = plan.libraries.single()
        assertEquals(5, restored.sessions.single().questions.single().position)
        val restoredAnswer = restored.answers.single()
        assertEquals(0, restoredAnswer.answer.position)
        assertEquals(restored.sessions.single().questions.single().questionId, restoredAnswer.answer.questionId)
        assertEquals(5, restoredAnswer.referenceSnapshot!!.position)
        assertEquals("${restored.sessions.single().id}:0", restoredAnswer.idempotencyKey)
    }

    @Test
    fun randomReorderedSessionKeepsSessionOrderAndAnswerIndexes() = runTest {
        val questions = (0..2).map { index ->
            practiceQuestion("q$index", position = index).copy(libraryId = "lib")
        }
        val libraryStore = TestLibraryStore()
        libraryStore.addLibrary(testLibrary("lib"))
        for (question in questions) libraryStore.addQuestion(question)
        val practiceStore = TestPracticeStore()
        var sequence = 0
        val repository = PracticeRepository(
            libraryStore,
            practiceStore,
            clock = { 1_000L },
            idFactory = { "id-${sequence++}" }
        )
        val started = repository.start("lib", PracticeModes.RANDOM, Random(7))
        assertTrue(started is StartPracticeOutcome.Started)
        val sessionId = (started as StartPracticeOutcome.Started).sessionId
        var session = practiceStore.session(sessionId)!!
        var guard = 0
        while (session.phase != PracticePhases.COMPLETED) {
            assertEquals(PracticePhases.ANSWERING, session.phase)
            val current = session.currentIndex
            assertTrue(repository.skip(sessionId, current, PracticeUserAnswer()) is SubmitPracticeOutcome.Recorded)
            assertTrue(repository.advance(sessionId, current) is com.offlinequiz.app.data.AdvancePracticeOutcome.Advanced)
            session = practiceStore.session(sessionId)!!
            if (++guard > 10) fail("session did not complete")
        }
        val orderedPositions = session.questions.map { it.position }
        assertEquals(setOf(0, 1, 2), orderedPositions.toSet())
        assertTrue("seed must actually reorder", orderedPositions != listOf(0, 1, 2))

        val answers = practiceStore.answers(sessionId)
        assertEquals(listOf(0, 1, 2), answers.map { it.position })
        val backups = answers.map { answer ->
            BackupAnswer(answer, practiceStore.snapshotByAnswer[answer.id]!!)
        }
        val bundle = export(source(library(questions), questions, listOf(session), backups))
        val stored = bundle.libraries.single()
        assertEquals(orderedPositions, stored.sessions.single().questions.map { it.position })
        assertEquals(listOf(0, 1, 2), stored.answers.map { it.answer.position })

        var counter = 0
        val plan = RestorePlanner.plan(bundle, BackupIdFactory { "r-${counter++}" }, 3)
        val restored = plan.libraries.single()
        assertEquals(orderedPositions, restored.sessions.single().questions.map { it.position })
        assertEquals(listOf(0, 1, 2), restored.answers.map { it.answer.position })
        assertTrue(
            restored.answers.all { it.referenceSnapshot?.position == restored.sessions.single().questions[it.answer.position].position }
        )
    }

    @Test
    fun feedbackWithoutCommittedAnswerIsRejected() = runTest {
        val q = practiceQuestion("q0")
        val session = practiceSession(
            "s",
            listOf(q),
            libraryId = "lib",
            status = PracticeStatuses.ACTIVE,
            phase = PracticePhases.FEEDBACK,
            currentIndex = 0
        )
        assertWriterRejects(BackupError.BAD_INDEX, source(library(listOf(q)), listOf(q), listOf(session), emptyList()))
    }

    @Test
    fun activeAnsweringAtZeroWithoutAnswersIsAccepted() = runTest {
        val q = practiceQuestion("q0")
        val session = practiceSession(
            "s",
            listOf(q),
            libraryId = "lib",
            status = PracticeStatuses.ACTIVE,
            phase = PracticePhases.ANSWERING,
            currentIndex = 0
        )
        val bundle = export(source(library(listOf(q)), listOf(q), listOf(session), emptyList()))
        assertTrue(bundle.libraries.single().answers.isEmpty())
    }

    @Test
    fun answeringMissingEarlierAnswerIsRejected() = runTest {
        val questions = (0..2).map { practiceQuestion("q$it", position = it) }
        val session = practiceSession(
            "s",
            questions,
            libraryId = "lib",
            phase = PracticePhases.ANSWERING,
            currentIndex = 2
        )
        val answers = listOf(answer(session, questions[1], 1))
        assertWriterRejects(BackupError.BAD_INDEX, source(library(questions), questions, listOf(session), answers))
    }

    @Test
    fun answeringAnswerAtCurrentIndexIsRejected() = runTest {
        val questions = (0..2).map { practiceQuestion("q$it", position = it) }
        val session = practiceSession(
            "s",
            questions,
            libraryId = "lib",
            phase = PracticePhases.ANSWERING,
            currentIndex = 2
        )
        val answers = (0..2).map { answer(session, questions[it], it) }
        assertWriterRejects(BackupError.BAD_INDEX, source(library(questions), questions, listOf(session), answers))
    }

    @Test
    fun completedMissingAnswerIsRejected() = runTest {
        val questions = (0..2).map { practiceQuestion("q$it", position = it) }
        val session = practiceSession(
            "s",
            questions,
            libraryId = "lib",
            status = PracticeStatuses.COMPLETED,
            phase = PracticePhases.COMPLETED,
            currentIndex = 3
        )
        val answers = listOf(answer(session, questions[0], 0), answer(session, questions[1], 1))
        assertWriterRejects(BackupError.BAD_INDEX, source(library(questions), questions, listOf(session), answers))
    }

    @Test
    fun supersededFeedbackMissingAnswerIsRejected() = runTest {
        val questions = (0..1).map { practiceQuestion("q$it", position = it) }
        val session = practiceSession(
            "s",
            questions,
            libraryId = "lib",
            status = PracticeStatuses.SUPERSEDED,
            phase = PracticePhases.FEEDBACK,
            currentIndex = 1
        )
        val answers = listOf(answer(session, questions[0], 0))
        assertWriterRejects(BackupError.BAD_INDEX, source(library(questions), questions, listOf(session), answers))
    }

    @Test
    fun supersededAnsweringAtZeroWithoutAnswersIsAccepted() = runTest {
        val questions = (0..1).map { practiceQuestion("q$it", position = it) }
        val session = practiceSession(
            "s",
            questions,
            libraryId = "lib",
            status = PracticeStatuses.SUPERSEDED,
            phase = PracticePhases.ANSWERING,
            currentIndex = 0
        )
        val bundle = export(source(library(questions), questions, listOf(session), emptyList()))
        assertEquals(PracticeStatuses.SUPERSEDED, bundle.libraries.single().sessions.single().status)
    }

    @Test
    fun feedbackWithNullHistoricalSnapshotIsAccepted() = runTest {
        val q = practiceQuestion("q0")
        val session = practiceSession(
            "s",
            listOf(q),
            libraryId = "lib",
            phase = PracticePhases.FEEDBACK,
            currentIndex = 0
        )
        val answer = answer(session, q, 0, snapshot = null)
        val bundle = export(source(library(listOf(q)), listOf(q), listOf(session), listOf(answer)))
        assertNull(bundle.libraries.single().answers.single().referenceSnapshot)
    }

    @Test
    fun referenceSnapshotContentMismatchIsRejected() = runTest {
        val q = practiceQuestion("q0")
        val session = practiceSession(
            "s",
            listOf(q),
            libraryId = "lib",
            phase = PracticePhases.FEEDBACK,
            currentIndex = 0
        )
        val editedSnapshot = q.copy(stem = "会话开始后改过的题干").toPracticeSnapshot()
        val answer = answer(session, q, 0, snapshot = editedSnapshot)
        assertWriterRejects(BackupError.DANGLING_REF, source(library(listOf(q)), listOf(q), listOf(session), listOf(answer)))
    }

    @Test
    fun snapshotLibraryPositionMayDifferFromAnswerIndex() = runTest {
        val q = practiceQuestion("q5", position = 5)
        val session = practiceSession(
            "s",
            listOf(q),
            libraryId = "lib",
            phase = PracticePhases.FEEDBACK,
            currentIndex = 0
        )
        val answer = answer(session, q, 0)
        assertNotNull(answer.referenceSnapshot)
        val bundle = export(source(library(listOf(q)), listOf(q), listOf(session), listOf(answer)))
        assertEquals(5, bundle.libraries.single().answers.single().referenceSnapshot!!.position)
        assertEquals(0, bundle.libraries.single().answers.single().answer.position)
    }
}
