package com.offlinequiz.app

import com.offlinequiz.app.data.*
import com.offlinequiz.app.model.*
import com.offlinequiz.app.ui.QuestionListPosition
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PracticePeekTest {
    @Test fun peekAllFiveTypesIsWrongAndCannotBeResubmittedAsCorrect() = runTest {
        val library = TestLibraryStore().apply { addLibrary(testLibrary("lib")) }
        val qs = listOf(
            practiceQuestion("single", position = 0),
            practiceQuestion("multi", position = 1, type = QuestionTypes.MULTIPLE_CHOICE,
                answers = listOf("A", "B")),
            practiceQuestion("tf", position = 2, type = QuestionTypes.TRUE_FALSE,
                options = emptyList(), answers = listOf("√")),
            practiceQuestion("fill", position = 3, type = QuestionTypes.FILL_BLANK,
                options = emptyList(), stem = "填____", answers = listOf("甲")),
            practiceQuestion("short", position = 4, type = QuestionTypes.SHORT_ANSWER,
                options = emptyList(), answers = emptyList(), referenceAnswer = "参考答案")
        )
        qs.forEach(library::addQuestion)
        val store = TestPracticeStore()
        val repo = PracticeRepository(library, store)
        val started = repo.start("lib", PracticeModes.SEQUENTIAL) as StartPracticeOutcome.Started
        assertEquals(5, started.total)
        for (i in qs.indices) {
            val peek = repo.peek(started.sessionId, i, PracticeUserAnswer()) as SubmitPracticeOutcome.Recorded
            assertEquals(PracticeAnswerKinds.PEEKED, peek.answer.kind)
            assertEquals(PracticeResults.FORGOTTEN, peek.answer.result)
            val again = repo.submitAutomatic(started.sessionId, i,
                PracticeUserAnswer(selectedKeys = listOf("A"))) as SubmitPracticeOutcome.Recorded
            assertTrue(again.alreadyRecorded)
            assertEquals(peek.answer, again.answer)
            assertTrue(repo.advance(started.sessionId, i) is AdvancePracticeOutcome.Advanced)
        }
        val report = PracticeStatsRepository(library, store).libraryReport("lib")
        assertEquals(PracticeTotals(0, 5), report.overview.totals)
        assertEquals(5, report.wrongbook.size)
        assertEquals(5, report.due.size)
        assertTrue(report.due.all { it.mastery.label == "待复习（偷看答案）" })
        assertEquals(PracticeTotals(0, 5), repo.loadState(started.sessionId)!!.summary.totals)
        assertEquals("偷看答案 · 错误", PracticeHistoryText.kindLabel(PracticeAnswerKinds.PEEKED, PracticeResults.FORGOTTEN))
    }

    @Test fun laterCorrectClearsDueButKeepsPeekHistoryAndSkipDoesNotClearIt() {
        val q = practiceQuestion("q")
        val peek = historyEntry(q, PracticeAnswerKinds.PEEKED, PracticeResults.FORGOTTEN, 1)
        val skip = skipped(q, 2)
        val due = PracticeStatsCalculator.report(listOf(q), listOf(peek, skip))
        assertEquals(PracticeTotals(0, 1), due.overview.totals)
        assertEquals(1, due.due.size)
        val passed = PracticeStatsCalculator.report(listOf(q), listOf(peek, skip, automaticCorrect(q, 3)))
        assertTrue(passed.due.isEmpty())
        assertEquals(1, passed.wrongbook.size)
        assertEquals(PracticeTotals(1, 1), passed.overview.totals)
    }

    @Test fun jumpRejectsInvalidNumbersAndDragIncludesFirstAndLast() {
        assertEquals(0, QuestionListPosition.dragIndex(-0.5f, 201))
        assertEquals(100, QuestionListPosition.dragIndex(0.5f, 201))
        assertEquals(200, QuestionListPosition.dragIndex(1.5f, 201))
        assertEquals(0, QuestionListPosition.dragIndex(1f, 1))
    }
}
