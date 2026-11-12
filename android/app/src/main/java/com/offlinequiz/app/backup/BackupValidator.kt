package com.offlinequiz.app.backup

import com.offlinequiz.app.model.PracticeAnswerKinds
import com.offlinequiz.app.model.PracticeModes
import com.offlinequiz.app.model.PracticePhases
import com.offlinequiz.app.model.PracticeResults
import com.offlinequiz.app.model.PracticeSessionRecord
import com.offlinequiz.app.model.PracticeSources
import com.offlinequiz.app.model.PracticeStatuses
import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.model.StoredQuestion

/**
 * Cross-record consistency rules applied by BOTH export and import. Export refuses to emit a
 * self-inconsistent bundle; import refuses to produce a half-valid plan.
 */
object BackupValidator {

    private val knownTypes = setOf(
        QuestionTypes.SINGLE_CHOICE,
        QuestionTypes.MULTIPLE_CHOICE,
        QuestionTypes.TRUE_FALSE,
        QuestionTypes.FILL_BLANK,
        QuestionTypes.SHORT_ANSWER,
        QuestionTypes.UNKNOWN
    )

    fun validateBundle(bundle: BackupBundle, limits: BackupLimits) {
        validateManifest(bundle.manifest, limits)
        if (bundle.libraries.size != bundle.manifest.libraries.size) {
            fail(BackupError.BAD_MANIFEST, "备份题库数量与清单不一致")
        }
        val questionIds = mutableSetOf<String>()
        val sessionIds = mutableSetOf<String>()
        val answerIds = mutableSetOf<String>()
        for ((index, library) in bundle.libraries.withIndex()) {
            val meta = bundle.manifest.libraries[index]
            if (library.record.id != meta.record.id) {
                fail(BackupError.BAD_FIELD, "题库标识与清单不一致")
            }
            if (meta.hasSourceText != (library.record.sourceText != null) || (meta.hasSourceText && meta.pdf != null)) {
                fail(BackupError.BAD_MANIFEST, "文字来源与备份清单不一致")
            }
            validateLibrary(library.record, library.questions, library.sessions, library.answers, limits)
            for (question in library.questions) {
                if (!questionIds.add(question.id)) fail(BackupError.DUPLICATE_ID, "题目标识重复：${question.id}")
            }
            for (session in library.sessions) {
                if (!sessionIds.add(session.id)) fail(BackupError.DUPLICATE_ID, "会话标识重复：${session.id}")
            }
            for (answer in library.answers) {
                if (!answerIds.add(answer.answer.id)) fail(BackupError.DUPLICATE_ID, "作答标识重复：${answer.answer.id}")
            }
        }
    }

    /** Validates a writer-produced manifest without touching the ZIP. */
    fun validateManifest(manifest: BackupManifest, limits: BackupLimits) {
        val raw = BackupJson.encodeManifest(manifest)
        val decoded = BackupJson.decodeManifest(raw, limits)
        if (decoded.entries.size != manifest.entries.size) {
            fail(BackupError.BAD_MANIFEST, "备份清单条目不一致")
        }
    }

    fun validateHeader(header: BackupLibraryHeader, limits: BackupLimits) {
        val record = header.record
        if (record.id.isBlank() || record.id.length > 128) fail(BackupError.BAD_FIELD, "题库标识无效")
        if (record.name.toByteArray(Charsets.UTF_8).size > limits.maxStringBytes) {
            fail(BackupError.TOO_LARGE, "题库名称超过上限")
        }
        if (record.createdAt < 0 || record.updatedAt < record.createdAt) {
            fail(BackupError.BAD_FIELD, "题库时间无效")
        }
        if (record.sourcePageCount < 0) fail(BackupError.BAD_FIELD, "来源页数不能为负")
        record.sourceText?.let { text ->
            if (text.isBlank() || text.contains('\u0000')) fail(BackupError.BAD_FIELD, "文字来源内容无效")
            if (text.toByteArray(Charsets.UTF_8).size > minOf(limits.maxStringBytes, 512 * 1024)) {
                fail(BackupError.TOO_LARGE, "文字来源超过上限")
            }
            if (record.sourcePageCount != 0 || header.pdfAvailable) fail(BackupError.BAD_FIELD, "文字来源与 PDF 来源冲突")
        }
        if (record.schemaVersion < 1) fail(BackupError.BAD_FIELD, "题库结构版本无效")
        if (header.questionCount < 0 || header.questionCount > limits.maxQuestionsPerLibrary) {
            fail(BackupError.TOO_LARGE, "题库题目数量超过上限")
        }
        if (header.sessionCount < 0 || header.sessionCount > limits.maxSessionsPerLibrary) {
            fail(BackupError.TOO_LARGE, "练习会话数量超过上限")
        }
        if (header.answerCount < 0 || header.answerCount > limits.maxAnswersPerLibrary) {
            fail(BackupError.TOO_LARGE, "作答记录数量超过上限")
        }
    }

    fun validateLibrary(
        record: com.offlinequiz.app.model.LibraryRecord,
        questions: List<StoredQuestion>,
        sessions: List<PracticeSessionRecord>,
        answers: List<BackupAnswer>,
        limits: BackupLimits
    ) {
        if (questions.size > limits.maxQuestionsPerLibrary) fail(BackupError.TOO_LARGE, "题库题目数量超过上限")
        if (sessions.size > limits.maxSessionsPerLibrary) fail(BackupError.TOO_LARGE, "练习会话数量超过上限")
        if (answers.size > limits.maxAnswersPerLibrary) fail(BackupError.TOO_LARGE, "作答记录数量超过上限")

        val questionById = mutableMapOf<String, StoredQuestion>()
        for (question in questions) {
            if (question.libraryId != record.id) fail(BackupError.DANGLING_REF, "题目不属于该题库")
            if (question.id.isBlank() || question.id.length > 128) fail(BackupError.BAD_FIELD, "题目标识无效")
            if (questionById.put(question.id, question) != null) {
                fail(BackupError.DUPLICATE_ID, "题目标识重复：${question.id}")
            }
            if (question.position < 0) fail(BackupError.BAD_INDEX, "题目位置不能为负")
            if (question.type !in knownTypes) fail(BackupError.BAD_ENUM, "题目类型无效：${question.type}")
            if (question.learningBlankCount < 0) fail(BackupError.BAD_FIELD, "学习空数量不能为负")
            if (question.sourcePages.any { it < 0 }) fail(BackupError.BAD_FIELD, "来源页码不能为负")
            if (question.sourceLines.any { it.page < 0 || it.line < 0 }) {
                fail(BackupError.BAD_FIELD, "来源行号不能为负")
            }
            if (question.type == QuestionTypes.FILL_BLANK &&
                question.acceptableAnswers.size != question.answers.size
            ) {
                fail(BackupError.BAD_FIELD, "填空题可接受答案数量与空数量不一致")
            }
            if (question.confirmedAt != null && question.confirmedAt < 0) {
                fail(BackupError.BAD_FIELD, "题目确认时间无效")
            }
            if (question.editedAt != null && question.editedAt < 0) {
                fail(BackupError.BAD_FIELD, "题目修改时间无效")
            }
        }
        if (questions.map { it.position }.toSet().size != questions.size) {
            fail(BackupError.BAD_INDEX, "题库内题目位置重复")
        }

        val sessionById = mutableMapOf<String, PracticeSessionRecord>()
        var activeSessions = 0
        var activeSingleReviews = 0
        for (session in sessions) {
            if (session.libraryId != record.id) fail(BackupError.DANGLING_REF, "练习会话不属于该题库")
            if (session.id.isBlank() || session.id.length > 128) fail(BackupError.BAD_FIELD, "会话标识无效")
            if (sessionById.put(session.id, session) != null) {
                fail(BackupError.DUPLICATE_ID, "会话标识重复：${session.id}")
            }
            if (session.mode !in setOf(PracticeModes.SEQUENTIAL, PracticeModes.RANDOM)) {
                fail(BackupError.BAD_ENUM, "练习模式无效：${session.mode}")
            }
            if (session.status !in setOf(
                    PracticeStatuses.ACTIVE,
                    PracticeStatuses.COMPLETED,
                    PracticeStatuses.SUPERSEDED
                )
            ) {
                fail(BackupError.BAD_ENUM, "练习状态无效：${session.status}")
            }
            if (session.phase !in setOf(
                    PracticePhases.ANSWERING,
                    PracticePhases.FEEDBACK,
                    PracticePhases.COMPLETED
                )
            ) {
                fail(BackupError.BAD_ENUM, "练习阶段无效：${session.phase}")
            }
            if (session.source !in setOf(PracticeSources.NORMAL, PracticeSources.WRONGBOOK, PracticeSources.SINGLE_REVIEW)) {
                fail(BackupError.BAD_ENUM, "练习来源无效：${session.source}")
            }
            if (session.questions.isEmpty()) fail(BackupError.BAD_INDEX, "练习会话没有题目快照")
            if (session.source == PracticeSources.SINGLE_REVIEW && session.questions.size != 1)
                fail(BackupError.BAD_FIELD, "单题复习必须只包含一道题")
            if (session.draftRevision < 0) fail(BackupError.BAD_FIELD, "草稿版本不能为负")
            if (session.createdAt < 0 || session.updatedAt < session.createdAt) {
                fail(BackupError.BAD_FIELD, "练习会话时间无效")
            }
            val completed = session.status == PracticeStatuses.COMPLETED
            if (completed != (session.phase == PracticePhases.COMPLETED)) {
                fail(BackupError.BAD_FIELD, "练习状态与阶段不一致")
            }
            if (completed != (session.completedAt != null)) {
                fail(BackupError.BAD_FIELD, "完成时间与练习状态不一致")
            }
            if (session.completedAt != null && session.completedAt < session.createdAt) {
                fail(BackupError.BAD_FIELD, "完成时间早于创建时间")
            }
            if (session.currentIndex < 0) fail(BackupError.BAD_INDEX, "当前题号不能为负")
            if (session.phase == PracticePhases.COMPLETED) {
                if (session.currentIndex != session.questions.size) {
                    fail(BackupError.BAD_INDEX, "已完成会话的当前题号必须等于题目数")
                }
            } else if (session.currentIndex >= session.questions.size) {
                fail(BackupError.BAD_INDEX, "当前题号超出会话题目范围")
            }
            val snapshotIds = mutableSetOf<String>()
            for (snapshot in session.questions) {
                val question = questionById[snapshot.questionId]
                    ?: throw BackupException(BackupError.DANGLING_REF, "会话引用了不存在的题目：${snapshot.questionId}")
                if (snapshot.libraryId != record.id) {
                    fail(BackupError.DANGLING_REF, "会话快照不属于该题库")
                }
                if (snapshot.position != question.position) {
                    fail(BackupError.BAD_INDEX, "会话快照题目位置与题库不一致：${snapshot.questionId}")
                }
                if (!snapshotIds.add(snapshot.questionId)) {
                    fail(BackupError.DUPLICATE_ID, "同一会话重复包含题目：${snapshot.questionId}")
                }
            }
            if (session.status == PracticeStatuses.ACTIVE) {
                if (session.source == PracticeSources.SINGLE_REVIEW) activeSingleReviews += 1 else activeSessions += 1
            }
            val draft = session.draft
            if (session.phase != PracticePhases.ANSWERING) {
                if (draft.selectedKeys.isNotEmpty() || draft.blankAnswers.isNotEmpty() ||
                    draft.text.isNotEmpty() || draft.referenceRevealed
                ) {
                    fail(BackupError.BAD_FIELD, "非作答阶段不应保留草稿")
                }
            }
        }
        if (activeSessions > 1) fail(BackupError.BAD_FIELD, "同一题库最多只能有一个进行中的练习")
        if (activeSingleReviews > 1) fail(BackupError.BAD_FIELD, "同一题库最多只能有一个进行中的单题复习")

        val answerKeys = mutableSetOf<String>()
        for (backup in answers) {
            val answer = backup.answer
            if (answer.id.isBlank() || answer.id.length > 128) fail(BackupError.BAD_FIELD, "作答标识无效")
            if (answer.submittedAt < 0) fail(BackupError.BAD_FIELD, "作答时间无效")
            val session = sessionById[answer.sessionId]
                ?: throw BackupException(BackupError.DANGLING_REF, "作答引用了不存在的会话：${answer.sessionId}")
            if (answer.position < 0 || answer.position >= session.questions.size) {
                fail(BackupError.BAD_INDEX, "作答位置超出会话题目范围")
            }
            if (session.questions[answer.position].questionId != answer.questionId) {
                fail(BackupError.DANGLING_REF, "作答题目与会话快照不一致")
            }
            if (!answerKeys.add("${answer.sessionId}:${answer.position}")) {
                fail(BackupError.DUPLICATE_ID, "同一会话的同一题存在多条作答")
            }
            val expected = when (answer.kind) {
                PracticeAnswerKinds.AUTOMATIC -> setOf(PracticeResults.CORRECT, PracticeResults.INCORRECT)
                PracticeAnswerKinds.SELF_ASSESSMENT -> setOf(
                    PracticeResults.REMEMBERED,
                    PracticeResults.FORGOTTEN
                )
                PracticeAnswerKinds.SKIPPED -> setOf(PracticeResults.SKIPPED)
                PracticeAnswerKinds.PEEKED -> setOf(PracticeResults.FORGOTTEN)
                else -> fail(BackupError.BAD_ENUM, "作答类别无效：${answer.kind}")
            }
            if (answer.result !in expected) fail(BackupError.BAD_ENUM, "作答结果与类别不一致")
            val snapshot = backup.referenceSnapshot
            if (snapshot != null) {
                val sessionSnapshot = session.questions[answer.position]
                if (snapshot.libraryId != record.id) {
                    fail(BackupError.DANGLING_REF, "作答参考快照不属于该题库")
                }
                // answer.position is the session index; snapshot.position is the library position.
                // Compare with the session's fixed snapshot for that index, never with the current
                // (possibly edited) library question and never position-to-index.
                if (snapshot != sessionSnapshot) {
                    fail(BackupError.DANGLING_REF, "作答参考快照与当次会话题目不一致")
                }
            }
        }

        val answersBySession = answers.groupBy { it.answer.sessionId }
        for (session in sessions) {
            val actualPositions = answersBySession[session.id].orEmpty()
                .map { it.answer.position }
                .toSet()
            val requiredPositions = when (session.phase) {
                // The current question has no committed answer yet while answering.
                PracticePhases.ANSWERING -> (0 until session.currentIndex).toSet()
                // Feedback means the current question was just committed.
                PracticePhases.FEEDBACK -> (0..session.currentIndex).toSet()
                PracticePhases.COMPLETED -> (0 until session.questions.size).toSet()
                else -> emptySet()
            }
            if (actualPositions != requiredPositions) {
                fail(
                    BackupError.BAD_INDEX,
                    "练习进度与阶段不匹配：缺少或多出作答（会话 ${session.id}）"
                )
            }
        }
    }

    fun activeSessionCount(sessions: List<PracticeSessionRecord>): Int =
        sessions.count { it.status == PracticeStatuses.ACTIVE && it.source != PracticeSources.SINGLE_REVIEW }

    private fun fail(error: BackupError, message: String): Nothing = throw BackupException(error, message)
}
