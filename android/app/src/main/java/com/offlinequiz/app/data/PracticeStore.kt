package com.offlinequiz.app.data

import com.offlinequiz.app.model.PracticeDraft
import com.offlinequiz.app.model.PracticeHistoryEntry
import com.offlinequiz.app.model.PracticeRecordedAnswer
import com.offlinequiz.app.model.PracticeSessionRecord
import com.offlinequiz.app.model.PracticeSessionSummary
import com.offlinequiz.app.model.PracticeStatsSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class PracticeSubmitResult(
    val answer: PracticeRecordedAnswer,
    val alreadyRecorded: Boolean
)

sealed interface DraftSaveOutcome {
    data object Saved : DraftSaveOutcome
    data object Stale : DraftSaveOutcome
    data class Failed(val message: String) : DraftSaveOutcome
}

interface PracticeStore {
    /** Answers and completed-round counts read at one database boundary. */
    suspend fun libraryStatsSnapshot(libraryId: String): PracticeStatsSnapshot
    fun observeActiveSession(libraryId: String): Flow<PracticeSessionSummary?>
    suspend fun activeSession(libraryId: String): PracticeSessionSummary?
    suspend fun createSession(session: PracticeSessionRecord)
    suspend fun session(id: String): PracticeSessionRecord?
    suspend fun answers(sessionId: String): List<PracticeRecordedAnswer>
    suspend fun answerAt(sessionId: String, position: Int): PracticeRecordedAnswer?
    suspend fun submitAnswer(
        sessionId: String,
        expectedPosition: Int,
        answer: PracticeRecordedAnswer,
        referenceSnapshotJson: String
    ): PracticeSubmitResult?

    suspend fun advanceSession(
        sessionId: String,
        expectedPosition: Int,
        nextIndex: Int,
        completed: Boolean,
        now: Long
    ): PracticeSessionRecord?

    suspend fun saveDraft(
        sessionId: String,
        position: Int,
        draft: PracticeDraft,
        revision: Long,
        now: Long
    ): DraftSaveOutcome

    /** Submitted answers of one library (all session states) for statistics and wrongbook. */
    suspend fun historyForLibrary(libraryId: String): List<PracticeHistoryEntry>

    /** Submitted answers of one question, used by the per-question history screen. */
    suspend fun historyForQuestion(libraryId: String, questionId: String): List<PracticeHistoryEntry>
}

class RoomPracticeStore(private val dao: PracticeDao) : PracticeStore {

    override suspend fun libraryStatsSnapshot(libraryId: String): PracticeStatsSnapshot {
        val rows = dao.libraryStatsRows(libraryId)
        return PracticeStatsSnapshot(rows.history.map { it.toHistoryEntry() }, rows.rounds)
    }

    override fun observeActiveSession(libraryId: String): Flow<PracticeSessionSummary?> =
        dao.observeActiveSession(libraryId).map { row -> row?.toSummary() }

    override suspend fun activeSession(libraryId: String): PracticeSessionSummary? =
        dao.activeSession(libraryId)?.toSummary(answeredCount = null)

    override suspend fun createSession(session: PracticeSessionRecord) {
        dao.createSessionWithSupersede(session.toEntity())
    }

    override suspend fun session(id: String): PracticeSessionRecord? =
        dao.session(id)?.toModel()

    override suspend fun answers(sessionId: String): List<PracticeRecordedAnswer> =
        dao.answers(sessionId).map { it.toModel() }

    override suspend fun answerAt(sessionId: String, position: Int): PracticeRecordedAnswer? =
        dao.answerAt(sessionId, position)?.toModel()

    override suspend fun submitAnswer(
        sessionId: String,
        expectedPosition: Int,
        answer: PracticeRecordedAnswer,
        referenceSnapshotJson: String
    ): PracticeSubmitResult? {
        val entity = PracticeAnswerEntity(
            id = answer.id,
            sessionId = sessionId,
            questionId = answer.questionId,
            position = expectedPosition,
            kind = answer.kind,
            userAnswerJson = PracticeJson.encodeUserAnswer(answer.userAnswer),
            result = answer.result,
            referenceSnapshotJson = referenceSnapshotJson,
            idempotencyKey = "$sessionId:$expectedPosition",
            submittedAt = answer.submittedAt
        )
        val row = dao.submitAnswerAndSetFeedback(sessionId, expectedPosition, entity) ?: return null
        return PracticeSubmitResult(row.answer.toModel(), row.alreadyRecorded)
    }

    override suspend fun advanceSession(
        sessionId: String,
        expectedPosition: Int,
        nextIndex: Int,
        completed: Boolean,
        now: Long
    ): PracticeSessionRecord? =
        dao.advanceSession(sessionId, expectedPosition, nextIndex, completed, now)?.toModel()

    override suspend fun saveDraft(
        sessionId: String,
        position: Int,
        draft: PracticeDraft,
        revision: Long,
        now: Long
    ): DraftSaveOutcome {
        return try {
            val updated = dao.updateDraft(
                sessionId = sessionId,
                position = position,
                draftJson = PracticeJson.encodeDraft(draft),
                revision = revision,
                now = now
            )
            if (updated == 1) DraftSaveOutcome.Saved else DraftSaveOutcome.Stale
        } catch (e: Exception) {
            DraftSaveOutcome.Failed("草稿保存失败，请重试")
        }
    }

    override suspend fun historyForLibrary(libraryId: String): List<PracticeHistoryEntry> =
        dao.historyForLibrary(libraryId).map { it.toHistoryEntry() }

    override suspend fun historyForQuestion(
        libraryId: String,
        questionId: String
    ): List<PracticeHistoryEntry> =
        dao.historyForQuestion(libraryId, questionId).map { it.toHistoryEntry() }
}

fun PracticeSessionEntity.toModel(): PracticeSessionRecord = PracticeSessionRecord(
    id = id,
    libraryId = libraryId,
    mode = mode,
    status = status,
    questions = PracticeJson.decodeQuestions(questionsJson),
    currentIndex = currentIndex,
    phase = phase,
    draft = PracticeJson.decodeDraft(draftJson),
    draftRevision = draftRevision,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
    source = source
)

fun PracticeSessionRecord.toEntity(): PracticeSessionEntity = PracticeSessionEntity(
    id = id,
    libraryId = libraryId,
    mode = mode,
    status = status,
    questionOrderJson = QuizJson.encodeStrings(questions.map { it.questionId }),
    questionsJson = PracticeJson.encodeQuestions(questions),
    currentIndex = currentIndex,
    phase = phase,
    draftJson = PracticeJson.encodeDraft(draft),
    draftRevision = draftRevision,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
    source = source
)

fun PracticeAnswerEntity.toModel(): PracticeRecordedAnswer = PracticeRecordedAnswer(
    id = id,
    sessionId = sessionId,
    questionId = questionId,
    position = position,
    kind = kind,
    result = result,
    userAnswer = PracticeJson.decodeUserAnswer(userAnswerJson),
    submittedAt = submittedAt
)

private fun PracticeHistoryRow.toHistoryEntry(): PracticeHistoryEntry = PracticeHistoryEntry(
    answer = answer.toModel(),
    referenceSnapshot = PracticeJson.decodeQuestions(answer.referenceSnapshotJson).firstOrNull(),
    sessionSource = sessionSource
)

private fun ActiveSessionRow.toSummary(): PracticeSessionSummary {
    val total = QuizJson.decodeStrings(session.questionOrderJson).size
    return PracticeSessionSummary(
        id = session.id,
        libraryId = session.libraryId,
        mode = session.mode,
        status = session.status,
        total = total,
        currentIndex = session.currentIndex,
        phase = session.phase,
        answeredCount = answeredCount,
        createdAt = session.createdAt,
        updatedAt = session.updatedAt,
        source = session.source
    )
}

private fun PracticeSessionEntity.toSummary(answeredCount: Int?): PracticeSessionSummary {
    return PracticeSessionSummary(
        id = id,
        libraryId = libraryId,
        mode = mode,
        status = status,
        total = QuizJson.decodeStrings(questionOrderJson).size,
        currentIndex = currentIndex,
        phase = phase,
        answeredCount = answeredCount ?: 0,
        createdAt = createdAt,
        updatedAt = updatedAt,
        source = source
    )
}

