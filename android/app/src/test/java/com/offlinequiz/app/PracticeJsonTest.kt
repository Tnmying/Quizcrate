package com.offlinequiz.app

import com.offlinequiz.app.data.PracticeJson
import com.offlinequiz.app.model.PracticeDraft
import com.offlinequiz.app.model.PracticeQuestionSnapshot
import com.offlinequiz.app.model.PracticeUserAnswer
import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.model.QuizOption
import org.junit.Assert.assertEquals
import org.junit.Test

class PracticeJsonTest {

    @Test
    fun questionSnapshotRoundTripKeepsPracticeDataButOnlySerializedFields() {
        val question = PracticeQuestionSnapshot(
            questionId = "q-1",
            libraryId = "lib-1",
            position = 3,
            type = QuestionTypes.FILL_BLANK,
            originalNumber = "6",
            stem = "有理想、____",
            options = listOf(QuizOption("A", "选项")),
            answers = listOf("甲", "乙"),
            acceptableAnswers = listOf(listOf("甲-A"), emptyList()),
            referenceAnswer = "简答参考",
            editedAt = 1234L
        )
        val decoded = PracticeJson.decodeQuestions(
            PracticeJson.encodeQuestions(listOf(question))
        ).single()
        assertEquals(question, decoded)
    }

    @Test
    fun draftAndUserAnswerRoundTripKeepSessionInput() {
        val draft = PracticeDraft(
            selectedKeys = listOf("A", "B"),
            blankAnswers = listOf("甲", "", "乙"),
            text = "文字作答",
            referenceRevealed = true
        )
        assertEquals(draft, PracticeJson.decodeDraft(PracticeJson.encodeDraft(draft)))

        val answer = PracticeUserAnswer(
            selectedKeys = listOf("A"),
            blankAnswers = listOf("甲"),
            text = "草稿"
        )
        assertEquals(answer, PracticeJson.decodeUserAnswer(PracticeJson.encodeUserAnswer(answer)))
    }

    @Test
    fun malformedJsonFallsBackToEmptyValues() {
        assertEquals(emptyList<PracticeQuestionSnapshot>(), PracticeJson.decodeQuestions("{bad"))
        assertEquals(PracticeDraft(), PracticeJson.decodeDraft("bad"))
        assertEquals(PracticeUserAnswer(), PracticeJson.decodeUserAnswer("bad"))
    }
}
