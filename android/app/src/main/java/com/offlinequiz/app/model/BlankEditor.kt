package com.offlinequiz.app.model

/**
 * Explicit blank-count operations for the fill-blank editor.
 *
 * The stem's placeholder tokens are the structural truth; answers are kept in the same order.
 * Merging is conservative: only whitespace or a small punctuation whitelist may sit between the two
 * placeholders. Anything else would change the meaning of the sentence, so the operation is
 * rejected and the user is asked to edit the stem manually.
 */
object BlankEditor {

    private val placeholder = Regex("_{2,}")
    private val safeSeparators = setOf("、", "，", ",", "；", ";", "：", ":", "。", ".", "！", "!", "？", "?")

    data class Result(
        val stem: String,
        val answers: List<String>,
        val acceptableAnswers: List<List<String>>
    )

    data class MergeResult(
        val stem: String,
        val answers: List<String>,
        val acceptableAnswers: List<List<String>>,
        val notice: String? = null,
        val rejectedReason: String? = null
    ) {
        val rejected: Boolean get() = rejectedReason != null
    }

    fun placeholderCount(stem: String): Int = placeholder.findAll(stem).count()

    fun addBlank(stem: String, answers: List<String>, acceptableAnswers: List<List<String>>): Result {
        val last = stem.lastOrNull()
        val separator = when {
            stem.isEmpty() -> ""
            last == null -> ""
            last.isWhitespace() -> ""
            last in "：:，,。.；;、！!？?" -> ""
            else -> " "
        }
        return Result(
            stem = stem + separator + "____",
            answers = answers + "",
            acceptableAnswers = normalized(acceptableAnswers, answers.size) + listOf(emptyList())
        )
    }

    fun removeBlank(
        stem: String,
        answers: List<String>,
        acceptableAnswers: List<List<String>>,
        index: Int
    ): Result {
        if (index !in answers.indices) return Result(stem, answers, normalized(acceptableAnswers, answers.size))
        val match = placeholder.findAll(stem).elementAtOrNull(index)
            ?: return Result(stem, answers, normalized(acceptableAnswers, answers.size))
        val newStem = stem.removeRange(match.range)
        return Result(
            stem = newStem,
            answers = answers.filterIndexed { i, _ -> i != index },
            acceptableAnswers = normalized(acceptableAnswers, answers.size)
                .filterIndexed { i, _ -> i != index }
        )
    }

    /**
     * Merge blank [index] into the previous blank, replacing both placeholders and the text between
     * them with one placeholder. The punctuation/whitespace between the old blanks is used as the
     * connector between the two reference answers; it is never invented.
     *
     * The merged blank's extra acceptable answers are cleared: a half-answer cannot be accepted as
     * the whole merged answer. Other blanks keep their acceptable answers in order.
     */
    fun mergeBlank(
        stem: String,
        answers: List<String>,
        acceptableAnswers: List<List<String>>,
        index: Int
    ): MergeResult {
        val matches = placeholder.findAll(stem).toList()
        if (matches.size != answers.size) {
            return rejected(
                stem,
                answers,
                acceptableAnswers,
                "题干空位数量（${matches.size}）与答案组数（${answers.size}）不一致，请先校对后再合并"
            )
        }
        if (index <= 0 || index !in answers.indices || index >= matches.size) {
            return rejected(stem, answers, acceptableAnswers, "只能将后一个空并入前一个空")
        }

        val previousMatch = matches[index - 1]
        val currentMatch = matches[index]
        val between = stem.substring(previousMatch.range.last + 1, currentMatch.range.first)
        if (between.isEmpty()) {
            return rejected(stem, answers, acceptableAnswers, "两个空之间没有可保留的分隔内容，无法安全合并，请手动校对")
        }
        val connector = when {
            between.all { it.isWhitespace() } -> " "
            between.trim() in safeSeparators -> between.trim()
            else -> {
                val visible = between.trim().replace(Regex("\\s+"), " ").take(40)
                return rejected(
                    stem,
                    answers,
                    acceptableAnswers,
                    "两个空之间包含正文「$visible」，自动合并会改变题意，请手动编辑题干和答案"
                )
            }
        }

        val previousAnswer = answers[index - 1].trim()
        val currentAnswer = answers[index].trim()
        if (previousAnswer.isEmpty() || currentAnswer.isEmpty()) {
            return rejected(stem, answers, acceptableAnswers, "两个空的参考答案都必须非空，请先补全后再合并")
        }

        val mergedAnswer = previousAnswer + connector + currentAnswer
        val accepted = normalized(acceptableAnswers, answers.size)
        val hadAccepted = accepted[index - 1].isNotEmpty() || accepted[index].isNotEmpty()
        val newAnswers = answers.toMutableList().apply {
            this[index - 1] = mergedAnswer
            removeAt(index)
        }
        val newAccepted = accepted.toMutableList().apply {
            this[index - 1] = emptyList()
            removeAt(index)
        }
        val newStem = stem.replaceRange(previousMatch.range.first, currentMatch.range.last + 1, "____")
        val notice = if (hadAccepted) {
            "已合并相邻两空。合并空的额外可接受答案已清空，请重新设置。"
        } else {
            "已合并相邻两空。"
        }
        return MergeResult(
            stem = newStem,
            answers = newAnswers,
            acceptableAnswers = newAccepted,
            notice = notice
        )
    }

    fun normalized(acceptableAnswers: List<List<String>>, size: Int): List<List<String>> {
        return (0 until size).map { index ->
            acceptableAnswers.getOrNull(index)
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                .orEmpty()
        }
    }

    private fun rejected(
        stem: String,
        answers: List<String>,
        acceptableAnswers: List<List<String>>,
        reason: String
    ): MergeResult = MergeResult(
        stem = stem,
        answers = answers,
        acceptableAnswers = acceptableAnswers,
        rejectedReason = reason
    )
}
