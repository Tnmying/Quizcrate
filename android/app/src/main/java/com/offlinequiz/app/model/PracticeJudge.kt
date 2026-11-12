package com.offlinequiz.app.model

/**
 * Conservative, pure judging rules for the five supported question types.
 *
 * Fill-blank normalization only handles layout differences: full-width ASCII -> half-width,
 * leading/trailing whitespace and runs of layout whitespace. It never deletes internal spaces,
 * punctuation, digits or negation, and never uses NFKC or similarity matching.
 */
object PracticeJudge {

    fun isSupportedType(type: String): Boolean = when (type) {
        QuestionTypes.SINGLE_CHOICE,
        QuestionTypes.MULTIPLE_CHOICE,
        QuestionTypes.TRUE_FALSE,
        QuestionTypes.FILL_BLANK,
        QuestionTypes.SHORT_ANSWER -> true
        else -> false
    }

    fun isAutomaticType(type: String): Boolean = when (type) {
        QuestionTypes.SINGLE_CHOICE,
        QuestionTypes.MULTIPLE_CHOICE,
        QuestionTypes.TRUE_FALSE,
        QuestionTypes.FILL_BLANK -> true
        else -> false
    }

    /** Returns null for short answer/unsupported types: those are self-assessed, not auto-judged. */
    fun judge(question: PracticeQuestionSnapshot, answer: PracticeUserAnswer): Boolean? {
        return when (question.type) {
            QuestionTypes.SINGLE_CHOICE -> judgeSingle(question, answer)
            QuestionTypes.MULTIPLE_CHOICE -> judgeMultiple(question, answer)
            QuestionTypes.TRUE_FALSE -> judgeTrueFalse(question, answer)
            QuestionTypes.FILL_BLANK -> judgeFill(question, answer)
            else -> null
        }
    }

    fun hasAnswer(question: PracticeQuestionSnapshot, draft: PracticeDraft): Boolean {
        return when (question.type) {
            QuestionTypes.SINGLE_CHOICE,
            QuestionTypes.TRUE_FALSE -> draft.selectedKeys.size == 1
            QuestionTypes.MULTIPLE_CHOICE -> draft.selectedKeys.isNotEmpty()
            QuestionTypes.FILL_BLANK -> draft.blankAnswers.size == question.answers.size &&
                draft.blankAnswers.all { it.isNotBlank() }
            QuestionTypes.SHORT_ANSWER -> true
            else -> false
        }
    }

    fun isValidTrueFalseReference(question: PracticeQuestionSnapshot): Boolean {
        return question.answers.size == 1 && trueFalseValue(question.answers.first()) != null
    }

    fun trueFalseValue(raw: String?): Boolean? {
        val value = raw?.trim().orEmpty()
        return when (value) {
            "√", "✓", "✔", "对", "正确", "T", "TRUE", "true" -> true
            "×", "✗", "✘", "错", "错误", "F", "FALSE", "false" -> false
            else -> null
        }
    }

    /**
     * Layout-only normalization. Examples:
     * - `Ａ` -> `A`, `１．５` -> `1.5`, full-width space -> ASCII space
     * - `"  A   B  "` -> `"A B"`
     * - `"1.5" != "15"`, `"不能" != "能"`, `"A B" != "AB"`
     */
    fun normalizeFillText(raw: String): String {
        val halfWidth = StringBuilder(raw.length)
        for (char in raw) {
            when {
                char == '\u3000' || char == '\u00A0' || char.isWhitespace() -> halfWidth.append(' ')
                char.code in 0xFF01..0xFF5E -> halfWidth.append((char.code - 0xFEE0).toChar())
                else -> halfWidth.append(char)
            }
        }
        return halfWidth.toString()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun judgeSingle(question: PracticeQuestionSnapshot, answer: PracticeUserAnswer): Boolean {
        if (answer.selectedKeys.size != 1) return false
        val selected = answer.selectedKeys.single().trim().uppercase()
        return question.answers.size == 1 && selected == question.answers.single().trim().uppercase()
    }

    private fun judgeMultiple(question: PracticeQuestionSnapshot, answer: PracticeUserAnswer): Boolean {
        val selected = answer.selectedKeys.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
        val reference = question.answers.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
        if (selected.isEmpty() || reference.size < 2) return false
        // Set equality: fewer choices, extra choices and order changes are all handled strictly.
        return selected == reference
    }

    private fun judgeTrueFalse(question: PracticeQuestionSnapshot, answer: PracticeUserAnswer): Boolean {
        if (answer.selectedKeys.size != 1) return false
        val selected = trueFalseValue(answer.selectedKeys.single()) ?: return false
        val reference = trueFalseValue(question.answers.firstOrNull()) ?: return false
        return selected == reference
    }

    private fun judgeFill(question: PracticeQuestionSnapshot, answer: PracticeUserAnswer): Boolean {
        if (question.answers.isEmpty()) return false
        if (answer.blankAnswers.size != question.answers.size) return false
        return question.answers.indices.all { index ->
            val user = normalizeFillText(answer.blankAnswers[index])
            if (user.isEmpty()) return@all false
            val accepted = FillAnswerVariants.candidates(question.answers[index], question.acceptableAnswers.getOrNull(index).orEmpty())
                .map { normalizeFillText(it) }
                .filter { it.isNotEmpty() }
            user in accepted
        }
    }
}

enum class EligibilityStatus {
    ELIGIBLE,
    NEEDS_REVIEW,
    STRUCTURE_INVALID,
    UNSUPPORTED_TYPE
}

object PracticeEligibility {

    /** Single source of truth for the "can this question be practiced" rules. */
    fun classify(question: StoredQuestion): EligibilityStatus {
        if (!PracticeJudge.isSupportedType(question.type)) return EligibilityStatus.UNSUPPORTED_TYPE
        if (!question.confirmed && question.originalNeedsReview && !FillAnswerVariants.hasOnlyObsoleteVariantWarning(question)) return EligibilityStatus.NEEDS_REVIEW
        if (!isStructurallyPracticable(question)) return EligibilityStatus.STRUCTURE_INVALID
        return EligibilityStatus.ELIGIBLE
    }

    fun evaluate(questions: List<StoredQuestion>): PracticeEligibilityReport {
        var reviewExcluded = 0
        var structureExcluded = 0
        var unsupportedTypeExcluded = 0
        val eligible = mutableListOf<StoredQuestion>()

        for (question in questions) {
            when (classify(question)) {
                EligibilityStatus.ELIGIBLE -> eligible += question
                EligibilityStatus.NEEDS_REVIEW -> reviewExcluded++
                EligibilityStatus.STRUCTURE_INVALID -> structureExcluded++
                EligibilityStatus.UNSUPPORTED_TYPE -> unsupportedTypeExcluded++
            }
        }
        return PracticeEligibilityReport(
            eligible = eligible,
            reviewExcluded = reviewExcluded,
            structureExcluded = structureExcluded,
            unsupportedTypeExcluded = unsupportedTypeExcluded
        )
    }

    fun isStructurallyPracticable(question: StoredQuestion): Boolean {
        if (!PracticeJudge.isSupportedType(question.type)) return false
        if (QuestionValidator.validate(question).isNotEmpty()) return false
        if (question.type == QuestionTypes.TRUE_FALSE) {
            val snapshot = question.toPracticeSnapshot()
            if (!PracticeJudge.isValidTrueFalseReference(snapshot)) return false
        }
        return true
    }
}

fun StoredQuestion.toPracticeSnapshot(): PracticeQuestionSnapshot = PracticeQuestionSnapshot(
    questionId = id,
    libraryId = libraryId,
    position = position,
    type = type,
    originalNumber = originalNumber,
    stem = stem,
    options = options,
    answers = answers,
    acceptableAnswers = normalizedAcceptableAnswers(),
    referenceAnswer = referenceAnswer,
    editedAt = editedAt
)
