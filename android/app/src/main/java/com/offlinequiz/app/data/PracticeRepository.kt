package com.offlinequiz.app.data

import com.offlinequiz.app.model.PracticeAnswerKinds
import com.offlinequiz.app.model.PracticeDraft
import com.offlinequiz.app.model.PracticeEligibility
import com.offlinequiz.app.model.PracticeEligibilityReport
import com.offlinequiz.app.model.PracticeJudge
import com.offlinequiz.app.model.PracticeModes
import com.offlinequiz.app.model.PracticePhases
import com.offlinequiz.app.model.PracticeQuestionSnapshot
import com.offlinequiz.app.model.PracticeRecordedAnswer
import com.offlinequiz.app.model.PracticeResultSummary
import com.offlinequiz.app.model.PracticeResults
import com.offlinequiz.app.model.PracticeSessionRecord
import com.offlinequiz.app.model.PracticeSessionSummary
import com.offlinequiz.app.model.PracticeSources
import com.offlinequiz.app.model.PracticeState
import com.offlinequiz.app.model.PracticeStatuses
import com.offlinequiz.app.model.PracticeUserAnswer
import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.model.StoredQuestion
import com.offlinequiz.app.model.toPracticeSnapshot
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow

sealed interface StartPracticeOutcome {
    data class Started(
        val sessionId: String,
        val total: Int,
        val initialState: PracticeState? = null
    ) : StartPracticeOutcome
    data class NoEligible(val report: PracticeEligibilityReport) : StartPracticeOutcome
    data class Failed(val message: String) : StartPracticeOutcome
}

sealed interface SubmitPracticeOutcome {
    data class Recorded(
        val answer: PracticeRecordedAnswer,
        val alreadyRecorded: Boolean
    ) : SubmitPracticeOutcome

    data class Rejected(val message: String) : SubmitPracticeOutcome
    data class Failed(val message: String) : SubmitPracticeOutcome
}

sealed interface AdvancePracticeOutcome {
    data class Advanced(val state: PracticeState) : AdvancePracticeOutcome
    data class Failed(val message: String) : AdvancePracticeOutcome
}

/**
 * Starts, resumes and records practice sessions. Answer snapshots are fixed at session creation, so
 * later question edits cannot silently change an existing result or an in-flight session.
 */
class PracticeRepository(
    private val libraryStore: LibraryStore,
    private val practiceStore: PracticeStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {

    fun observeActiveSession(libraryId: String): Flow<PracticeSessionSummary?> =
        practiceStore.observeActiveSession(libraryId)

    suspend fun activeSession(libraryId: String): PracticeSessionSummary? =
        practiceStore.activeSession(libraryId)

    fun eligibility(questions: List<StoredQuestion>): PracticeEligibilityReport =
        PracticeEligibility.evaluate(questions)

    suspend fun start(
        libraryId: String,
        mode: String,
        random: Random = Random.Default
    ): StartPracticeOutcome = startSession(
        libraryId = libraryId,
        mode = mode,
        source = PracticeSources.NORMAL,
        restrictTo = null,
        random = random
    )

    /**
     * Starts a re-practice round restricted to the given due questions. Only questions that pass
     * the normal practice eligibility rules right now are included, and their current content is
     * fixed as a snapshot at creation time (same rules as a normal round).
     */
    suspend fun startWrongbook(
        libraryId: String,
        mode: String,
        dueQuestionIds: Collection<String>,
        random: Random = Random.Default
    ): StartPracticeOutcome = startSession(
        libraryId = libraryId,
        mode = mode,
        source = PracticeSources.WRONGBOOK,
        restrictTo = dueQuestionIds.toSet(),
        random = random
    )

    /** A separate one-question session; does not replace the user's unfinished full round. */
    suspend fun startSingleReview(libraryId: String, questionId: String): StartPracticeOutcome {
        return try {
            val question = libraryStore.question(questionId)
                ?: return StartPracticeOutcome.Failed("题目不存在，请刷新错题本")
            if (question.libraryId != libraryId) return StartPracticeOutcome.Failed("题目不属于当前题库")
            val history = practiceStore.historyForQuestion(libraryId, questionId)
            if (!com.offlinequiz.app.model.PracticeStatsCalculator.questionStats(question, history).historicalWrong)
                return StartPracticeOutcome.Failed("这道题不在错题本中，请刷新后重试")
            val report = PracticeEligibility.evaluate(listOf(question))
            if (report.eligible.isEmpty()) return StartPracticeOutcome.NoEligible(report)
            val session = createSession(libraryId, PracticeModes.SEQUENTIAL, PracticeSources.SINGLE_REVIEW, report.eligible)
            StartPracticeOutcome.Started(
                session.id, session.total,
                PracticeState(session, session.currentQuestion, null, emptyList(),
                    PracticeResultSummary.fromAnswers(session.total, emptyList()))
            )
        } catch (_: Exception) {
            StartPracticeOutcome.Failed("创建单题复习失败，请重试")
        }
    }

    private suspend fun startSession(
        libraryId: String,
        mode: String,
        source: String,
        restrictTo: Set<String>?,
        random: Random
    ): StartPracticeOutcome {
        if (mode != PracticeModes.SEQUENTIAL && mode != PracticeModes.RANDOM) {
            return StartPracticeOutcome.Failed("未知的练习模式")
        }
        return try {
            libraryStore.library(libraryId)
                ?: return StartPracticeOutcome.Failed("题库不存在，无法开始练习")
            val candidates = libraryStore.questions(libraryId).let { all ->
                if (restrictTo == null) all else all.filter { it.id in restrictTo }
            }
            val report = PracticeEligibility.evaluate(candidates)
            if (report.eligible.isEmpty()) return StartPracticeOutcome.NoEligible(report)
            val ordered = if (mode == PracticeModes.RANDOM) {
                report.eligible.shuffled(random)
            } else {
                report.eligible.sortedWith(compareBy({ it.position }, { it.id }))
            }
            val session = createSession(libraryId, mode, source, ordered)
            StartPracticeOutcome.Started(session.id, session.total)
        } catch (e: Exception) {
            StartPracticeOutcome.Failed("创建练习失败，未写入任何会话")
        }
    }

    private suspend fun createSession(
        libraryId: String,
        mode: String,
        source: String,
        ordered: List<StoredQuestion>
    ): PracticeSessionRecord {
        val now = clock()
        val session = PracticeSessionRecord(
            id = idFactory(),
            libraryId = libraryId,
            mode = mode,
            status = PracticeStatuses.ACTIVE,
            questions = ordered.map { it.toPracticeSnapshot() },
            currentIndex = 0,
            phase = PracticePhases.ANSWERING,
            draft = PracticeDraft(),
            draftRevision = 0L,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
            source = source
        )
        practiceStore.createSession(session)
        return session
    }

    suspend fun loadState(sessionId: String): PracticeState? {
        return try {
            val session = practiceStore.session(sessionId) ?: return null
            val answers = practiceStore.answers(sessionId)
            val feedback = if (session.phase == PracticePhases.FEEDBACK) {
                practiceStore.answerAt(sessionId, session.currentIndex)
            } else {
                null
            }
            PracticeState(
                session = session,
                currentQuestion = session.currentQuestion,
                feedback = feedback,
                answers = answers,
                summary = PracticeResultSummary.fromAnswers(session.total, answers)
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun submitAutomatic(
        sessionId: String,
        expectedPosition: Int,
        userAnswer: PracticeUserAnswer
    ): SubmitPracticeOutcome {
        val session = loadSession(sessionId) ?: return SubmitPracticeOutcome.Failed("练习会话不存在")
        existingFeedback(session, expectedPosition)?.let { return it }
        val question = validateAnsweringPosition(session, expectedPosition)
            ?: return SubmitPracticeOutcome.Failed("题目状态已变化，请重试")
        if (!PracticeJudge.isAutomaticType(question.type)) {
            return SubmitPracticeOutcome.Rejected("该题需要在展开参考答案后自评")
        }
        val draft = PracticeDraft(
            selectedKeys = userAnswer.selectedKeys,
            blankAnswers = userAnswer.blankAnswers,
            text = userAnswer.text
        )
        if (!PracticeJudge.hasAnswer(question, draft)) {
            return SubmitPracticeOutcome.Rejected("请先作答，或使用“跳过”")
        }
        val correct = PracticeJudge.judge(question, userAnswer)
            ?: return SubmitPracticeOutcome.Rejected("该题型不支持自动判题")
        return record(session, question, expectedPosition, PracticeAnswerKinds.AUTOMATIC,
            if (correct) PracticeResults.CORRECT else PracticeResults.INCORRECT, userAnswer)
    }

    suspend fun submitSelfAssessment(
        sessionId: String,
        expectedPosition: Int,
        userAnswer: PracticeUserAnswer,
        remembered: Boolean
    ): SubmitPracticeOutcome {
        val session = loadSession(sessionId) ?: return SubmitPracticeOutcome.Failed("练习会话不存在")
        existingFeedback(session, expectedPosition)?.let { return it }
        val question = validateAnsweringPosition(session, expectedPosition)
            ?: return SubmitPracticeOutcome.Failed("题目状态已变化，请重试")
        if (question.type != QuestionTypes.FILL_BLANK && question.type != QuestionTypes.SHORT_ANSWER) {
            return SubmitPracticeOutcome.Rejected("该题不支持心中作答自评")
        }
        return record(
            session,
            question,
            expectedPosition,
            PracticeAnswerKinds.SELF_ASSESSMENT,
            if (remembered) PracticeResults.REMEMBERED else PracticeResults.FORGOTTEN,
            userAnswer
        )
    }

    suspend fun peek(
        sessionId: String,
        expectedPosition: Int,
        userAnswer: PracticeUserAnswer
    ): SubmitPracticeOutcome {
        val session = loadSession(sessionId) ?: return SubmitPracticeOutcome.Failed("练习会话不存在")
        existingFeedback(session, expectedPosition)?.let { return it }
        val question = validateAnsweringPosition(session, expectedPosition)
            ?: return SubmitPracticeOutcome.Failed("题目状态已变化，请重试")
        return record(session, question, expectedPosition, PracticeAnswerKinds.PEEKED,
            PracticeResults.FORGOTTEN, userAnswer)
    }

    suspend fun skip(
        sessionId: String,
        expectedPosition: Int,
        userAnswer: PracticeUserAnswer
    ): SubmitPracticeOutcome {
        val session = loadSession(sessionId) ?: return SubmitPracticeOutcome.Failed("练习会话不存在")
        existingFeedback(session, expectedPosition)?.let { return it }
        val question = validateAnsweringPosition(session, expectedPosition)
            ?: return SubmitPracticeOutcome.Failed("题目状态已变化，请重试")
        return record(
            session,
            question,
            expectedPosition,
            PracticeAnswerKinds.SKIPPED,
            PracticeResults.SKIPPED,
            userAnswer
        )
    }

    suspend fun advance(sessionId: String, expectedPosition: Int): AdvancePracticeOutcome {
        return try {
            val session = practiceStore.session(sessionId)
                ?: return AdvancePracticeOutcome.Failed("练习会话不存在")
            if (session.phase != PracticePhases.FEEDBACK || session.currentIndex != expectedPosition) {
                return AdvancePracticeOutcome.Failed("当前不在可前进的反馈状态")
            }
            val completed = expectedPosition + 1 >= session.total
            val nextIndex = if (completed) session.total else expectedPosition + 1
            practiceStore.advanceSession(
                sessionId = sessionId,
                expectedPosition = expectedPosition,
                nextIndex = nextIndex,
                completed = completed,
                now = clock()
            ) ?: return AdvancePracticeOutcome.Failed("进度保存失败，请重试")
            val state = loadState(sessionId)
                ?: return AdvancePracticeOutcome.Failed("进度已保存，但读取失败，请重新打开练习")
            AdvancePracticeOutcome.Advanced(state)
        } catch (_: Exception) {
            AdvancePracticeOutcome.Failed("进度保存失败，请重试")
        }
    }

    suspend fun saveDraft(
        sessionId: String,
        position: Int,
        draft: PracticeDraft,
        revision: Long
    ): DraftSaveOutcome {
        return try {
            practiceStore.saveDraft(sessionId, position, draft, revision, clock())
        } catch (_: Exception) {
            DraftSaveOutcome.Failed("草稿保存失败，请重试")
        }
    }

    private suspend fun loadSession(sessionId: String): PracticeSessionRecord? {
        return try {
            practiceStore.session(sessionId)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * A retry after a committed submit (e.g. the UI missed the response) must return the stored
     * answer instead of recording the position a second time.
     */
    private suspend fun existingFeedback(
        session: PracticeSessionRecord,
        expectedPosition: Int
    ): SubmitPracticeOutcome? {
        if (session.phase != PracticePhases.FEEDBACK || session.currentIndex != expectedPosition) {
            return null
        }
        val existing = practiceStore.answerAt(session.id, expectedPosition) ?: return null
        return SubmitPracticeOutcome.Recorded(existing, alreadyRecorded = true)
    }

    private fun validateAnsweringPosition(
        session: PracticeSessionRecord,
        expectedPosition: Int
    ): PracticeQuestionSnapshot? {
        if (session.status != PracticeStatuses.ACTIVE) return null
        if (session.phase != PracticePhases.ANSWERING) return null
        if (session.currentIndex != expectedPosition) return null
        return session.questions.getOrNull(expectedPosition)
    }

    private suspend fun record(
        session: PracticeSessionRecord,
        question: PracticeQuestionSnapshot,
        position: Int,
        kind: String,
        result: String,
        userAnswer: PracticeUserAnswer
    ): SubmitPracticeOutcome {
        val now = clock()
        val answer = PracticeRecordedAnswer(
            id = idFactory(),
            sessionId = session.id,
            questionId = question.questionId,
            position = position,
            kind = kind,
            result = result,
            userAnswer = userAnswer,
            submittedAt = now
        )
        return try {
            val stored = practiceStore.submitAnswer(
                sessionId = session.id,
                expectedPosition = position,
                answer = answer,
                referenceSnapshotJson = PracticeJson.encodeQuestions(listOf(question))
            ) ?: return SubmitPracticeOutcome.Failed("提交失败，当前题目状态已变化")
            SubmitPracticeOutcome.Recorded(stored.answer, stored.alreadyRecorded)
        } catch (_: Exception) {
            SubmitPracticeOutcome.Failed("保存作答失败，输入已保留，请重试")
        }
    }
}
