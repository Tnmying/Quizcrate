package com.offlinequiz.app

import androidx.room.migration.AutoMigrationSpec
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.offlinequiz.app.data.QuizDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-only migration checks. Compiled but not executed in this environment (no device).
 *
 * v1 -> v2 was verified in 03A; 03B adds v2 -> v3 (session source + question history index) and
 * must keep the direct v1 -> v3 route working without recreating or clearing anything.
 */
@RunWith(AndroidJUnit4::class)
class QuizMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        QuizDatabase::class.java,
        emptyList<AutoMigrationSpec>(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migration1To2KeepsLibraryQuestionAndEvidence() {
        val databaseName = "migration-1-2-${System.nanoTime()}.db"
        var database = helper.createDatabase(databaseName, 1)
        try {
            insertLibrary(database, "lib-1", "旧题库")
            insertQuestion(database, "q-1", "lib-1")
        } finally {
            database.close()
        }

        database = helper.runMigrationsAndValidate(
            databaseName,
            2,
            true,
            QuizDatabase.MIGRATION_1_2
        )
        try {
            assertLibraryAndQuestion(database)
            database.query("SELECT COUNT(*) FROM practice_sessions").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migration1To3KeepsDataAndAddsSessionSource() {
        val databaseName = "migration-1-3-${System.nanoTime()}.db"
        var database = helper.createDatabase(databaseName, 1)
        try {
            insertLibrary(database, "lib-1", "旧题库")
            insertQuestion(database, "q-1", "lib-1")
        } finally {
            database.close()
        }

        database = helper.runMigrationsAndValidate(
            databaseName,
            3,
            true,
            QuizDatabase.MIGRATION_1_2,
            QuizDatabase.MIGRATION_2_3
        )
        try {
            assertLibraryAndQuestion(database)
            database.query("PRAGMA table_info(practice_sessions)").use { cursor ->
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == "source") {
                        found = true
                        // SQLite reports the declared default including its quotes ('normal').
                        assertEquals("normal", cursor.getString(4).trim('\''))
                    }
                }
                assertTrue("practice_sessions.source must be added", found)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migration2To3KeepsSessionsAndDefaultsThemToNormalSource() {
        val databaseName = "migration-2-3-${System.nanoTime()}.db"
        var database = helper.createDatabase(databaseName, 2)
        try {
            insertLibrary(database, "lib-1", "旧题库")
            database.execSQL(
                "INSERT INTO practice_sessions (" +
                    "id, libraryId, mode, status, questionOrderJson, questionsJson, currentIndex, phase, " +
                    "draftJson, draftRevision, createdAt, updatedAt, completedAt" +
                    ") VALUES ('s1', 'lib-1', 'sequential', 'active', '[]', '[]', 0, 'answering', '', 0, 10, 10, NULL)"
            )
            database.execSQL(
                "INSERT INTO practice_answers (" +
                    "id, sessionId, questionId, position, kind, userAnswerJson, result, " +
                    "referenceSnapshotJson, idempotencyKey, submittedAt" +
                    ") VALUES ('a1', 's1', 'q-1', 0, 'automatic', '{}', 'correct', '[]', 's1:0', 20)"
            )
        } finally {
            database.close()
        }

        database = helper.runMigrationsAndValidate(
            databaseName,
            3,
            true,
            QuizDatabase.MIGRATION_2_3
        )
        try {
            database.query("SELECT source FROM practice_sessions WHERE id = 's1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("normal", cursor.getString(0))
            }
            database.query("SELECT COUNT(*), MAX(result) FROM practice_answers").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals("correct", cursor.getString(1))
            }
            database.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_practice_answers_questionId'"
            ).use { cursor ->
                assertTrue("questionId history index must exist", cursor.moveToFirst())
            }
        } finally {
            database.close()
        }
    }

    private fun insertLibrary(database: SupportSQLiteDatabase, id: String, name: String) {
        database.execSQL(
            "INSERT INTO libraries (id, name, createdAt, updatedAt, sourceFileName, sourcePageCount, pdfRelativePath, schemaVersion) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                id, name, 100L, 200L, "demo.pdf", 3,
                "libraries/$id/source.pdf", 1
            )
        )
    }

    private fun insertQuestion(database: SupportSQLiteDatabase, id: String, libraryId: String) {
        database.execSQL(
            "INSERT INTO questions (" +
                "id, libraryId, position, type, originalNumber, numberValue, sectionTitle, stem, " +
                "stemWithAnswer, originalSentence, referenceAnswer, optionRawText, optionsJson, answersJson, " +
                "acceptableAnswersJson, sourcePagesJson, sourceLinesJson, blanksJson, learningBlankCount, " +
                "originalReviewReasonsJson, originalNeedsReview, originalStem, originalOptionsJson, " +
                "originalAnswersJson, originalBlanksJson, confirmed, confirmedAt, editedAt" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                id, libraryId, 0, "single_choice", "1", 1, "一、单选题", "旧题干",
                "旧题干（A）", "旧题干（A）", null, "A.甲 B.乙",
                """[{"key":"A","text":"甲","markerIndex":0},{"key":"B","text":"乙","markerIndex":1}]""",
                """["A"]""", "[]", "[2]", "[]", "[]", 0,
                """["no_answer_in_parentheses"]""", 1, "旧题干",
                """[{"key":"A","text":"甲","markerIndex":0},{"key":"B","text":"乙","markerIndex":1}]""",
                """["A"]""", "[]", 1, 123L, 456L
            )
        )
    }

    private fun assertLibraryAndQuestion(database: SupportSQLiteDatabase) {
        database.query("SELECT name, pdfRelativePath FROM libraries WHERE id = 'lib-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("旧题库", cursor.getString(0))
            assertEquals("libraries/lib-1/source.pdf", cursor.getString(1))
        }
        database.query(
            "SELECT stem, confirmed, originalNeedsReview, originalReviewReasonsJson, editedAt " +
                "FROM questions WHERE id = 'q-1'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("旧题干", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals(1, cursor.getInt(2))
            assertTrue(cursor.getString(3).contains("no_answer_in_parentheses"))
            assertEquals(456L, cursor.getLong(4))
        }
    }
}
