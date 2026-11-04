package com.offlinequiz.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offlinequiz.app.data.LibraryEntity
import com.offlinequiz.app.data.PracticeAnswerEntity
import com.offlinequiz.app.data.PracticeJson
import com.offlinequiz.app.data.PracticeSessionEntity
import com.offlinequiz.app.data.QuizDatabase
import com.offlinequiz.app.data.QuizJson
import com.offlinequiz.app.data.RoomPracticeStore
import com.offlinequiz.app.model.PracticeAnswerKinds
import com.offlinequiz.app.model.PracticeDraft
import com.offlinequiz.app.model.PracticePhases
import com.offlinequiz.app.model.PracticeQuestionSnapshot
import com.offlinequiz.app.model.PracticeResults
import com.offlinequiz.app.model.PracticeSources
import com.offlinequiz.app.model.PracticeStatuses
import com.offlinequiz.app.model.PracticeUserAnswer
import com.offlinequiz.app.model.QuestionTypes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-only SQL checks for 03B. The JVM tests use in-memory fakes and cannot prove the JOIN,
 * ORDER BY or the new `source` column; those are verified here on a real SQLite database.
 * Compiled but not executed in this environment (no device).
 */
@RunWith(AndroidJUnit4::class)
class PracticeStatsDatabaseTest {

    private lateinit var database: QuizDatabase
    private lateinit var store: RoomPracticeStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, QuizDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomPracticeStore(database.practiceDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun historyIncludesSupersededSessionsAndIsolatesLibraries() = runBlocking {
        insertLibrary("lib-a")
        insertLibrary("lib-b")
        database.practiceDao().insertSession(session("s-old", "lib-a", PracticeStatuses.SUPERSEDED))
        database.practiceDao().insertSession(session("s-active", "lib-a", PracticeStatuses.ACTIVE))
        database.practiceDao().insertSession(session("s-b", "lib-b", PracticeStatuses.ACTIVE))
        database.practiceDao().insertAnswer(answer("a-old", "s-old", 1, PracticeResults.INCORRECT))
        database.practiceDao().insertAnswer(answer("a-new", "s-active", 2, PracticeResults.CORRECT))
        database.practiceDao().insertAnswer(answer("a-b", "s-b", 3, PracticeResults.INCORRECT))

        val historyA = store.historyForLibrary("lib-a")
        assertEquals("superseded session answers stay in history", 2, historyA.size)
        assertEquals(listOf(PracticeResults.INCORRECT, PracticeResults.CORRECT), historyA.map { it.answer.result })
        assertNotNull("the reference snapshot must be decoded for history", historyA[0].referenceSnapshot)
        assertEquals(PracticeSources.NORMAL, historyA[0].sessionSource)

        val historyB = store.historyForLibrary("lib-b")
        assertEquals(1, historyB.size)
        assertEquals("a-b", historyB.single().answer.id)
    }

    @Test
    fun historyForQuestionFiltersAndOrdersBySubmissionTime() = runBlocking {
        insertLibrary("lib-a")
        database.practiceDao().insertSession(session("s1", "lib-a", PracticeStatuses.ACTIVE))
        database.practiceDao().insertSession(session("s2", "lib-a", PracticeStatuses.SUPERSEDED))
        database.practiceDao().insertAnswer(answer("first", "s1", 1, PracticeResults.INCORRECT, questionId = "q-1", position = 0))
        database.practiceDao().insertAnswer(answer("second", "s2", 2, PracticeResults.CORRECT, questionId = "q-1", position = 0))
        database.practiceDao().insertAnswer(answer("other", "s2", 3, PracticeResults.CORRECT, questionId = "q-2", position = 1))

        val history = store.historyForQuestion("lib-a", "q-1")
        assertEquals(listOf("first", "second"), history.map { it.answer.id })
        assertTrue(history.all { it.answer.questionId == "q-1" })
    }

    @Test
    fun sessionSourceIsPersistedAndExposedByTheSummary() = runBlocking {
        insertLibrary("lib-a")
        database.practiceDao().insertSession(
            session("s-wrongbook", "lib-a", PracticeStatuses.ACTIVE, source = PracticeSources.WRONGBOOK)
        )

        val restored = store.session("s-wrongbook")
        assertNotNull(restored)
        assertEquals(PracticeSources.WRONGBOOK, restored!!.source)

        val active = store.activeSession("lib-a")
        assertNotNull(active)
        assertEquals(PracticeSources.WRONGBOOK, active!!.source)
    }

    @Test
    fun defaultSourceForNewRowsIsNormal() = runBlocking {
        insertLibrary("lib-a")
        database.practiceDao().insertSession(session("s-default", "lib-a", PracticeStatuses.ACTIVE))
        val restored = store.session("s-default")
        assertNotNull(restored)
        assertEquals(PracticeSources.NORMAL, restored!!.source)
    }

    @Test
    fun statsSnapshotCountsCompletedRoundsSeparatelyAndKeepsSkippedHistory() = runBlocking {
        insertLibrary("lib-a")
        insertLibrary("lib-b")
        val normal = session("normal", "lib-a", PracticeStatuses.COMPLETED).copy(
            phase = PracticePhases.COMPLETED, currentIndex = 1, completedAt = 10L)
        database.practiceDao().insertSession(normal)
        database.practiceDao().insertSession(normal.copy(id = "wrong", source = PracticeSources.WRONGBOOK))
        database.practiceDao().insertSession(session("active", "lib-a", PracticeStatuses.ACTIVE))
        database.practiceDao().insertSession(normal.copy(id = "superseded", status = PracticeStatuses.SUPERSEDED))
        database.practiceDao().insertSession(normal.copy(id = "no-time", completedAt = null))
        database.practiceDao().insertSession(normal.copy(id = "other", libraryId = "lib-b"))
        database.practiceDao().insertAnswer(answer("skip", "normal", 2, PracticeResults.SKIPPED).copy(kind = PracticeAnswerKinds.SKIPPED))
        database.practiceDao().insertAnswer(answer("wrong-answer", "active", 3, PracticeResults.INCORRECT))
        val snapshot = store.libraryStatsSnapshot("lib-a")
        assertEquals(com.offlinequiz.app.model.PracticeRoundCounts(1, 1), snapshot.rounds)
        assertEquals(2, snapshot.history.size)
        assertEquals(PracticeResults.SKIPPED, snapshot.history.first().answer.result)
        assertEquals(1, store.libraryStatsSnapshot("lib-b").rounds.normalCompleted)
        assertTrue(store.libraryStatsSnapshot("lib-b").history.isEmpty())
    }

    private suspend fun insertLibrary(id: String) {
        database.libraryDao().insertLibrary(
            LibraryEntity(
                id = id,
                name = "题库 $id",
                createdAt = 1L,
                updatedAt = 1L,
                sourceFileName = "demo.pdf",
                sourcePageCount = 1,
                pdfRelativePath = "libraries/$id/source.pdf",
                schemaVersion = QuizDatabase.VERSION
            )
        )
    }

    private fun question(questionId: String = "q-1"): PracticeQuestionSnapshot = PracticeQuestionSnapshot(
        questionId = questionId,
        libraryId = "lib-a",
        position = 0,
        type = QuestionTypes.SINGLE_CHOICE,
        originalNumber = "1",
        stem = "题干",
        options = emptyList(),
        answers = listOf("A"),
        acceptableAnswers = emptyList(),
        referenceAnswer = null,
        editedAt = null
    )

    @Test
    fun isolatedSingleReviewKeepsRegularSessionAndStaysOutOfRoundCounts() = runBlocking {
        insertLibrary("lib-a")
        val regular = session("regular", "lib-a", PracticeStatuses.ACTIVE).copy(
            draftJson = PracticeJson.encodeDraft(PracticeDraft(selectedKeys = listOf("B"))))
        database.practiceDao().createSessionWithSupersede(regular)
        database.practiceDao().createSessionWithSupersede(
            session("single-1", "lib-a", PracticeStatuses.ACTIVE, PracticeSources.SINGLE_REVIEW).copy(updatedAt = 10))
        database.practiceDao().createSessionWithSupersede(
            session("single-2", "lib-a", PracticeStatuses.ACTIVE, PracticeSources.SINGLE_REVIEW).copy(updatedAt = 20))
        assertEquals(regular, database.practiceDao().session("regular"))
        assertEquals("regular", database.practiceDao().activeSession("lib-a")!!.id)
        assertEquals(PracticeStatuses.SUPERSEDED, database.practiceDao().session("single-1")!!.status)
        database.practiceDao().updateSession(database.practiceDao().session("single-2")!!.copy(
            status = PracticeStatuses.COMPLETED, phase = PracticePhases.COMPLETED, currentIndex = 1, completedAt = 30))
        assertEquals(0, database.practiceDao().completedRoundCounts("lib-a").normalCompleted)
        assertEquals(0, database.practiceDao().completedRoundCounts("lib-a").wrongbookCompleted)
    }

    private fun session(
        id: String,
        libraryId: String,
        status: String,
        source: String = PracticeSources.NORMAL
    ): PracticeSessionEntity = PracticeSessionEntity(
        id = id,
        libraryId = libraryId,
        mode = "sequential",
        status = status,
        questionOrderJson = QuizJson.encodeStrings(listOf("q-1")),
        questionsJson = PracticeJson.encodeQuestions(listOf(question())),
        currentIndex = 0,
        phase = PracticePhases.ANSWERING,
        draftJson = PracticeJson.encodeDraft(PracticeDraft()),
        draftRevision = 0L,
        createdAt = 1L,
        updatedAt = 1L,
        completedAt = null,
        source = source
    )

    private fun answer(
        id: String,
        sessionId: String,
        submittedAt: Long,
        result: String,
        questionId: String = "q-1",
        position: Int = 0
    ): PracticeAnswerEntity = PracticeAnswerEntity(
        id = id,
        sessionId = sessionId,
        questionId = questionId,
        position = position,
        kind = PracticeAnswerKinds.AUTOMATIC,
        userAnswerJson = PracticeJson.encodeUserAnswer(PracticeUserAnswer(selectedKeys = listOf("A"))),
        result = result,
        referenceSnapshotJson = PracticeJson.encodeQuestions(listOf(question(questionId))),
        idempotencyKey = "$sessionId:$position",
        submittedAt = submittedAt
    )
}
