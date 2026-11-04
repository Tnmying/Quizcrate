package com.offlinequiz.app.model

/** Review is optional for valid imports. Attention always follows actual practice eligibility. */
object QuestionReview {
    fun needsAttention(question: StoredQuestion): Boolean =
        PracticeEligibility.classify(question) != EligibilityStatus.ELIGIBLE

    fun label(question: StoredQuestion): String = when (PracticeEligibility.classify(question)) {
        EligibilityStatus.NEEDS_REVIEW -> "需处理 · 识别有疑问"
        EligibilityStatus.STRUCTURE_INVALID -> "需处理 · 题目或答案不完整"
        EligibilityStatus.UNSUPPORTED_TYPE -> "需处理 · 暂不支持的题型"
        EligibilityStatus.ELIGIBLE -> ""
    }

    fun summary(library: LibraryRecord, questions: List<StoredQuestion>): LibrarySummary = LibrarySummary(
        library = library,
        questionCount = questions.size,
        pendingCount = questions.count(::needsAttention),
        confirmedCount = questions.count { it.confirmed }
    )
}

enum class QuestionFilter(val label: String) {
    ALL("全部"), ATTENTION("需处理");

    fun includes(question: StoredQuestion): Boolean = when (this) {
        ALL -> true
        ATTENTION -> QuestionReview.needsAttention(question)
    }

    companion object {
        fun restore(value: String): QuestionFilter = entries.firstOrNull { it.name == value } ?: ALL
    }
}
