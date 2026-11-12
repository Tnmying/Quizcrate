package com.offlinequiz.app.model

/** A backslash separates accepted forms of one blank, never separate blanks. */
object FillAnswerVariants {
    private val separators = Regex("\\\\+")
    private val drivePath = Regex("^[A-Za-z]:\\\\.*")

    fun isDelimited(raw: String): Boolean {
        val value = raw.trim()
        return value.contains('\\') && !value.startsWith('\\') && !value.endsWith('\\') &&
            !drivePath.matches(value) && separators.split(value).all { it.isNotBlank() }
    }

    fun forms(raw: String): List<String> = if (isDelimited(raw)) {
        separators.split(raw.trim()).map { it.trim() }.distinct()
    } else listOf(raw.trim())

    fun candidates(reference: String, accepted: List<String>): List<String> =
        (forms(reference) + accepted.flatMap(::forms)).filter { it.isNotEmpty() }.distinct()

    fun hasOnlyObsoleteVariantWarning(question: StoredQuestion): Boolean =
        question.isFillBlank && question.originalReviewReasons.isNotEmpty() &&
            question.originalReviewReasons.all { it == "answer_variants_need_review" } &&
            question.originalAnswers.any(::isDelimited) &&
            question.originalAnswers.filter { it.contains('\\') }.all(::isDelimited)
}

/** Upgrade the editable view of old imports without rewriting their original evidence or history. */
fun StoredQuestion.withFillAnswerVariants(): StoredQuestion {
    if (!isFillBlank) return this
    val groups = answers.indices.map { index ->
        FillAnswerVariants.candidates(answers[index], acceptableAnswers.getOrNull(index).orEmpty())
    }
    val primary = answers.map { FillAnswerVariants.forms(it).first() }
    val alternatives = groups.mapIndexed { index, values -> values.filter { it != primary[index] } }
    if (primary == answers && alternatives == normalizedAcceptableAnswers()) return this
    return copy(answers = primary, acceptableAnswers = alternatives)
}
