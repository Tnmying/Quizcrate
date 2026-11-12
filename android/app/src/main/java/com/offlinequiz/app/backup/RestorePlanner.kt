package com.offlinequiz.app.backup

import com.offlinequiz.app.model.PracticeQuestionSnapshot
import com.offlinequiz.app.model.PracticeStatuses
import com.offlinequiz.app.model.QuestionReview

/**
 * Pure "restore as a new copy" planner. It never writes Room/files: every library, question,
 * session and answer receives a new injected ID and every reference is rewritten. Existing data
 * with the same IDs can therefore never be linked or overwritten by this plan.
 */
object RestorePlanner {

    fun plan(
        bundle: BackupBundle,
        idFactory: BackupIdFactory,
        targetSchemaVersion: Int
    ): RestorePlan {
        if (targetSchemaVersion < 1) {
            throw BackupException(BackupError.BAD_FIELD, "目标数据库版本无效")
        }
        val oldIds = mutableSetOf<String>()
        for (library in bundle.libraries) {
            oldIds += library.record.id
            oldIds += library.questions.map { it.id }
            oldIds += library.sessions.map { it.id }
            oldIds += library.answers.map { it.answer.id }
        }
        val generated = mutableSetOf<String>()

        fun newId(): String {
            val id = idFactory.newId()
            if (id.isBlank() || id.length > 128) {
                throw BackupException(BackupError.BAD_FIELD, "恢复标识生成失败")
            }
            if (!generated.add(id)) {
                throw BackupException(BackupError.DUPLICATE_ID, "恢复标识生成器返回了重复值")
            }
            if (id in oldIds) {
                throw BackupException(BackupError.DUPLICATE_ID, "恢复标识不能复用备份中的旧标识")
            }
            return id
        }

        var active = 0
        var completed = 0
        var superseded = 0
        var questionTotal = 0
        var sessionTotal = 0
        var answerTotal = 0
        var pendingReview = 0
        var pdfIncluded = 0
        var pdfMissing = 0

        val restored = bundle.libraries.map { backup ->
            val newLibraryId = newId()
            val questionIdMap = mutableMapOf<String, String>()
            val questions = backup.questions.map { old ->
                val newQuestionId = newId()
                questionIdMap[old.id] = newQuestionId
                old.copy(id = newQuestionId, libraryId = newLibraryId)
            }
            val sessionIdMap = mutableMapOf<String, String>()
            val sessions = backup.sessions.map { old ->
                val newSessionId = newId()
                sessionIdMap[old.id] = newSessionId
                old.copy(
                    id = newSessionId,
                    libraryId = newLibraryId,
                    questions = old.questions.map { snapshot ->
                        remapSnapshot(snapshot, newLibraryId, questionIdMap)
                    }
                )
            }
            val answers = backup.answers.map { old ->
                val answer = old.answer
                val newSessionId = sessionIdMap[answer.sessionId]
                    ?: throw BackupException(BackupError.DANGLING_REF, "作答对应的练习会话不存在")
                val newQuestionId = questionIdMap[answer.questionId]
                    ?: throw BackupException(BackupError.DANGLING_REF, "作答对应的题目不存在")
                RestoreAnswer(
                    answer = answer.copy(
                        id = newId(),
                        sessionId = newSessionId,
                        questionId = newQuestionId
                    ),
                    referenceSnapshot = old.referenceSnapshot?.let { snapshot ->
                        remapSnapshot(snapshot, newLibraryId, questionIdMap)
                    },
                    idempotencyKey = "$newSessionId:${answer.position}"
                )
            }
            val pdf = backup.pdf?.let { info ->
                RestorePdf(
                    entry = info.entry,
                    byteSize = info.byteSize,
                    sha256 = info.sha256,
                    relativePath = "libraries/$newLibraryId/source.pdf"
                )
            }
            val library = backup.record.copy(
                id = newLibraryId,
                pdfRelativePath = pdf?.relativePath,
                schemaVersion = targetSchemaVersion
            )

            active += backup.sessions.count { it.status == PracticeStatuses.ACTIVE }
            completed += backup.sessions.count { it.status == PracticeStatuses.COMPLETED }
            superseded += backup.sessions.count { it.status == PracticeStatuses.SUPERSEDED }
            questionTotal += questions.size
            sessionTotal += sessions.size
            answerTotal += answers.size
            pendingReview += questions.count(QuestionReview::needsAttention)
            if (pdf != null) pdfIncluded += 1 else if (library.sourceText == null) pdfMissing += 1

            RestoreLibrary(
                library = library,
                questions = questions,
                sessions = sessions,
                answers = answers,
                pdf = pdf
            )
        }

        return RestorePlan(
            summary = RestoreSummary(
                libraryCount = restored.size,
                questionCount = questionTotal,
                sessionCount = sessionTotal,
                answerCount = answerTotal,
                pdfIncludedLibraries = pdfIncluded,
                pdfMissingLibraries = pdfMissing,
                textSourceLibraries = restored.count { it.library.sourceText != null },
                pendingReviewQuestions = pendingReview,
                activeSessions = active,
                completedSessions = completed,
                supersededSessions = superseded
            ),
            libraries = restored
        )
    }

    private fun remapSnapshot(
        snapshot: PracticeQuestionSnapshot,
        newLibraryId: String,
        questionIdMap: Map<String, String>
    ): PracticeQuestionSnapshot {
        val newQuestionId = questionIdMap[snapshot.questionId]
            ?: throw BackupException(BackupError.DANGLING_REF, "快照对应的题目不存在")
        return snapshot.copy(questionId = newQuestionId, libraryId = newLibraryId)
    }
}
