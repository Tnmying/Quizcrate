package com.offlinequiz.app.data

import androidx.room.withTransaction
import com.offlinequiz.app.backup.BackupAnswer
import com.offlinequiz.app.backup.BackupBundle
import com.offlinequiz.app.backup.BackupDataStore
import com.offlinequiz.app.backup.BackupError
import com.offlinequiz.app.backup.BackupException
import com.offlinequiz.app.backup.BackupLibraryData
import com.offlinequiz.app.backup.BackupPdfSource
import com.offlinequiz.app.backup.BackupSnapshot
import com.offlinequiz.app.backup.RestorePlan
import com.offlinequiz.app.model.LibraryRecord
import com.offlinequiz.app.model.PracticeSessionRecord
import com.offlinequiz.app.model.StoredQuestion

/**
 * Room implementation of the backup read/write boundary.
 *
 * Reads: one [QuizDatabase.withTransaction] wrapping four queries, so the exported bundle cannot
 * mix libraries from different moments, and completed/superseded sessions are included.
 * Writes: one transaction using only ABORT inserts; a collision rolls the whole new copy back.
 */
class RoomBackupDataStore(
    private val database: QuizDatabase,
    private val pdfStore: PrivatePdfStore
) : BackupDataStore {

    private val dao get() = database.backupDao()

    override suspend fun readSnapshot(): BackupSnapshot = database.withTransaction {
        if (dao.totalLibraries() > MAX_ANDROID_LIBRARIES) {
            throw BackupException(BackupError.TOO_LARGE, "题库数量超过手机端备份上限")
        }
        if (dao.totalQuestions() > MAX_ANDROID_QUESTIONS) {
            throw BackupException(BackupError.TOO_LARGE, "题目数量超过手机端备份上限")
        }
        if (dao.totalSessions() > MAX_ANDROID_SESSIONS) {
            throw BackupException(BackupError.TOO_LARGE, "练习记录数量超过手机端备份上限")
        }
        if (dao.totalAnswers() > MAX_ANDROID_ANSWERS) {
            throw BackupException(BackupError.TOO_LARGE, "作答数量超过手机端备份上限")
        }
        val rows = dao.readAll()
        val questionsByLibrary = rows.questions.groupBy { it.libraryId }.mapValues { entry ->
            entry.value.map { it.toModel() }
        }
        val sessionsByLibrary = rows.sessions.groupBy { it.libraryId }.mapValues { entry ->
            entry.value.map { it.toModel() }
        }
        val answersByLibrary = rows.answers
            .groupBy { it.sessionId }
            .mapValues { entry -> entry.value.map { BackupAnswer(it.toModel(), it.decodeSnapshot()) } }
        val libraries = rows.libraries.map { entity ->
            val record = entity.toRecord()
            val sessions = sessionsByLibrary[record.id].orEmpty()
            val answers = sessions.flatMap { session -> answersByLibrary[session.id].orEmpty() }
            BackupLibraryData(
                record = record,
                questions = questionsByLibrary[record.id].orEmpty(),
                sessions = sessions,
                answers = answers
            )
        }
        BackupSnapshot(libraries)
    }

    override suspend fun insert(plan: RestorePlan) {
        database.withTransaction {
            for (library in plan.libraries) {
                if (dao.libraryCount(library.library.id) > 0) {
                    throw BackupException(
                        com.offlinequiz.app.backup.BackupError.DUPLICATE_ID,
                        "新副本题库标识与现有数据冲突，未写入任何数据"
                    )
                }
                for (question in library.questions) {
                    if (dao.questionCount(question.id) > 0) {
                        throw BackupException(
                            com.offlinequiz.app.backup.BackupError.DUPLICATE_ID,
                            "新副本题目标识与现有数据冲突，未写入任何数据"
                        )
                    }
                }
                for (session in library.sessions) {
                    if (dao.sessionCount(session.id) > 0) {
                        throw BackupException(
                            com.offlinequiz.app.backup.BackupError.DUPLICATE_ID,
                            "新副本练习标识与现有数据冲突，未写入任何数据"
                        )
                    }
                }
                for (answer in library.answers) {
                    if (dao.answerCount(answer.answer.id) > 0) {
                        throw BackupException(
                            com.offlinequiz.app.backup.BackupError.DUPLICATE_ID,
                            "新副本作答标识与现有数据冲突，未写入任何数据"
                        )
                    }
                }
            }
            dao.insertAll(
                BackupRows(
                    libraries = plan.libraries.map { it.library.toEntity() },
                    questions = plan.libraries.flatMap { it.questions }.map { it.toEntity() },
                    sessions = plan.libraries.flatMap { it.sessions }.map { it.toEntity() },
                    answers = plan.libraries.flatMap { it.answers }.map { restore ->
                        PracticeAnswerEntity(
                            id = restore.answer.id,
                            sessionId = restore.answer.sessionId,
                            questionId = restore.answer.questionId,
                            position = restore.answer.position,
                            kind = restore.answer.kind,
                            userAnswerJson = PracticeJson.encodeUserAnswer(restore.answer.userAnswer),
                            result = restore.answer.result,
                            referenceSnapshotJson = restore.referenceSnapshot?.let {
                                PracticeJson.encodeQuestions(listOf(it))
                            } ?: "",
                            idempotencyKey = restore.idempotencyKey,
                            submittedAt = restore.answer.submittedAt
                        )
                    }
                )
            )
        }
    }

    override fun openPdf(record: LibraryRecord): BackupPdfSource? {
        val file = pdfStore.resolve(record.pdfRelativePath) ?: return null
        return BackupPdfSource { file.inputStream() }
    }

    private fun PracticeAnswerEntity.decodeSnapshot() =
        PracticeJson.decodeQuestions(referenceSnapshotJson).firstOrNull()

    companion object {
        // The validated plan is held in memory; these whole-DB guards run before loading rows.
        const val MAX_ANDROID_LIBRARIES = 200
        const val MAX_ANDROID_QUESTIONS = 20_000
        const val MAX_ANDROID_SESSIONS = 20_000
        const val MAX_ANDROID_ANSWERS = 100_000
    }
}
