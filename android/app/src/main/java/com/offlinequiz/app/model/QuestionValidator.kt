package com.offlinequiz.app.model

/**
 * Structural validation for a question being saved from the editor.
 *
 * These are hard errors. Parser diagnostics from the original import are kept separately and
 * never silently discarded or auto-"fixed"; the user resolves them explicitly.
 */
object QuestionValidator {

    fun validate(question: StoredQuestion): List<String> {
        val errors = mutableListOf<String>()
        if (question.stem.isBlank()) errors += "题干不能为空"

        when (question.type) {
            QuestionTypes.SINGLE_CHOICE, QuestionTypes.MULTIPLE_CHOICE -> validateChoice(question, errors)
            QuestionTypes.TRUE_FALSE -> validateTrueFalse(question, errors)
            QuestionTypes.FILL_BLANK -> validateFillBlank(question, errors)
            QuestionTypes.SHORT_ANSWER -> {
                if (question.referenceAnswer.isNullOrBlank()) errors += "简答题参考答案不能为空"
            }
            else -> errors += "请确认题型后保存"
        }
        return errors.distinct()
    }

    private fun validateChoice(question: StoredQuestion, errors: MutableList<String>) {
        if (question.options.size < 2) errors += "选择题至少需要两个选项"
        val keys = question.options.map { it.key.trim().uppercase() }
        if (keys.any { it.isEmpty() }) errors += "选项标记不能为空"
        if (keys.any { key -> key.length != 1 || key[0] !in 'A'..'Z' }) {
            errors += "选项标记应为单个英文字母"
        }
        if (keys.size != keys.distinct().size) errors += "选项标记不能重复"
        if (question.options.any { it.text.isBlank() }) errors += "选项内容不能为空"

        val answers = question.answers.map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        if (answers.any { it !in keys }) errors += "正确项不在现有选项中"

        if (question.type == QuestionTypes.SINGLE_CHOICE) {
            if (answers.size != 1) errors += "单选题必须有且只有一个正确项"
        } else {
            if (answers.distinct().size < 2) errors += "多选题至少需要两个正确项"
            if (answers.size != answers.distinct().size) errors += "多选题正确项不能重复"
        }
    }

    private fun validateTrueFalse(question: StoredQuestion, errors: MutableList<String>) {
        val answers = question.answers.map { it.trim() }.filter { it.isNotEmpty() }
        if (answers.size != 1) errors += "判断题必须有且只有一个答案"
    }

    private fun validateFillBlank(question: StoredQuestion, errors: MutableList<String>) {
        val placeholders = BlankEditor.placeholderCount(question.stem)
        if (placeholders == 0) {
            errors += "填空题题干中没有空位（____）"
        }
        if (placeholders != question.answers.size) {
            errors += "题干有 $placeholders 个空位，但答案有 ${question.answers.size} 组"
        }
        if (question.answers.any { it.isBlank() }) errors += "每个空的答案都不能为空"
        if (question.acceptableAnswers.isNotEmpty() && question.acceptableAnswers.size != question.answers.size) {
            errors += "可接受答案组数与填空数量不一致"
        }
    }
}
