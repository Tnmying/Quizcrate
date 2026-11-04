package com.offlinequiz.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** One consistent read of every saved row, used only by the local backup service. */
data class BackupRows(
    val libraries: List<LibraryEntity>,
    val questions: List<QuestionEntity>,
    val sessions: List<PracticeSessionEntity>,
    val answers: List<PracticeAnswerEntity>
)

@Dao
interface BackupDao {

    @Query("SELECT * FROM libraries ORDER BY createdAt ASC, id ASC")
    suspend fun libraries(): List<LibraryEntity>

    @Query("SELECT * FROM questions ORDER BY libraryId ASC, position ASC, id ASC")
    suspend fun questions(): List<QuestionEntity>

    @Query("SELECT * FROM practice_sessions ORDER BY libraryId ASC, createdAt ASC, id ASC")
    suspend fun sessions(): List<PracticeSessionEntity>

    @Query("SELECT * FROM practice_answers ORDER BY sessionId ASC, position ASC")
    suspend fun answers(): List<PracticeAnswerEntity>

    /** All four queries run in one SQLite transaction/WAL snapshot. */
    @Transaction
    suspend fun readAll(): BackupRows = BackupRows(
        libraries = libraries(),
        questions = questions(),
        sessions = sessions(),
        answers = answers()
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLibraries(rows: List<LibraryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertQuestions(rows: List<QuestionEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSessions(rows: List<PracticeSessionEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAnswers(rows: List<PracticeAnswerEntity>)

    /** One transaction: any PK/FK/unique collision aborts the whole new copy. */
    @Transaction
    suspend fun insertAll(rows: BackupRows) {
        insertLibraries(rows.libraries)
        insertQuestions(rows.questions)
        insertSessions(rows.sessions)
        insertAnswers(rows.answers)
    }

    @Query("SELECT COUNT(*) FROM libraries WHERE id = :id")
    suspend fun libraryCount(id: String): Int

    @Query("SELECT COUNT(*) FROM questions WHERE id = :id")
    suspend fun questionCount(id: String): Int

    @Query("SELECT COUNT(*) FROM practice_sessions WHERE id = :id")
    suspend fun sessionCount(id: String): Int

    @Query("SELECT COUNT(*) FROM practice_answers WHERE id = :id")
    suspend fun answerCount(id: String): Int

    @Query("SELECT COUNT(*) FROM libraries")
    suspend fun totalLibraries(): Int

    @Query("SELECT COUNT(*) FROM questions")
    suspend fun totalQuestions(): Int

    @Query("SELECT COUNT(*) FROM practice_sessions")
    suspend fun totalSessions(): Int

    @Query("SELECT COUNT(*) FROM practice_answers")
    suspend fun totalAnswers(): Int
}
