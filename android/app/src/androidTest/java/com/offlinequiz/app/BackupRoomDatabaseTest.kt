package com.offlinequiz.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offlinequiz.app.backup.BackupAnswer
import com.offlinequiz.app.backup.BackupBundle
import com.offlinequiz.app.backup.BackupException
import com.offlinequiz.app.backup.BackupIdFactory
import com.offlinequiz.app.backup.BackupLibrary
import com.offlinequiz.app.backup.BackupManifest
import com.offlinequiz.app.backup.RestorePlanner
import com.offlinequiz.app.data.BackupRows
import com.offlinequiz.app.data.PracticeAnswerEntity
import com.offlinequiz.app.data.PracticeJson
import com.offlinequiz.app.data.PrivatePdfStore
import com.offlinequiz.app.data.QuizDatabase
import com.offlinequiz.app.data.RoomBackupDataStore
import com.offlinequiz.app.data.toEntity
import com.offlinequiz.app.model.LibraryRecord
import com.offlinequiz.app.model.PracticeAnswerKinds
import com.offlinequiz.app.model.PracticeDraft
import com.offlinequiz.app.model.PracticePhases
import com.offlinequiz.app.model.PracticeQuestionSnapshot
import com.offlinequiz.app.model.PracticeResults
import com.offlinequiz.app.model.PracticeSessionRecord
import com.offlinequiz.app.model.PracticeStatuses
import com.offlinequiz.app.model.PracticeUserAnswer
import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.model.QuizOption
import com.offlinequiz.app.model.StoredQuestion
import com.offlinequiz.app.model.toPracticeSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Device-only Room boundary checks; compiled but not executed here (no device). */
@RunWith(AndroidJUnit4::class)
class BackupRoomDatabaseTest {

    private lateinit var database: QuizDatabase
    private lateinit var store: RoomBackupDataStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, QuizDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val pdfRoot = File(context.cacheDir, "backup-room-test-${System.nanoTime()}")
        pdfRoot.mkdirs()
        store = RoomBackupDataStore(database, PrivatePdfStore(pdfRoot))
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun question(): StoredQuestion = StoredQuestion(
        id = "q1",
        libraryId = "lib",
        position = 0,
        type = QuestionTypes.SINGLE_CHOICE,
        originalNumber = "1",
        numberValue = 1,
        sectionTitle = null,
        stem = "题干",
        stemWithAnswer = "题干",
        originalSentence = null,
        referenceAnswer = null,
        optionRawText = null,
        options = listOf(QuizOption("A", "甲")),
        answers = listOf("A"),
        acceptableAnswers = emptyList(),
        sourcePages = emptyList(),
        sourceLines = emptyList(),
        blanks = emptyList(),
        learningBlankCount = 0,
        originalReviewReasons = emptyList(),
        originalNeedsReview = false,
        originalStem = "题干",
        originalOptions = listOf(QuizOption("A", "甲")),
        originalAnswers = listOf("A"),
        originalBlanks = emptyList(),
        confirmed = true,
        confirmedAt = 1L,
        editedAt = null
    )

    private fun seed() = runBlocking {
        val q = question()
        val session = PracticeSessionRecord(
            id = "s1",
            libraryId = "lib",
            mode = "sequential",
            status = PracticeStatuses.COMPLETED,
            questions = listOf(q.toPracticeSnapshot()),
            currentIndex = 1,
            phase = PracticePhases.COMPLETED,
            draft = PracticeDraft(),
            draftRevision = 0L,
            createdAt = 1_000L,
            updatedAt = 2_000L,
            completedAt = 2_000L
        )
        val answer = PracticeAnswerEntity(
            id = "a1",
            sessionId = "s1",
            questionId = "q1",
            position = 0,
            kind = PracticeAnswerKinds.AUTOMATIC,
            userAnswerJson = PracticeJson.encodeUserAnswer(PracticeUserAnswer(selectedKeys = listOf("A"))),
            result = PracticeResults.CORRECT,
            referenceSnapshotJson = PracticeJson.encodeQuestions(listOf(q.toPracticeSnapshot())),
            idempotencyKey = "s1:0",
            submittedAt = 2_000L
        )
        database.backupDao().insertAll(
            BackupRows(
                libraries = listOf(
                    LibraryRecord("lib", "测试题库", 1_000L, 1_000L, "demo.pdf", 1, null, 3).toEntity()
                ),
                questions = listOf(q.toEntity()),
                sessions = listOf(session.toEntity()),
                answers = listOf(answer)
            )
        )
    }

    @Test
    fun consistentSnapshotReadsAllStatusesAndAnswers() = runBlocking {
        seed()
        val snapshot = store.readSnapshot()
        assertEquals(1, snapshot.libraries.size)
        assertEquals(1, snapshot.libraries[0].questions.size)
        assertEquals(1, snapshot.libraries[0].sessions.size)
        assertEquals(1, snapshot.libraries[0].answers.size)
        assertEquals(PracticeStatuses.COMPLETED, snapshot.libraries[0].sessions[0].status)
        assertEquals(0, snapshot.libraries[0].answers[0].answer.position)
        assertTrue(snapshot.libraries[0].answers[0].referenceSnapshot != null)
    }

    @Test
    fun aggregateInsertCommitsNewCopyWithOriginalIntact() = runBlocking {
        seed()
        val snapshot = store.readSnapshot()
        val bundle = BackupBundle(
            manifest = BackupManifest(0L, 3, emptyList(), emptyList()),
            libraries = snapshot.libraries.map { data ->
                BackupLibrary(data.record, data.questions, data.sessions, data.answers, pdf = null)
            }
        )
        var counter = 0
        val plan = RestorePlanner.plan(bundle, BackupIdFactory { "new-${counter++}" }, 3)
        store.insert(plan)
        val after = store.readSnapshot()
        assertEquals(2, after.libraries.size)
        val original = snapshot.libraries.single()
        assertEquals(original, after.libraries.single { it.record.id == original.record.id })
        val restored = after.libraries.single { it.record.id != original.record.id }
        assertEquals(original.record.name, restored.record.name)
        assertEquals(plan.libraries.single().questions, restored.questions)
        assertEquals(plan.libraries.single().sessions, restored.sessions)
        val expectedAnswers = plan.libraries.single().answers.map { BackupAnswer(it.answer, it.referenceSnapshot) }
        assertEquals(expectedAnswers, restored.answers)
    }

    @Test
    fun duplicateIdCollisionAbortsWholeInsertAndKeepsOriginal() = runBlocking {
        seed()
        val snapshot = store.readSnapshot()
        val bundle = BackupBundle(
            manifest = BackupManifest(0L, 3, emptyList(), emptyList()),
            libraries = snapshot.libraries.map { data ->
                BackupLibrary(data.record, data.questions, data.sessions, data.answers, pdf = null)
            }
        )
        var counter = 0
        val plan = RestorePlanner.plan(bundle, BackupIdFactory { "again-${counter++}" }, 3)
        store.insert(plan)
        try {
            store.insert(plan)
            throw AssertionError("expected duplicate-id failure")
        } catch (_: BackupException) {
            // expected: ABORT transaction, no partial copy
        }
        val after = store.readSnapshot()
        assertEquals(2, after.libraries.size)
        assertEquals(2, after.libraries.flatMap { it.questions }.size)
        assertEquals(2, after.libraries.flatMap { it.answers }.size)
    }
}
