package com.offlinequiz.app

import com.offlinequiz.app.data.QuestionMapper
import com.offlinequiz.app.model.QuestionTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pdfprobe.Model

class QuestionMapperTest {

    @Test
    fun mapperPreservesTypeAnswersPagesOptionsAndBlankEvidence() {
        val parse = Model.ParseResult()
        val question = Model.Question()
        question.type = QuestionTypes.FILL_BLANK
        question.originalNumber = "6"
        question.numberValue = 6
        question.sectionTitle = "四、填空题"
        question.stem = "有理想、敢担当、____"
        question.stemWithAnswer = "有理想、敢担当、能吃苦"
        question.answers.add("能吃苦")
        question.sourcePages.add(20)
        question.sourcePages.add(21)
        val line = Model.SourceLine().apply {
            page = 20
            line = 8
        }
        question.sourceLines.add(line)

        val option = Model.Option().apply {
            key = "A"
            text = "选项"
            markerIndex = 0
            duplicate = false
            supported = true
        }
        question.options.add(option)

        val blank = Model.BlankSpan().apply {
            answer = "能吃苦"
            originalAnswer = "能吃苦"
            crossLine = true
            splitByEnumeration = false
            sentenceStart = 6
            sentenceEnd = 9
        }
        blank.segments.add(Model.Segment().apply {
            page = 20
            this.line = 8
            x0 = 100f
            x1 = 140f
            y = 500f
            text = "能吃"
        })
        blank.learningBlanks.add(Model.LearningBlank().apply {
            answer = "能吃苦"
            sentenceStart = 6
            sentenceEnd = 9
        })
        question.blanks.add(blank)

        parse.questions.add(question)
        val stored = QuestionMapper.fromParseResult(parse, "library-1") { "question-1" }.single()

        assertEquals("question-1", stored.id)
        assertEquals("library-1", stored.libraryId)
        assertEquals(QuestionTypes.FILL_BLANK, stored.type)
        assertEquals(listOf("能吃苦"), stored.answers)
        assertEquals(listOf(20, 21), stored.sourcePages)
        assertEquals(listOf(com.offlinequiz.app.model.SourceLineRef(20, 8)), stored.sourceLines)
        assertEquals("A", stored.options.single().key)
        assertTrue(stored.blanks.single().crossLine)
        assertEquals(1, stored.blanks.single().segments.size)
        assertEquals("能吃苦", stored.blanks.single().learningBlanks.single().answer)
        assertEquals("有理想、敢担当、____", stored.originalStem)
        assertEquals(listOf("能吃苦"), stored.originalAnswers)
        assertEquals(false, stored.confirmed)
        assertEquals(1, stored.learningBlankCount)
    }

    @Test
    fun twoLibrariesReceiveDifferentQuestionIdsEvenWithSameNumbers() {
        val parse = Model.ParseResult()
        repeat(2) {
            val question = Model.Question()
            question.type = QuestionTypes.SINGLE_CHOICE
            question.originalNumber = "7"
            question.stem = "题干（A）"
            question.answers.add("A")
            question.options.add(Model.Option().apply {
                key = "A"
                text = "甲"
                markerIndex = 0
            })
            question.options.add(Model.Option().apply {
                key = "B"
                text = "乙"
                markerIndex = 1
            })
            parse.questions.add(question)
        }

        var counter = 0
        val first = QuestionMapper.fromParseResult(parse, "lib-a") {
            counter += 1
            "q-$counter"
        }
        val second = QuestionMapper.fromParseResult(parse, "lib-b") {
            counter += 1
            "q-$counter"
        }

        assertEquals(listOf("q-1", "q-2"), first.map { it.id })
        assertEquals(listOf("q-3", "q-4"), second.map { it.id })
        assertTrue(first.all { it.libraryId == "lib-a" })
        assertTrue(second.all { it.libraryId == "lib-b" })
    }
}
