package com.offlinequiz.app

import com.offlinequiz.app.data.QuizJson
import com.offlinequiz.app.model.BlankSegment
import com.offlinequiz.app.model.BlankSpan
import com.offlinequiz.app.model.LearningBlankRef
import com.offlinequiz.app.model.QuizOption
import com.offlinequiz.app.model.SourceLineRef
import org.junit.Assert.assertEquals
import org.junit.Test

class QuizJsonTest {

    @Test
    fun optionsRoundTripKeepsEvidenceAndSpecialCharacters() {
        val options = listOf(
            QuizOption("A", "甲\"乙\"\n丙", markerIndex = 0, duplicate = false, supported = true),
            QuizOption("B", "带\\反斜杠", markerIndex = 1, duplicate = true, supported = false)
        )
        val decoded = QuizJson.decodeOptions(QuizJson.encodeOptions(options))
        assertEquals(options, decoded)
    }

    @Test
    fun stringListsRoundTripKeepsOrder() {
        val values = listOf(
            listOf("甲", "A"),
            emptyList(),
            listOf("乙、丙", "  ")
        )
        assertEquals(values, QuizJson.decodeStringLists(QuizJson.encodeStringLists(values)))
        assertEquals(listOf("A", "B", "C"), QuizJson.decodeStrings(QuizJson.encodeStrings(listOf("A", "B", "C"))))
        assertEquals(listOf(1, 2, 3), QuizJson.decodeInts(QuizJson.encodeInts(listOf(1, 2, 3))))
    }

    @Test
    fun blankSpanRoundTripKeepsSegmentsAndLearningBlanks() {
        val span = BlankSpan(
            answer = "甲、乙",
            originalAnswer = "甲、乙",
            crossLine = true,
            splitByEnumeration = true,
            sentenceStart = 3,
            sentenceEnd = 8,
            segments = listOf(
                BlankSegment(page = 2, line = 7, x0 = 84.5f, x1 = 140.25f, y = 700.5f, text = "甲、"),
                BlankSegment(page = 2, line = 8, x0 = 84.0f, x1 = 110.0f, y = 680.0f, text = "乙")
            ),
            learningBlanks = listOf(
                LearningBlankRef("甲", 3, 4),
                LearningBlankRef("乙", 6, 8)
            )
        )
        val decoded = QuizJson.decodeBlanks(QuizJson.encodeBlanks(listOf(span))).single()
        assertEquals(span, decoded)
    }

    @Test
    fun sourceLinesRoundTrip() {
        val lines = listOf(SourceLineRef(1, 2), SourceLineRef(3, 4))
        assertEquals(lines, QuizJson.decodeSourceLines(QuizJson.encodeSourceLines(lines)))
    }

    @Test
    fun malformedJsonFallsBackToEmptyInsteadOfCrashing() {
        assertEquals(emptyList<QuizOption>(), QuizJson.decodeOptions("{not-json"))
        assertEquals(emptyList<String>(), QuizJson.decodeStrings("[1,2"))
        assertEquals(emptyList<BlankSpan>(), QuizJson.decodeBlanks("null"))
    }
}
