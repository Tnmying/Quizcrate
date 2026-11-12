package com.offlinequiz.app.data

import com.offlinequiz.app.model.BlankSegment
import com.offlinequiz.app.model.BlankSpan
import com.offlinequiz.app.model.LearningBlankRef
import com.offlinequiz.app.model.LibraryRecord
import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.model.QuizOption
import com.offlinequiz.app.model.SourceLineRef
import com.offlinequiz.app.model.StoredQuestion
import com.offlinequiz.app.model.withFillAnswerVariants
import pdfprobe.Model

object QuestionMapper {

    fun fromParseResult(
        result: Model.ParseResult,
        libraryId: String,
        idFactory: () -> String
    ): List<StoredQuestion> {
        return result.questions.mapIndexed { index, parsed ->
            val blanks = parsed.blanks.map(::blankFromModel)
            val options = parsed.options.map(::optionFromModel)
            val answers = parsed.answers.toList()
            val acceptable: List<List<String>> = if (parsed.type == QuestionTypes.FILL_BLANK) {
                List(answers.size) { emptyList<String>() }
            } else {
                emptyList()
            }
            StoredQuestion(
                id = idFactory(),
                libraryId = libraryId,
                position = index,
                type = parsed.type.orEmpty().ifEmpty { QuestionTypes.UNKNOWN },
                originalNumber = parsed.originalNumber,
                numberValue = parsed.numberValue?.toInt(),
                sectionTitle = parsed.sectionTitle,
                stem = parsed.stem.orEmpty(),
                stemWithAnswer = parsed.stemWithAnswer,
                originalSentence = parsed.originalSentence,
                referenceAnswer = parsed.referenceAnswer,
                optionRawText = parsed.optionRawText,
                options = options,
                answers = answers,
                acceptableAnswers = acceptable,
                sourcePages = parsed.sourcePages.toList(),
                sourceLines = parsed.sourceLines.map { SourceLineRef(page = it.page, line = it.line) },
                blanks = blanks,
                learningBlankCount = if (parsed.type == QuestionTypes.FILL_BLANK) {
                    answers.size
                } else {
                    parsed.learningBlankCount
                },
                originalReviewReasons = parsed.reviewReasons.toList(),
                originalNeedsReview = parsed.needsReview,
                originalStem = parsed.stem.orEmpty(),
                originalOptions = options,
                originalAnswers = answers,
                originalBlanks = blanks,
                confirmed = false,
                confirmedAt = null,
                editedAt = null
            ).withFillAnswerVariants()
        }
    }

    private fun optionFromModel(option: Model.Option): QuizOption = QuizOption(
        key = option.key.orEmpty(),
        text = option.text.orEmpty(),
        markerIndex = option.markerIndex,
        duplicate = option.duplicate,
        supported = option.supported
    )

    private fun blankFromModel(blank: Model.BlankSpan): BlankSpan = BlankSpan(
        answer = blank.answer.orEmpty(),
        originalAnswer = blank.originalAnswer.orEmpty(),
        crossLine = blank.crossLine,
        splitByEnumeration = blank.splitByEnumeration,
        sentenceStart = blank.sentenceStart,
        sentenceEnd = blank.sentenceEnd,
        segments = blank.segments.map { segment ->
            BlankSegment(
                page = segment.page,
                line = segment.line,
                x0 = segment.x0,
                x1 = segment.x1,
                y = segment.y,
                text = segment.text.orEmpty()
            )
        },
        learningBlanks = blank.learningBlanks.map { learning ->
            LearningBlankRef(
                answer = learning.answer.orEmpty(),
                sentenceStart = learning.sentenceStart,
                sentenceEnd = learning.sentenceEnd
            )
        }
    )
}

fun LibraryEntity.toRecord(): LibraryRecord = LibraryRecord(
    id = id,
    name = name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    sourceFileName = sourceFileName,
    sourcePageCount = sourcePageCount,
    pdfRelativePath = pdfRelativePath,
    schemaVersion = schemaVersion,
    sourceText = sourceText
)

fun LibraryRecord.toEntity(): LibraryEntity = LibraryEntity(
    id = id,
    name = name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    sourceFileName = sourceFileName,
    sourcePageCount = sourcePageCount,
    pdfRelativePath = pdfRelativePath,
    schemaVersion = schemaVersion,
    sourceText = sourceText
)

fun QuestionEntity.toModel(): StoredQuestion = StoredQuestion(
    id = id,
    libraryId = libraryId,
    position = position,
    type = type,
    originalNumber = originalNumber,
    numberValue = numberValue,
    sectionTitle = sectionTitle,
    stem = stem,
    stemWithAnswer = stemWithAnswer,
    originalSentence = originalSentence,
    referenceAnswer = referenceAnswer,
    optionRawText = optionRawText,
    options = QuizJson.decodeOptions(optionsJson),
    answers = QuizJson.decodeStrings(answersJson),
    acceptableAnswers = QuizJson.decodeStringLists(acceptableAnswersJson),
    sourcePages = QuizJson.decodeInts(sourcePagesJson),
    sourceLines = QuizJson.decodeSourceLines(sourceLinesJson),
    blanks = QuizJson.decodeBlanks(blanksJson),
    learningBlankCount = learningBlankCount,
    originalReviewReasons = QuizJson.decodeStrings(originalReviewReasonsJson),
    originalNeedsReview = originalNeedsReview,
    originalStem = originalStem,
    originalOptions = QuizJson.decodeOptions(originalOptionsJson),
    originalAnswers = QuizJson.decodeStrings(originalAnswersJson),
    originalBlanks = QuizJson.decodeBlanks(originalBlanksJson),
    confirmed = confirmed,
    confirmedAt = confirmedAt,
    editedAt = editedAt
).withFillAnswerVariants()

fun StoredQuestion.toEntity(): QuestionEntity = QuestionEntity(
    id = id,
    libraryId = libraryId,
    position = position,
    type = type,
    originalNumber = originalNumber,
    numberValue = numberValue,
    sectionTitle = sectionTitle,
    stem = stem,
    stemWithAnswer = stemWithAnswer,
    originalSentence = originalSentence,
    referenceAnswer = referenceAnswer,
    optionRawText = optionRawText,
    optionsJson = QuizJson.encodeOptions(options),
    answersJson = QuizJson.encodeStrings(answers),
    acceptableAnswersJson = QuizJson.encodeStringLists(acceptableAnswers),
    sourcePagesJson = QuizJson.encodeInts(sourcePages),
    sourceLinesJson = QuizJson.encodeSourceLines(sourceLines),
    blanksJson = QuizJson.encodeBlanks(blanks),
    learningBlankCount = learningBlankCount,
    originalReviewReasonsJson = QuizJson.encodeStrings(originalReviewReasons),
    originalNeedsReview = originalNeedsReview,
    originalStem = originalStem,
    originalOptionsJson = QuizJson.encodeOptions(originalOptions),
    originalAnswersJson = QuizJson.encodeStrings(originalAnswers),
    originalBlanksJson = QuizJson.encodeBlanks(originalBlanks),
    confirmed = confirmed,
    confirmedAt = confirmedAt,
    editedAt = editedAt
)
