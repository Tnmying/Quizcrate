package com.offlinequiz.app.data

import android.content.Context
import com.offlinequiz.app.model.PracticePhases
import com.offlinequiz.app.model.PracticeSources
import com.offlinequiz.app.model.PracticeStatuses
import com.offlinequiz.app.model.PracticeRoundCounts
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Database schema version 3.
 *
 * v1 -> v2 adds the practice session/answer tables. v2 -> v3 adds the session `source`
 * (normal practice vs wrongbook re-practice) and an index on `practice_answers.questionId`
 * for per-question history. Libraries, questions, confirmation state, original evidence, private
 * PDF paths, sessions, answers and drafts are preserved; destructive fallback stays disabled and
 * both exported schemas (v1, v2) are kept for migration verification.
 */

@Entity(tableName = "libraries")
data class LibraryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceFileName: String,
    val sourcePageCount: Int,
    val pdfRelativePath: String?,
    val schemaVersion: Int
)

@Entity(
    tableName = "questions",
    foreignKeys = [
        ForeignKey(
            entity = LibraryEntity::class,
            parentColumns = ["id"],
            childColumns = ["libraryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("libraryId"),
        Index(value = ["libraryId", "position"], unique = true)
    ]
)
data class QuestionEntity(
    @PrimaryKey val id: String,
    val libraryId: String,
    val position: Int,
    val type: String,
    val originalNumber: String?,
    val numberValue: Int?,
    val sectionTitle: String?,
    val stem: String,
    val stemWithAnswer: String?,
    val originalSentence: String?,
    val referenceAnswer: String?,
    val optionRawText: String?,
    val optionsJson: String,
    val answersJson: String,
    val acceptableAnswersJson: String,
    val sourcePagesJson: String,
    val sourceLinesJson: String,
    val blanksJson: String,
    val learningBlankCount: Int,
    val originalReviewReasonsJson: String,
    val originalNeedsReview: Boolean,
    val originalStem: String,
    val originalOptionsJson: String,
    val originalAnswersJson: String,
    val originalBlanksJson: String,
    val confirmed: Boolean,
    val confirmedAt: Long?,
    val editedAt: Long?
)

@Entity(
    tableName = "practice_sessions",
    foreignKeys = [
        ForeignKey(
            entity = LibraryEntity::class,
            parentColumns = ["id"],
            childColumns = ["libraryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("libraryId"),
        Index(value = ["libraryId", "status"])
    ]
)
data class PracticeSessionEntity(
    @PrimaryKey val id: String,
    val libraryId: String,
    val mode: String,
    val status: String,
    val questionOrderJson: String,
    val questionsJson: String,
    val currentIndex: Int,
    val phase: String,
    val draftJson: String,
    val draftRevision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    @ColumnInfo(defaultValue = "normal") val source: String = PracticeSources.NORMAL
)

@Entity(
    tableName = "practice_answers",
    foreignKeys = [
        ForeignKey(
            entity = PracticeSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("sessionId"),
        Index("questionId"),
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["sessionId", "position"], unique = true)
    ]
)
data class PracticeAnswerEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val questionId: String,
    val position: Int,
    val kind: String,
    val userAnswerJson: String,
    val result: String,
    val referenceSnapshotJson: String,
    val idempotencyKey: String,
    val submittedAt: Long
)

data class LibrarySummaryRow(
    @Embedded val library: LibraryEntity,
    @Relation(parentColumn = "id", entityColumn = "libraryId")
    val questions: List<QuestionEntity>
)

data class ActiveSessionRow(
    @Embedded val session: PracticeSessionEntity,
    val answeredCount: Int
)

data class PracticeSubmitRow(
    val answer: PracticeAnswerEntity,
    val alreadyRecorded: Boolean
)

/** One submitted answer joined with the source of its session, for history/statistics. */
data class PracticeHistoryRow(
    @Embedded val answer: PracticeAnswerEntity,
    val sessionSource: String
)

data class PracticeStatsRows(val history: List<PracticeHistoryRow>, val rounds: PracticeRoundCounts)

@Dao
interface LibraryDao {

    @Transaction
    @Query(
        """
        SELECT l.*
        FROM libraries l
        ORDER BY l.updatedAt DESC, l.createdAt DESC
        """
    )
    fun observeSummaries(): Flow<List<LibrarySummaryRow>>

    @Query("SELECT * FROM libraries WHERE id = :id")
    fun observeLibrary(id: String): Flow<LibraryEntity?>

    @Query("SELECT * FROM libraries WHERE id = :id")
    suspend fun library(id: String): LibraryEntity?

    @Query("SELECT * FROM questions WHERE libraryId = :libraryId ORDER BY position")
    fun observeQuestions(libraryId: String): Flow<List<QuestionEntity>>

    @Query("SELECT * FROM questions WHERE libraryId = :libraryId ORDER BY position")
    suspend fun questions(libraryId: String): List<QuestionEntity>

    @Query("SELECT * FROM questions WHERE id = :id")
    suspend fun question(id: String): QuestionEntity?

    @Insert
    suspend fun insertLibrary(library: LibraryEntity)

    @Insert
    suspend fun insertQuestions(questions: List<QuestionEntity>)

    @Update
    suspend fun updateLibrary(library: LibraryEntity)

    @Update
    suspend fun updateQuestion(question: QuestionEntity)

    @Query("UPDATE libraries SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchLibrary(id: String, updatedAt: Long)

    @Query("SELECT id FROM libraries")
    suspend fun libraryIds(): List<String>

    @Transaction
    suspend fun createLibraryWithQuestions(library: LibraryEntity, questions: List<QuestionEntity>) {
        insertLibrary(library)
        insertQuestions(questions)
    }

    @Transaction
    suspend fun saveQuestionAndTouchLibrary(question: QuestionEntity, updatedAt: Long) {
        updateQuestion(question)
        touchLibrary(question.libraryId, updatedAt)
    }
}

@Dao
interface PracticeDao {
    @Query("""
        SELECT COUNT(CASE WHEN source = 'normal' THEN 1 END) AS normalCompleted,
               COUNT(CASE WHEN source = 'wrongbook' THEN 1 END) AS wrongbookCompleted
        FROM practice_sessions
        WHERE libraryId = :libraryId AND status = 'completed'
          AND phase = 'completed' AND completedAt IS NOT NULL
    """)
    suspend fun completedRoundCounts(libraryId: String): PracticeRoundCounts

    @Transaction
    suspend fun libraryStatsRows(libraryId: String): PracticeStatsRows =
        PracticeStatsRows(historyForLibrary(libraryId), completedRoundCounts(libraryId))


    @Query(
        """
        SELECT s.*,
            (SELECT COUNT(*) FROM practice_answers a WHERE a.sessionId = s.id) AS answeredCount
        FROM practice_sessions s
        WHERE s.libraryId = :libraryId AND s.status = 'active' AND s.source != 'single_review'
        ORDER BY s.updatedAt DESC
        LIMIT 1
        """
    )
    fun observeActiveSession(libraryId: String): Flow<ActiveSessionRow?>

    @Query(
        """
        SELECT * FROM practice_sessions
        WHERE libraryId = :libraryId AND status = 'active' AND source != 'single_review'
        ORDER BY updatedAt DESC
        LIMIT 1
        """
    )
    suspend fun activeSession(libraryId: String): PracticeSessionEntity?

    @Query("SELECT * FROM practice_sessions WHERE id = :id")
    suspend fun session(id: String): PracticeSessionEntity?

    @Query("SELECT * FROM practice_sessions WHERE id = :id")
    fun observeSession(id: String): Flow<PracticeSessionEntity?>

    @Query("SELECT * FROM practice_answers WHERE sessionId = :sessionId ORDER BY position")
    suspend fun answers(sessionId: String): List<PracticeAnswerEntity>

    @Query("SELECT * FROM practice_answers WHERE sessionId = :sessionId AND position = :position")
    suspend fun answerAt(sessionId: String, position: Int): PracticeAnswerEntity?

    @Query("SELECT * FROM practice_answers WHERE idempotencyKey = :idempotencyKey")
    suspend fun answerByKey(idempotencyKey: String): PracticeAnswerEntity?

    /**
     * Every submitted answer of one library, including superseded and unfinished sessions, joined
     * with the session source. Drafts are not rows in this table and therefore never counted.
     */
    @Query(
        """
        SELECT a.*, s.source AS sessionSource
        FROM practice_answers a
        INNER JOIN practice_sessions s ON s.id = a.sessionId
        WHERE s.libraryId = :libraryId
        ORDER BY a.submittedAt ASC, a.id ASC
        """
    )
    suspend fun historyForLibrary(libraryId: String): List<PracticeHistoryRow>

    @Query(
        """
        SELECT a.*, s.source AS sessionSource
        FROM practice_answers a
        INNER JOIN practice_sessions s ON s.id = a.sessionId
        WHERE s.libraryId = :libraryId AND a.questionId = :questionId
        ORDER BY a.submittedAt ASC, a.id ASC
        """
    )
    suspend fun historyForQuestion(libraryId: String, questionId: String): List<PracticeHistoryRow>

    @Insert
    suspend fun insertSession(session: PracticeSessionEntity)

    @Insert
    suspend fun insertAnswer(answer: PracticeAnswerEntity)

    @Update
    suspend fun updateSession(session: PracticeSessionEntity)

    @Query("UPDATE practice_sessions SET status = 'superseded', updatedAt = :now WHERE libraryId = :libraryId AND status = 'active'")
    suspend fun supersedeActiveSessions(libraryId: String, now: Long)

    @Query("UPDATE practice_sessions SET status = 'superseded', updatedAt = :now WHERE libraryId = :libraryId AND status = 'active' AND source = 'single_review'")
    suspend fun supersedeSingleReviews(libraryId: String, now: Long)

    @Transaction
    suspend fun createSessionWithSupersede(session: PracticeSessionEntity) {
        if (session.source == PracticeSources.SINGLE_REVIEW)
            supersedeSingleReviews(session.libraryId, session.createdAt)
        else supersedeActiveSessions(session.libraryId, session.createdAt)
        insertSession(session)
    }

    /** Inserts an answer once and moves the session to feedback in the same transaction. */
    @Transaction
    suspend fun submitAnswerAndSetFeedback(
        sessionId: String,
        expectedPosition: Int,
        answer: PracticeAnswerEntity
    ): PracticeSubmitRow? {
        val session = session(sessionId) ?: return null
        if (session.status != PracticeStatuses.ACTIVE) return null
        if (session.currentIndex != expectedPosition) return null
        val existing = answerByKey(answer.idempotencyKey)
        if (existing != null) {
            if (session.phase != PracticePhases.FEEDBACK) {
                updateSession(
                    session.copy(
                        phase = PracticePhases.FEEDBACK,
                        draftJson = "",
                        draftRevision = session.draftRevision + 1,
                        updatedAt = answer.submittedAt
                    )
                )
            }
            return PracticeSubmitRow(existing, alreadyRecorded = true)
        }
        insertAnswer(answer)
        updateSession(
            session.copy(
                phase = PracticePhases.FEEDBACK,
                draftJson = "",
                draftRevision = session.draftRevision + 1,
                updatedAt = answer.submittedAt
            )
        )
        return PracticeSubmitRow(answer, alreadyRecorded = false)
    }

    /** Advances only from the matching feedback position; duplicate taps are a no-op. */
    @Transaction
    suspend fun advanceSession(
        sessionId: String,
        expectedPosition: Int,
        nextIndex: Int,
        completed: Boolean,
        now: Long
    ): PracticeSessionEntity? {
        val session = session(sessionId) ?: return null
        if (session.status != PracticeStatuses.ACTIVE) return null
        if (session.currentIndex != expectedPosition) return null
        if (session.phase != PracticePhases.FEEDBACK) return null
        if (answerAt(sessionId, expectedPosition) == null) return null
        val updated = session.copy(
            currentIndex = nextIndex,
            phase = if (completed) PracticePhases.COMPLETED else PracticePhases.ANSWERING,
            status = if (completed) PracticeStatuses.COMPLETED else PracticeStatuses.ACTIVE,
            draftJson = "",
            draftRevision = session.draftRevision + 1,
            updatedAt = now,
            completedAt = if (completed) now else null
        )
        updateSession(updated)
        return updated
    }

    /** Revision is monotonic; a delayed older draft can never overwrite a newer one. */
    @Query(
        """
        UPDATE practice_sessions
        SET draftJson = :draftJson, draftRevision = :revision, updatedAt = :now
        WHERE id = :sessionId
            AND currentIndex = :position
            AND phase = 'answering'
            AND status = 'active'
            AND draftRevision < :revision
        """
    )
    suspend fun updateDraft(
        sessionId: String,
        position: Int,
        draftJson: String,
        revision: Long,
        now: Long
    ): Int
}

@Database(
    entities = [
        LibraryEntity::class,
        QuestionEntity::class,
        PracticeSessionEntity::class,
        PracticeAnswerEntity::class
    ],
    version = QuizDatabase.VERSION,
    exportSchema = true
)
abstract class QuizDatabase : RoomDatabase() {

    abstract fun libraryDao(): LibraryDao

    abstract fun maintenanceDao(): LibraryMaintenanceDao

    abstract fun practiceDao(): PracticeDao

    abstract fun backupDao(): BackupDao

    companion object {
        const val NAME = "offlinequiz.db"
        const val VERSION = 3

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `practice_sessions` (
                        `id` TEXT NOT NULL,
                        `libraryId` TEXT NOT NULL,
                        `mode` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `questionOrderJson` TEXT NOT NULL,
                        `questionsJson` TEXT NOT NULL,
                        `currentIndex` INTEGER NOT NULL,
                        `phase` TEXT NOT NULL,
                        `draftJson` TEXT NOT NULL,
                        `draftRevision` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `completedAt` INTEGER,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`libraryId`) REFERENCES `libraries`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_practice_sessions_libraryId` " +
                        "ON `practice_sessions` (`libraryId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_practice_sessions_libraryId_status` " +
                        "ON `practice_sessions` (`libraryId`, `status`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `practice_answers` (
                        `id` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `questionId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL,
                        `userAnswerJson` TEXT NOT NULL,
                        `result` TEXT NOT NULL,
                        `referenceSnapshotJson` TEXT NOT NULL,
                        `idempotencyKey` TEXT NOT NULL,
                        `submittedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`sessionId`) REFERENCES `practice_sessions`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_practice_answers_sessionId` " +
                        "ON `practice_answers` (`sessionId`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_practice_answers_idempotencyKey` " +
                        "ON `practice_answers` (`idempotencyKey`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_practice_answers_sessionId_position` " +
                        "ON `practice_answers` (`sessionId`, `position`)"
                )
            }
        }

        /**
         * v2 -> v3 adds the session source and the per-question history index. Existing sessions
         * keep every row and become normal practice sessions; no table is recreated or cleared.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `practice_sessions` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'normal'"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_practice_answers_questionId` " +
                        "ON `practice_answers` (`questionId`)"
                )
            }
        }

        fun create(context: Context): QuizDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                QuizDatabase::class.java,
                NAME
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        }
    }
}
