package com.offlinequiz.app.model

import kotlin.math.roundToInt

/** Display totals only: persisted automatic/self-assessment origins remain unchanged. */
data class PracticeTotals(val correct: Int, val incorrect: Int) {
    val effective: Int get() = correct + incorrect
    val accuracyPercent: Int? get() = if (effective == 0) null else (correct * 100.0 / effective).roundToInt()
    val accuracyLabel: String get() = accuracyPercent?.let { "$it%" } ?: "暂无"
}

data class PracticeRoundCounts(val normalCompleted: Int = 0, val wrongbookCompleted: Int = 0) {
    companion object {
        fun fromSessions(sessions: Collection<PracticeSessionRecord>): PracticeRoundCounts {
            val completed = sessions.filter {
                it.status == PracticeStatuses.COMPLETED && it.phase == PracticePhases.COMPLETED && it.completedAt != null
            }
            return PracticeRoundCounts(
                completed.count { it.source == PracticeSources.NORMAL },
                completed.count { it.source == PracticeSources.WRONGBOOK }
            )
        }
    }
}

data class PracticeStatsSnapshot(val history: List<PracticeHistoryEntry>, val rounds: PracticeRoundCounts)

/** Where a practice session came from. Persisted so a resumed session keeps its label. */
object PracticeSources {
    const val NORMAL = "normal"
    const val WRONGBOOK = "wrongbook"
    const val SINGLE_REVIEW = "single_review"

    fun label(source: String): String = when (source) {
        WRONGBOOK -> "错题重练"
        SINGLE_REVIEW -> "单题复习"
        else -> "练习"
    }

    fun isWrongbook(source: String): Boolean = source == WRONGBOOK || source == SINGLE_REVIEW
}

/**
 * A submitted answer together with the content snapshot it was judged against.
 *
 * `referenceSnapshot` is decoded from the answer row, so history always shows what the question
 * looked like at submission time instead of the edited current text.
 */
data class PracticeHistoryEntry(
    val answer: PracticeRecordedAnswer,
    val referenceSnapshot: PracticeQuestionSnapshot?,
    val sessionSource: String = PracticeSources.NORMAL
)

/**
 * Canonical form of the judging-relevant content of a practice snapshot.
 *
 * Two records belong to the same content version only when these strings are equal. Original
 * number, position, timestamps and the library id are deliberately excluded: they are not judging
 * evidence, and reordering a library must not invalidate a correct answer.
 */
object PracticeContentVersion {

    private const val FIELD = '\u0001'
    private const val ITEM = '\u0002'
    private const val GROUP = '\u0003'

    fun canonical(snapshot: PracticeQuestionSnapshot): String = buildString {
        append(snapshot.type).append(FIELD)
        append(snapshot.stem.trim()).append(FIELD)
        snapshot.options.forEach { option ->
            append(option.key.trim()).append(ITEM).append(option.text.trim()).append(FIELD)
        }
        append(GROUP)
        snapshot.answers.forEach { append(it.trim()).append(ITEM) }
        append(GROUP)
        snapshot.acceptableAnswers.forEach { answersForBlank ->
            answersForBlank.forEach { append(it.trim()).append(ITEM) }
            append(GROUP)
        }
        append(GROUP)
        append(snapshot.referenceAnswer?.trim().orEmpty())
    }

    /** False for a missing snapshot: an unreadable record can never prove the current version. */
    fun matches(snapshot: PracticeQuestionSnapshot?, canonicalCurrent: String): Boolean =
        snapshot != null && canonical(snapshot) == canonicalCurrent
}

/** Current mastery of the *current* content version; historical errors never disappear. */
sealed interface PracticeMastery {
    data object NotPracticed : PracticeMastery
    data class Due(val kind: String) : PracticeMastery
    data object DueAfterEdit : PracticeMastery
    data class Passed(val kind: String) : PracticeMastery

    val isDue: Boolean
        get() = this is Due || this is DueAfterEdit

    val label: String
        get() = when (this) {
            NotPracticed -> "当前版本尚未练习"
            is Due -> when (kind) {
                PracticeAnswerKinds.AUTOMATIC -> "待复习（自动答错）"
                PracticeAnswerKinds.PEEKED -> "待复习（偷看答案）"
                else -> "待复习（自评没记住）"
            }
            DueAfterEdit -> "题目已修改，待重新练习"
            is Passed -> if (kind == PracticeAnswerKinds.AUTOMATIC) {
                "已通过（自动判题）"
            } else {
                "已通过（自评）"
            }
        }
}

/** Aggregate history for one question of one library. */
data class PracticeQuestionStats(
    val questionId: String,
    val libraryId: String,
    val position: Int,
    val type: String,
    val originalNumber: String?,
    val stem: String,
    val practicable: Boolean,
    val automaticCorrect: Int,
    val automaticIncorrect: Int,
    val remembered: Int,
    val forgotten: Int,
    val skipped: Int,
    val lastPracticeAt: Long?,
    val contentChanged: Boolean,
    val historicalWrong: Boolean,
    val mastery: PracticeMastery,
    val optionTexts: List<String> = emptyList()
) {
    val automaticTotal: Int get() = automaticCorrect + automaticIncorrect
    val selfTotal: Int get() = remembered + forgotten
    val totals: PracticeTotals get() = PracticeTotals(automaticCorrect + remembered, automaticIncorrect + forgotten)

    /** Practice count never includes skips. */
    val practiceCount: Int get() = automaticTotal + selfTotal

    /** Historical errors: automatic and self-assessment are reported separately. */
    val historicalWrongCount: Int get() = automaticIncorrect + forgotten

    val stemSummary: String
        get() = stem.replace('\n', ' ').trim().let {
            if (it.length <= 80) it else it.take(80) + "…"
        }
}

data class PracticeLibraryStats(
    val practiceCount: Int,
    val automaticCorrect: Int,
    val automaticIncorrect: Int,
    val remembered: Int,
    val forgotten: Int,
    val skipped: Int,
    val questionCount: Int,
    val lastPracticeAt: Long?,
    val wrongQuestionCount: Int,
    val dueQuestionCount: Int,
    val rounds: PracticeRoundCounts = PracticeRoundCounts()
) {
    val automaticTotal: Int get() = automaticCorrect + automaticIncorrect
    val selfTotal: Int get() = remembered + forgotten
    val totals: PracticeTotals get() = PracticeTotals(automaticCorrect + remembered, automaticIncorrect + forgotten)

    /** Null means "no automatic answer yet"; the UI must not show 0% or NaN. */
    val automaticAccuracyPercent: Int?
        get() = if (automaticTotal == 0) null else (automaticCorrect * 100.0 / automaticTotal).roundToInt()

    val automaticAccuracyLabel: String
        get() = automaticAccuracyPercent?.let { "$it%（$automaticCorrect/$automaticTotal）" } ?: "暂无自动作答"
}

data class PracticeLibraryReport(
    val overview: PracticeLibraryStats,
    /** One entry per current question that has any submitted answer, ordered by library position. */
    val questions: List<PracticeQuestionStats>,
    /** Every question that was ever answered incorrectly (automatic) or "forgotten" (self). */
    val wrongbook: List<PracticeQuestionStats>,
    /** Wrongbook questions whose current content version is still due. */
    val due: List<PracticeQuestionStats>
)

data class QuestionHistoryRecord(
    val answer: PracticeRecordedAnswer,
    val referenceSnapshot: PracticeQuestionSnapshot?,
    /** True when this record was judged against the question text currently in the library. */
    val currentContentVersion: Boolean
)

data class PracticeQuestionHistory(
    val question: StoredQuestion?,
    val questionId: String,
    val stats: PracticeQuestionStats?,
    /** Newest first, with a stable tie-break for identical timestamps. */
    val records: List<QuestionHistoryRecord>
)

/**
 * Aggregates persisted answers into per-library and per-question statistics.
 *
 * Statistics are derived from `practice_answers` every time; there is no separate counter that can
 * drift. Submitted answers of active, completed and superseded sessions all count, while drafts
 * (never rows) do not.
 */
object PracticeStatsCalculator {

    fun report(
        questions: List<StoredQuestion>,
        history: List<PracticeHistoryEntry>,
        rounds: PracticeRoundCounts = PracticeRoundCounts()
    ): PracticeLibraryReport {
        val byQuestion = history.groupBy { it.answer.questionId }
        val stats = questions.sortedBy { it.position }
            .mapNotNull { question ->
                byQuestion[question.id]?.let { entries -> questionStats(question, entries) }
            }
        // Sorting happens once per computed report, never during recomposition.
        val wrongbook = stats.filter { it.historicalWrong }.sortedWith(
            compareByDescending<PracticeQuestionStats> { it.lastPracticeAt ?: Long.MIN_VALUE }
                .thenBy { it.position }
        )
        return PracticeLibraryReport(
            overview = overview(history, stats).copy(rounds = rounds),
            questions = stats,
            wrongbook = wrongbook,
            due = wrongbook.filter { it.mastery.isDue }
        )
    }

    fun questionHistory(
        question: StoredQuestion?,
        history: List<PracticeHistoryEntry>
    ): PracticeQuestionHistory {
        val stats = question?.let { questionStats(it, history) }
        val current = question?.let { PracticeContentVersion.canonical(it.toPracticeSnapshot()) }
        val records = history
            .sortedWith(compareByDescending<PracticeHistoryEntry> { it.answer.submittedAt }
                .thenByDescending { it.answer.id })
            .map { entry ->
                QuestionHistoryRecord(
                    answer = entry.answer,
                    referenceSnapshot = entry.referenceSnapshot,
                    currentContentVersion = current != null &&
                        PracticeContentVersion.matches(entry.referenceSnapshot, current)
                )
            }
        return PracticeQuestionHistory(question, question?.id.orEmpty(), stats, records)
    }

    fun questionStats(
        question: StoredQuestion,
        entries: List<PracticeHistoryEntry>
    ): PracticeQuestionStats {
        val current = PracticeContentVersion.canonical(question.toPracticeSnapshot())
        var automaticCorrect = 0
        var automaticIncorrect = 0
        var remembered = 0
        var forgotten = 0
        var skipped = 0
        var lastPracticeAt: Long? = null
        var lastCurrentVersionAnswer: PracticeHistoryEntry? = null
        var contentChanged = false

        val ordered = entries.sortedWith(
            compareBy({ it.answer.submittedAt }, { it.answer.id })
        )
        for (entry in ordered) {
            val answer = entry.answer
            when (answer.kind) {
                PracticeAnswerKinds.AUTOMATIC -> when (answer.result) {
                    PracticeResults.CORRECT -> automaticCorrect++
                    PracticeResults.INCORRECT -> automaticIncorrect++
                }
                PracticeAnswerKinds.SELF_ASSESSMENT, PracticeAnswerKinds.PEEKED -> when (answer.result) {
                    PracticeResults.REMEMBERED -> remembered++
                    PracticeResults.FORGOTTEN -> forgotten++
                }
                PracticeAnswerKinds.SKIPPED -> skipped++
            }
            if (answer.kind == PracticeAnswerKinds.AUTOMATIC ||
                answer.kind == PracticeAnswerKinds.SELF_ASSESSMENT || answer.kind == PracticeAnswerKinds.PEEKED
            ) {
                lastPracticeAt = maxOf(lastPracticeAt ?: answer.submittedAt, answer.submittedAt)
                if (PracticeContentVersion.matches(entry.referenceSnapshot, current)) {
                    lastCurrentVersionAnswer = entry
                } else if (entry.referenceSnapshot != null) {
                    contentChanged = true
                }
            }
        }

        val historicalWrong = automaticIncorrect > 0 || forgotten > 0
        val mastery = when {
            lastCurrentVersionAnswer != null -> when (lastCurrentVersionAnswer.answer.result) {
                PracticeResults.CORRECT -> PracticeMastery.Passed(PracticeAnswerKinds.AUTOMATIC)
                PracticeResults.INCORRECT -> PracticeMastery.Due(PracticeAnswerKinds.AUTOMATIC)
                PracticeResults.REMEMBERED -> PracticeMastery.Passed(PracticeAnswerKinds.SELF_ASSESSMENT)
                PracticeResults.FORGOTTEN -> PracticeMastery.Due(lastCurrentVersionAnswer.answer.kind)
                else -> PracticeMastery.NotPracticed
            }
            historicalWrong -> PracticeMastery.DueAfterEdit
            else -> PracticeMastery.NotPracticed
        }

        return PracticeQuestionStats(
            questionId = question.id,
            libraryId = question.libraryId,
            position = question.position,
            type = question.type,
            originalNumber = question.originalNumber,
            stem = question.stem,
            practicable = PracticeEligibility.classify(question) == EligibilityStatus.ELIGIBLE,
            automaticCorrect = automaticCorrect,
            automaticIncorrect = automaticIncorrect,
            remembered = remembered,
            forgotten = forgotten,
            skipped = skipped,
            lastPracticeAt = lastPracticeAt,
            contentChanged = contentChanged,
            historicalWrong = historicalWrong,
            mastery = mastery,
            optionTexts = question.options.map { it.text }
        )
    }

    private fun overview(
        history: List<PracticeHistoryEntry>,
        stats: List<PracticeQuestionStats>
    ): PracticeLibraryStats {
        var automaticCorrect = 0
        var automaticIncorrect = 0
        var remembered = 0
        var forgotten = 0
        var skipped = 0
        var lastPracticeAt: Long? = null
        val answeredQuestions = mutableSetOf<String>()

        for (entry in history) {
            val answer = entry.answer
            answeredQuestions += answer.questionId
            when (answer.kind) {
                PracticeAnswerKinds.AUTOMATIC -> when (answer.result) {
                    PracticeResults.CORRECT -> automaticCorrect++
                    PracticeResults.INCORRECT -> automaticIncorrect++
                }
                PracticeAnswerKinds.SELF_ASSESSMENT, PracticeAnswerKinds.PEEKED -> when (answer.result) {
                    PracticeResults.REMEMBERED -> remembered++
                    PracticeResults.FORGOTTEN -> forgotten++
                }
                PracticeAnswerKinds.SKIPPED -> skipped++
            }
            if (answer.kind == PracticeAnswerKinds.AUTOMATIC ||
                answer.kind == PracticeAnswerKinds.SELF_ASSESSMENT || answer.kind == PracticeAnswerKinds.PEEKED
            ) {
                lastPracticeAt = maxOf(lastPracticeAt ?: answer.submittedAt, answer.submittedAt)
            }
        }

        return PracticeLibraryStats(
            practiceCount = automaticCorrect + automaticIncorrect + remembered + forgotten,
            automaticCorrect = automaticCorrect,
            automaticIncorrect = automaticIncorrect,
            remembered = remembered,
            forgotten = forgotten,
            skipped = skipped,
            questionCount = answeredQuestions.size,
            lastPracticeAt = lastPracticeAt,
            wrongQuestionCount = stats.count { it.historicalWrong },
            dueQuestionCount = stats.count { it.historicalWrong && it.mastery.isDue }
        )
    }
}

/** Plain-text rendering of a recorded answer for history screens (no Compose dependency). */
object PracticeHistoryText {

    fun kindLabel(kind: String, result: String): String = when (kind) {
        PracticeAnswerKinds.AUTOMATIC -> "自动判题 · ${resultLabel(result)}"
        PracticeAnswerKinds.SELF_ASSESSMENT -> "自评 · ${resultLabel(result)}"
        PracticeAnswerKinds.SKIPPED -> "跳过"
        PracticeAnswerKinds.PEEKED -> "偷看答案 · 错误"
        else -> "记录"
    }

    fun resultLabel(result: String): String = when (result) {
        PracticeResults.CORRECT -> "正确"
        PracticeResults.INCORRECT -> "错误"
        PracticeResults.REMEMBERED -> "记得"
        PracticeResults.FORGOTTEN -> "没记住"
        PracticeResults.SKIPPED -> "跳过"
        else -> "已记录"
    }

    fun userAnswer(snapshot: PracticeQuestionSnapshot?, answer: PracticeUserAnswer): String {
        val type = snapshot?.type
        return when (type) {
            QuestionTypes.SINGLE_CHOICE,
            QuestionTypes.MULTIPLE_CHOICE -> answer.selectedKeys.joinToString("、").ifEmpty { "未作答" }
            QuestionTypes.TRUE_FALSE -> when (answer.selectedKeys.firstOrNull()) {
                "√" -> "正确（√）"
                "×" -> "错误（×）"
                else -> "未作答"
            }
            QuestionTypes.FILL_BLANK -> if (answer.blankAnswers.isEmpty()) {
                "未作答"
            } else {
                answer.blankAnswers.mapIndexed { index, value ->
                    "第 ${index + 1} 空：${value.ifBlank { "（空）" }}"
                }.joinToString("；")
            }
            QuestionTypes.SHORT_ANSWER -> answer.text.ifBlank { "未输入文字" }
            else -> fallbackUserAnswer(answer)
        }
    }

    fun referenceAnswer(snapshot: PracticeQuestionSnapshot?): String {
        if (snapshot == null) return "当时的题目快照不可用"
        return when (snapshot.type) {
            QuestionTypes.SINGLE_CHOICE,
            QuestionTypes.MULTIPLE_CHOICE -> snapshot.answers.joinToString("、").ifEmpty { "未设置" }
            QuestionTypes.TRUE_FALSE -> when (snapshot.answers.firstOrNull()?.trim().orEmpty()) {
                "√", "✓", "✔", "对", "正确", "T", "TRUE", "true" -> "正确（√）"
                "×", "✗", "✘", "错", "错误", "F", "FALSE", "false" -> "错误（×）"
                else -> snapshot.answers.firstOrNull()?.trim().orEmpty().ifEmpty { "未设置" }
            }
            QuestionTypes.FILL_BLANK -> snapshot.answers.mapIndexed { index, value ->
                val accepted = snapshot.acceptableAnswers.getOrNull(index).orEmpty()
                val suffix = if (accepted.isEmpty()) "" else "（可接受：${accepted.joinToString("；")}）"
                "第 ${index + 1} 空：$value$suffix"
            }.joinToString("；").ifEmpty { "未设置" }
            QuestionTypes.SHORT_ANSWER -> snapshot.referenceAnswer.orEmpty().ifEmpty { "未设置" }
            else -> "未设置"
        }
    }

    private fun fallbackUserAnswer(answer: PracticeUserAnswer): String {
        val parts = buildList {
            if (answer.selectedKeys.isNotEmpty()) add(answer.selectedKeys.joinToString("、"))
            if (answer.blankAnswers.any { it.isNotBlank() }) {
                add(answer.blankAnswers.joinToString("；") { it.ifBlank { "（空）" } })
            }
            if (answer.text.isNotBlank()) add(answer.text)
        }
        return parts.joinToString("；").ifEmpty { "未作答" }
    }
}
