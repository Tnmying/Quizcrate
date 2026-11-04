package com.offlinequiz.app.model

/** Immutable application model. The parser's Java model is converted at the import boundary. */

data class LibraryRecord(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceFileName: String,
    val sourcePageCount: Int,
    val pdfRelativePath: String?,
    val schemaVersion: Int
)

data class LibrarySummary(
    val library: LibraryRecord,
    val questionCount: Int,
    val pendingCount: Int,
    val confirmedCount: Int
) {
    val unreviewedCount: Int get() = (questionCount - confirmedCount).coerceAtLeast(0)
}

data class QuizOption(
    val key: String,
    val text: String,
    val markerIndex: Int = 0,
    val duplicate: Boolean = false,
    val supported: Boolean = true
)

data class SourceLineRef(
    val page: Int,
    val line: Int
)

data class BlankSegment(
    val page: Int,
    val line: Int,
    val x0: Float,
    val x1: Float,
    val y: Float,
    val text: String
)

data class LearningBlankRef(
    val answer: String,
    val sentenceStart: Int,
    val sentenceEnd: Int
)

data class BlankSpan(
    val answer: String,
    val originalAnswer: String,
    val crossLine: Boolean,
    val splitByEnumeration: Boolean,
    val sentenceStart: Int,
    val sentenceEnd: Int,
    val segments: List<BlankSegment> = emptyList(),
    val learningBlanks: List<LearningBlankRef> = emptyList()
)

data class StoredQuestion(
    val id: String,
    val libraryId: String,
    val position: Int,
    val type: String,
    val originalNumber: String?,
    val numberValue: Int?,
    val sectionTitle: String?,
    val stem: String,
    val stemWithAnswer: String?,
    val originalSentence: String?,
    val referenceAnswer: String?,
    val optionRawText: String?,
    val options: List<QuizOption>,
    val answers: List<String>,
    val acceptableAnswers: List<List<String>>,
    val sourcePages: List<Int>,
    val sourceLines: List<SourceLineRef>,
    val blanks: List<BlankSpan>,
    val learningBlankCount: Int,
    val originalReviewReasons: List<String>,
    val originalNeedsReview: Boolean,
    val originalStem: String,
    val originalOptions: List<QuizOption>,
    val originalAnswers: List<String>,
    val originalBlanks: List<BlankSpan>,
    val confirmed: Boolean,
    val confirmedAt: Long?,
    val editedAt: Long?
) {
    val typeLabel: String get() = QuestionTypes.label(type)
    val isChoice: Boolean get() = type == QuestionTypes.SINGLE_CHOICE || type == QuestionTypes.MULTIPLE_CHOICE
    val isFillBlank: Boolean get() = type == QuestionTypes.FILL_BLANK
    val isTrueFalse: Boolean get() = type == QuestionTypes.TRUE_FALSE
    val isShortAnswer: Boolean get() = type == QuestionTypes.SHORT_ANSWER

    fun normalizedAcceptableAnswers(): List<List<String>> {
        if (!isFillBlank) return emptyList()
        return answers.indices.map { index ->
            acceptableAnswers.getOrNull(index)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        }
    }

    fun editableContent(): EditableContent = EditableContent(
        stem = stem,
        options = options.map { it.copy(key = it.key.trim(), text = it.text.trim()) },
        answers = answers.map { it.trim() },
        acceptableAnswers = normalizedAcceptableAnswers(),
        referenceAnswer = referenceAnswer?.trim().orEmpty()
    )
}

data class EditableContent(
    val stem: String,
    val options: List<QuizOption>,
    val answers: List<String>,
    val acceptableAnswers: List<List<String>>,
    val referenceAnswer: String
)

sealed interface SaveOutcome {
    data class Saved(val confirmed: Boolean, val changed: Boolean) : SaveOutcome
    data class Invalid(val errors: List<String>) : SaveOutcome
    data class Failed(val message: String) : SaveOutcome
    data object Unchanged : SaveOutcome
}

object QuestionTypes {
    const val SINGLE_CHOICE = "single_choice"
    const val MULTIPLE_CHOICE = "multiple_choice"
    const val TRUE_FALSE = "true_false"
    const val FILL_BLANK = "fill_blank"
    const val SHORT_ANSWER = "short_answer"
    const val UNKNOWN = "unknown"

    fun label(type: String): String = when (type) {
        SINGLE_CHOICE -> "单选"
        MULTIPLE_CHOICE -> "多选"
        TRUE_FALSE -> "判断"
        FILL_BLANK -> "填空"
        SHORT_ANSWER -> "简答"
        else -> "未知题型"
    }
}

object ReviewLabels {
    private val labels = mapOf(
        "no_option_markers" to "未找到选项标记",
        "empty_option" to "选项内容为空",
        "too_few_options" to "选项数量过少",
        "too_many_options" to "选项数量过多",
        "option_keys_not_sequential" to "选项标记顺序或编号异常",
        "duplicate_option_marker" to "选项标记重复",
        "option_markers_out_of_order" to "选项标记顺序异常",
        "option_marker_gap" to "选项标记缺号",
        "unsupported_option_marker" to "选项标记超出单字母支持范围",
        "no_answer_in_parentheses" to "未找到括号内的答案",
        "single_choice_answer_count" to "单选答案数量异常",
        "multiple_choice_answer_count" to "多选答案数量异常",
        "answer_not_in_options" to "答案不在选项中",
        "non_choice_answer_token" to "答案不是选择题字母",
        "no_true_false_symbol" to "未找到判断符号",
        "no_reference_answer_marker" to "未找到“参考答案”",
        "empty_reference_answer" to "参考答案为空",
        "no_underline_answers" to "未识别到填空下划线",
        "empty_underline_answer" to "下划线内没有文字",
        "no_learning_blanks" to "未生成学习空",
        "invalid_blank_range" to "填空范围无效",
        "blank_text_mismatch" to "下划线文字与原文不一致",
        "partial_underline_coverage" to "下划线覆盖边界不完整",
        "overlapping_blank_ranges" to "填空范围重叠",
        "overlapping_cross_line_text" to "跨行下划线文字范围重叠",
        "empty_enumeration_item" to "顿号拆分出现空项，已保留原文",
        "empty_question_start" to "题目起始行为空",
        "empty_stem" to "题干为空",
        "unknown_question_type" to "未知题型"
    )

    /**
     * Unknown parser diagnostics must not leak raw English codes into the product UI.
     * The original code is still stored in the database for troubleshooting.
     */
    fun label(code: String): String = labels[code] ?: "其他识别提示"
}
