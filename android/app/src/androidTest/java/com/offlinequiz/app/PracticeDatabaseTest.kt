package com.offlinequiz.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offlinequiz.app.data.LibraryEntity
import com.offlinequiz.app.data.PracticeAnswerEntity
import com.offlinequiz.app.data.PracticeDao
import com.offlinequiz.app.data.PracticeJson
import com.offlinequiz.app.data.PracticeSessionEntity
import com.offlinequiz.app.data.QuizDatabase
import com.offlinequiz.app.data.QuizJson
import com.offlinequiz.app.model.PracticeAnswerKinds
import com.offlinequiz.app.model.PracticeDraft
import com.offlinequiz.app.model.PracticePhases
import com.offlinequiz.app.model.PracticeQuestionSnapshot
import com.offlinequiz.app.model.PracticeResults
import com.offlinequiz.app.model.PracticeStatuses
import com.offlinequiz.app.model.PracticeUserAnswer
import com.offlinequiz.app.model.QuestionTypes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-only practice transaction/idempotency/reopen checks. Compiled but not executed here.
 */
@RunWith(AndroidJUnit4::class)
class PracticeDatabaseTest {

    private lateinit var database: QuizDatabase
    private lateinit var practiceDao: PracticeDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, QuizDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        practiceDao = database.practiceDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun submitAnswerIsIdempotentAndMovesSessionToFeedback() = runBlocking {
        database.libraryDao().insertLibrary(library("lib"))
        practiceDao.insertSession(session("s1", "lib", total = 2))
        val answer = answer("a1", "s1", 0)

        val first = practiceDao.submitAnswerAndSetFeedback("s1", 0, answer)
        assertNotNull(first)
        assertEquals(false, first!!.alreadyRecorded)
        val sessionAfter = practiceDao.session("s1")!!
        assertEquals(PracticePhases.FEEDBACK, sessionAfter.phase)
        assertEquals(1, practiceDao.answers("s1").size)

        val retry = practiceDao.submitAnswerAndSetFeedback("s1", 0, answer)
        assertNotNull(retry)
        assertEquals(true, retry!!.alreadyRecorded)
        assertEquals(1, practiceDao.answers("s1").size)
    }

    @Test
    fun advanceOnlyOnceAndCompletesLastPosition() = runBlocking {
        database.libraryDao().insertLibrary(library("lib"))
        practiceDao.insertSession(session("s1", "lib", total = 1))
        practiceDao.submitAnswerAndSetFeedback("s1", 0, answer("a1", "s1", 0))

        val advanced = practiceDao.advanceSession("s1", 0, 1, completed = true, now = 50L)
        assertNotNull(advanced)
        assertEquals(PracticeStatuses.COMPLETED, advanced!!.status)
        assertEquals(PracticePhases.COMPLETED, advanced.phase)
        assertNotNull(advanced.completedAt)
        assertNull(practiceDao.advanceSession("s1", 0, 1, completed = true, now = 51L))
    }

    @Test
    fun draftRevisionPreventsOlderWriteFromWinning() = runBlocking {
        database.libraryDao().insertLibrary(library("lib"))
        practiceDao.insertSession(session("s1", "lib", total = 1))

        val newer = PracticeJson.encodeDraft(PracticeDraft(text = "新输入"))
        val older = PracticeJson.encodeDraft(PracticeDraft(text = "旧输入"))
        assertEquals(1, practiceDao.updateDraft("s1", 0, newer, revision = 2L, now = 10L))
        assertEquals(0, practiceDao.updateDraft("s1", 0, older, revision = 1L, now = 11L))

        val stored = practiceDao.session("s1")!!
        assertEquals(2L, stored.draftRevision)
        assertTrue(stored.draftJson.contains("新输入"))
    }

    @Test
    fun creatingNewSessionSupersedesActiveButKeepsAnswers() = runBlocking {
        database.libraryDao().insertLibrary(library("lib"))
        practiceDao.createSessionWithSupersede(session("s1", "lib", total = 1))
        practiceDao.submitAnswerAndSetFeedback("s1", 0, answer("a1", "s1", 0))
        practiceDao.createSessionWithSupersede(session("s2", "lib", total = 1, createdAt = 20L))

        assertEquals(PracticeStatuses.SUPERSEDED, practiceDao.session("s1")!!.status)
        assertEquals(PracticeStatuses.ACTIVE, practiceDao.session("s2")!!.status)
        assertEquals("s2", practiceDao.activeSession("lib")!!.id)
        assertEquals(1, practiceDao.answers("s1").size)
    }

    @Test
    fun practiceSessionAndAnswerSurviveDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "offlinequiz-practice-test-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        var fileDb = Room.databaseBuilder(context, QuizDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        try {
            fileDb.libraryDao().insertLibrary(library("lib"))
            fileDb.practiceDao().insertSession(session("s1", "lib", total = 1))
            fileDb.practiceDao().submitAnswerAndSetFeedback("s1", 0, answer("a1", "s1", 0))
        } finally {
            fileDb.close()
        }

        fileDb = Room.databaseBuilder(context, QuizDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        try {
            val restoredSession = fileDb.practiceDao().session("s1")
            assertNotNull(restoredSession)
            assertEquals(PracticePhases.FEEDBACK, restoredSession!!.phase)
            val restoredAnswer = fileDb.practiceDao().answerAt("s1", 0)
            assertNotNull(restoredAnswer)
            assertEquals(PracticeResults.CORRECT, restoredAnswer!!.result)
            assertEquals("correct", restoredAnswer.result)
        } finally {
            fileDb.close()
            context.deleteDatabase(name)
        }
    }

    private fun library(id: String): LibraryEntity = LibraryEntity(
        id = id,
        name = "练习题库",
        createdAt = 1L,
        updatedAt = 1L,
        sourceFileName = "demo.pdf",
        sourcePageCount = 1,
        pdfRelativePath = "libraries/$id/source.pdf",
        schemaVersion = QuizDatabase.VERSION
    )

    private fun question(id: String = "q1"): PracticeQuestionSnapshot = PracticeQuestionSnapshot(
        questionId = id,
        libraryId = "lib",
        position = 0,
        type = QuestionTypes.FILL_BLANK,
        originalNumber = "1",
        stem = "填空____",
        options = emptyList(),
        answers = listOf("甲"),
        acceptableAnswers = listOf(emptyList()),
        referenceAnswer = null,
        editedAt = 1L
    )

    private fun session(
        id: String,
        libraryId: String,
        total: Int,
        createdAt: Long = 1L
    ): PracticeSessionEntity {
        val questions = (0 until total).map { question("q$it") }
        return PracticeSessionEntity(
            id = id,
            libraryId = libraryId,
            mode = "sequential",
            status = PracticeStatuses.ACTIVE,
            questionOrderJson = QuizJson.encodeStrings(questions.map { it.questionId }),
            questionsJson = PracticeJson.encodeQuestions(questions),
            currentIndex = 0,
            phase = PracticePhases.ANSWERING,
            draftJson = "",
            draftRevision = 0L,
            createdAt = createdAt,
            updatedAt = createdAt,
            completedAt = null
        )
    }

    private fun answer(id: String, sessionId: String, position: Int): PracticeAnswerEntity {
        val question = question("q$position")
        return PracticeAnswerEntity(
            id = id,
            sessionId = sessionId,
            questionId = question.questionId,
            position = position,
            kind = PracticeAnswerKinds.AUTOMATIC,
            userAnswerJson = PracticeJson.encodeUserAnswer(
                PracticeUserAnswer(blankAnswers = listOf("甲"))
            ),
            result = PracticeResults.CORRECT,
            referenceSnapshotJson = PracticeJson.encodeQuestions(listOf(question)),
            idempotencyKey = "$sessionId:$position",
            submittedAt = 10L
        )
    }
}
