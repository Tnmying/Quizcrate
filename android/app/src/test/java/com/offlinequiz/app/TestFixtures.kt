package com.offlinequiz.app

import com.offlinequiz.app.model.BlankSpan
import com.offlinequiz.app.model.LibraryRecord
import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.model.QuizOption
import com.offlinequiz.app.model.StoredQuestion

fun testQuestion(
    id: String = "q-1",
    libraryId: String = "lib-1",
    position: Int = 0,
    type: String = QuestionTypes.FILL_BLANK,
    stem: String = "请填写____。",
    options: List<QuizOption> = emptyList(),
    answers: List<String> = listOf("甲"),
    acceptableAnswers: List<List<String>> = emptyList(),
    referenceAnswer: String? = null,
    blanks: List<BlankSpan> = emptyList(),
    originalReviewReasons: List<String> = emptyList(),
    confirmed: Boolean = false
): StoredQuestion {
    val normalizedAcceptable = if (type == QuestionTypes.FILL_BLANK) {
        answers.indices.map { index -> acceptableAnswers.getOrNull(index).orEmpty() }
    } else {
        emptyList()
    }
    return StoredQuestion(
        id = id,
        libraryId = libraryId,
        position = position,
        type = type,
        originalNumber = "1",
        numberValue = 1,
        sectionTitle = "一、测试题",
        stem = stem,
        stemWithAnswer = stem,
        originalSentence = stem,
        referenceAnswer = referenceAnswer,
        optionRawText = options.joinToString(" ") { "${it.key}.${it.text}" },
        options = options,
        answers = answers,
        acceptableAnswers = normalizedAcceptable,
        sourcePages = listOf(1),
        sourceLines = emptyList(),
        blanks = blanks,
        learningBlankCount = if (type == QuestionTypes.FILL_BLANK) answers.size else 0,
        originalReviewReasons = originalReviewReasons,
        originalNeedsReview = originalReviewReasons.isNotEmpty(),
        originalStem = stem,
        originalOptions = options,
        originalAnswers = answers,
        originalBlanks = blanks,
        confirmed = confirmed,
        confirmedAt = if (confirmed) 1L else null,
        editedAt = null
    )
}

fun testLibrary(
    id: String = "lib-1",
    name: String = "测试题库",
    createdAt: Long = 1_000L,
    updatedAt: Long = 1_000L
): LibraryRecord = LibraryRecord(
    id = id,
    name = name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    sourceFileName = "demo.pdf",
    sourcePageCount = 3,
    pdfRelativePath = "libraries/$id/source.pdf",
    schemaVersion = 1
)
