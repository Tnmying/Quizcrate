package com.offlinequiz.app.model

/** Immutable practice/session models. Snapshots are independent of later question edits. */

object PracticeModes {
    const val SEQUENTIAL = "sequential"
    const val RANDOM = "random"

    fun label(mode: String): String = when (mode) {
        SEQUENTIAL -> "顺序"
        RANDOM -> "随机"
        else -> "练习"
    }
}

object PracticeStatuses {
    const val ACTIVE = "active"
    const val COMPLETED = "completed"
    const val SUPERSEDED = "superseded"
}

object PracticePhases {
    const val ANSWERING = "answering"
    const val FEEDBACK = "feedback"
    const val COMPLETED = "completed"
}

object PracticeAnswerKinds {
    const val AUTOMATIC = "automatic"
    const val SELF_ASSESSMENT = "self_assessment"
    const val SKIPPED = "skipped"
    const val PEEKED = "peeked"
}

object PracticeResults {
    const val CORRECT = "correct"
    const val INCORRECT = "incorrect"
    const val REMEMBERED = "remembered"
    const val FORGOTTEN = "forgotten"
    const val SKIPPED = "skipped"
}

/**
 * The practice copy of a question. Deliberately excludes stemWithAnswer, originalSentence,
 * original options/answers/blanks and source evidence so no answer can leak before submission.
 */
data class PracticeQuestionSnapshot(
    val questionId: String,
    val libraryId: String,
    val position: Int,
    val type: String,
    val originalNumber: String?,
    val stem: String,
    val options: List<QuizOption>,
    val answers: List<String>,
    val acceptableAnswers: List<List<String>>,
    val referenceAnswer: String?,
    val editedAt: Long?
) {
    val typeLabel: String get() = QuestionTypes.label(type)
}

data class PracticeDraft(
    val selectedKeys: List<String> = emptyList(),
    val blankAnswers: List<String> = emptyList(),
    val text: String = "",
    val referenceRevealed: Boolean = false
) {
    fun toUserAnswer(): PracticeUserAnswer = PracticeUserAnswer(
        selectedKeys = selectedKeys,
        blankAnswers = blankAnswers,
        text = text
    )
}

data class PracticeUserAnswer(
    val selectedKeys: List<String> = emptyList(),
    val blankAnswers: List<String> = emptyList(),
    val text: String = ""
)

data class PracticeRecordedAnswer(
    val id: String,
    val sessionId: String,
    val questionId: String,
    val position: Int,
    val kind: String,
    val result: String,
    val userAnswer: PracticeUserAnswer,
    val submittedAt: Long
) {
    val resultLabel: String
        get() = when (result) {
            PracticeResults.CORRECT -> "正确"
            PracticeResults.INCORRECT -> "错误"
            PracticeResults.REMEMBERED -> "记得"
            PracticeResults.FORGOTTEN -> "没记住"
            PracticeResults.SKIPPED -> "跳过"
            else -> "已记录"
        }
}

data class PracticeSessionRecord(
    val id: String,
    val libraryId: String,
    val mode: String,
    val status: String,
    val questions: List<PracticeQuestionSnapshot>,
    val currentIndex: Int,
    val phase: String,
    val draft: PracticeDraft,
    val draftRevision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    /** [PracticeSources.NORMAL] or [PracticeSources.WRONGBOOK]. */
    val source: String = PracticeSources.NORMAL
) {
    val total: Int get() = questions.size
    val currentQuestion: PracticeQuestionSnapshot? get() = questions.getOrNull(currentIndex)
}

data class PracticeSessionSummary(
    val id: String,
    val libraryId: String,
    val mode: String,
    val status: String,
    val total: Int,
    val currentIndex: Int,
    val phase: String,
    val answeredCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val source: String = PracticeSources.NORMAL
) {
    val progressLabel: String
        get() = when {
            status == PracticeStatuses.COMPLETED -> "本轮已结束"
            total <= 0 -> "0 题"
            phase == PracticePhases.COMPLETED -> "本轮已结束"
            else -> "第 ${(currentIndex + 1).coerceAtMost(total)} / $total 题"
        }
}

data class PracticeResultSummary(
    val total: Int,
    val answered: Int,
    val automaticCorrect: Int,
    val automaticIncorrect: Int,
    val remembered: Int,
    val forgotten: Int,
    val skipped: Int
) {
    val totals: PracticeTotals get() = PracticeTotals(automaticCorrect + remembered, automaticIncorrect + forgotten)

    companion object {
        fun fromAnswers(total: Int, answers: List<PracticeRecordedAnswer>): PracticeResultSummary {
            return PracticeResultSummary(
                total = total,
                answered = answers.size,
                automaticCorrect = answers.count { it.result == PracticeResults.CORRECT },
                automaticIncorrect = answers.count { it.result == PracticeResults.INCORRECT },
                remembered = answers.count { it.result == PracticeResults.REMEMBERED },
                forgotten = answers.count { it.result == PracticeResults.FORGOTTEN },
                skipped = answers.count { it.result == PracticeResults.SKIPPED }
            )
        }
    }
}

data class PracticeState(
    val session: PracticeSessionRecord,
    val currentQuestion: PracticeQuestionSnapshot?,
    val feedback: PracticeRecordedAnswer?,
    val answers: List<PracticeRecordedAnswer>,
    val summary: PracticeResultSummary
)

data class PracticeEligibilityReport(
    val eligible: List<StoredQuestion>,
    val reviewExcluded: Int,
    val structureExcluded: Int,
    val unsupportedTypeExcluded: Int
) {
    val excludedTotal: Int
        get() = reviewExcluded + structureExcluded + unsupportedTypeExcluded
}
