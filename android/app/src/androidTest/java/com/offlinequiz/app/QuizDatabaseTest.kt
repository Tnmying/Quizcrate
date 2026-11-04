package com.offlinequiz.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offlinequiz.app.data.LibraryEntity
import com.offlinequiz.app.data.LibraryDao
import com.offlinequiz.app.data.QuestionEntity
import com.offlinequiz.app.data.RoomLibraryStore
import com.offlinequiz.app.data.QuizDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-only coverage for Room transactions and isolation.
 *
 * Compile with :app:assembleDebugAndroidTest and run on a connected Android device
 * with :app:connectedDebugAndroidTest.
 */
@RunWith(AndroidJUnit4::class)
class QuizDatabaseTest {

    private lateinit var database: QuizDatabase
    private lateinit var dao: LibraryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, QuizDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.libraryDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun questionsAndCountsAreIsolatedPerLibrary() = runBlocking {
        dao.createLibraryWithQuestions(library("lib-a", "题库甲"), listOf(
            question("a-1", "lib-a", 0, confirmed = true),
            question("a-2", "lib-a", 1, confirmed = false)
        ))
        dao.createLibraryWithQuestions(library("lib-b", "题库乙"), listOf(
            question("b-1", "lib-b", 0, confirmed = false)
        ))

        val summaries = RoomLibraryStore(dao).observeSummaries().first()
        assertEquals(2, summaries.size)
        val a = summaries.single { it.library.id == "lib-a" }
        val b = summaries.single { it.library.id == "lib-b" }
        assertEquals(2, a.questionCount)
        assertEquals(0, a.pendingCount)
        assertEquals(1, a.unreviewedCount)
        assertEquals(1, a.confirmedCount)
        assertEquals(1, b.questionCount)
        assertEquals(0, b.pendingCount)
        assertEquals(1, b.unreviewedCount)

        val questionsA = dao.observeQuestions("lib-a").first()
        val questionsB = dao.observeQuestions("lib-b").first()
        assertEquals(listOf("a-1", "a-2"), questionsA.map { it.id })
        assertEquals(listOf("b-1"), questionsB.map { it.id })
        assertEquals("1", questionsA.first().originalNumber)
        assertEquals("1", questionsB.first().originalNumber)
    }

    @Test
    fun confirmationUpdateIsPersistedAndTouchesLibrary() = runBlocking {
        dao.createLibraryWithQuestions(library("lib", "题库"), listOf(question("q", "lib", 0)))
        val before = dao.library("lib")!!
        val entity = dao.question("q")!!
        dao.saveQuestionAndTouchLibrary(entity.copy(confirmed = true, confirmedAt = 10L), 99L)

        assertTrue(dao.question("q")!!.confirmed)
        assertEquals(10L, dao.question("q")!!.confirmedAt)
        assertEquals(99L, dao.library("lib")!!.updatedAt)
        assertTrue(dao.library("lib")!!.updatedAt > before.updatedAt)
        assertEquals(0, RoomLibraryStore(dao).observeSummaries().first().single().pendingCount)
    }

    @Test
    fun summaryCountsAttentionAcrossImportDoubtAndInvalidConfirmedContent() = runBlocking {
        dao.createLibraryWithQuestions(library("lib", "题库"), listOf(
            question("normal", "lib", 0, confirmed = false),
            question("doubt", "lib", 1, confirmed = false).copy(originalNeedsReview = true),
            question("invalid", "lib", 2, confirmed = true).copy(answersJson = "[]")
        ))
        val store = RoomLibraryStore(dao)
        val summary = store.observeSummaries().first().single()
        assertEquals(3, summary.questionCount)
        assertEquals(2, summary.pendingCount)
        assertEquals(1, summary.confirmedCount)
        assertEquals(2, summary.unreviewedCount)
        val pending = dao.question("doubt")!!
        dao.saveQuestionAndTouchLibrary(pending.copy(confirmed = true, confirmedAt = 2L), 2L)
        val updated = store.observeSummaries().first().single()
        assertEquals(1, updated.pendingCount)
        assertEquals(2, updated.confirmedCount)
        assertEquals(1, updated.unreviewedCount)
    }

    @Test
    fun failedTransactionRollsBackLibraryAndQuestions() = runBlocking {
        val duplicate = question("same-id", "lib", 0)
        try {
            dao.createLibraryWithQuestions(
                library("lib", "回滚题库"),
                listOf(duplicate, duplicate.copy(position = 1))
            )
            fail("expected a primary key constraint failure")
        } catch (_: Exception) {
        }

        assertNull(dao.library("lib"))
        assertTrue(dao.observeSummaries().first().isEmpty())
        assertTrue(dao.observeQuestions("lib").first().isEmpty())
    }

    @Test
    fun savedLibrarySurvivesDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "offlinequiz-device-test-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        var fileDatabase = Room.databaseBuilder(context, QuizDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        try {
            fileDatabase.libraryDao().createLibraryWithQuestions(
                library("persisted", "重启后仍在"),
                listOf(question("persisted-q", "persisted", 0, confirmed = true))
            )
        } finally {
            fileDatabase.close()
        }

        fileDatabase = Room.databaseBuilder(context, QuizDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        try {
            val record = fileDatabase.libraryDao().library("persisted")
            assertNotNull(record)
            assertEquals("重启后仍在", record!!.name)
            val question = fileDatabase.libraryDao().question("persisted-q")
            assertNotNull(question)
            assertTrue(question!!.confirmed)
            assertEquals("题干", question.stem)
        } finally {
            fileDatabase.close()
            context.deleteDatabase(name)
        }
    }

    private fun library(id: String, name: String): LibraryEntity = LibraryEntity(
        id = id,
        name = name,
        createdAt = 1L,
        updatedAt = 1L,
        sourceFileName = "demo.pdf",
        sourcePageCount = 2,
        pdfRelativePath = "libraries/$id/source.pdf",
        schemaVersion = QuizDatabase.VERSION
    )

    private fun question(
        id: String,
        libraryId: String,
        position: Int,
        confirmed: Boolean = false
    ): QuestionEntity = QuestionEntity(
        id = id,
        libraryId = libraryId,
        position = position,
        type = "single_choice",
        originalNumber = "1",
        numberValue = 1,
        sectionTitle = "一、单选题",
        stem = "题干",
        stemWithAnswer = "题干（A）",
        originalSentence = "题干（A）",
        referenceAnswer = null,
        optionRawText = "A.甲 B.乙",
        optionsJson = """[{"key":"A","text":"甲","markerIndex":0},{"key":"B","text":"乙","markerIndex":1}]""",
        answersJson = """["A"]""",
        acceptableAnswersJson = "[]",
        sourcePagesJson = "[1]",
        sourceLinesJson = "[]",
        blanksJson = "[]",
        learningBlankCount = 0,
        originalReviewReasonsJson = "[]",
        originalNeedsReview = false,
        originalStem = "题干",
        originalOptionsJson = """[{"key":"A","text":"甲","markerIndex":0},{"key":"B","text":"乙","markerIndex":1}]""",
        originalAnswersJson = """["A"]""",
        originalBlanksJson = "[]",
        confirmed = confirmed,
        confirmedAt = if (confirmed) 1L else null,
        editedAt = null
    )
}
