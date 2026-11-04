package com.offlinequiz.app

import com.offlinequiz.app.data.*
import com.offlinequiz.app.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PracticeMergedStatsTest {
    @Test fun mixedAutomaticAndSelfAssessmentUsesCombinedDenominator() {
        val q = practiceQuestion("q")
        val history = (0..3).map { automaticCorrect(q, it.toLong(), "c$it") } +
            (0..1).map { automaticIncorrect(q, it.toLong(), "w$it") } +
            selfRemembered(q, 10, "r") + (0..2).map { selfForgotten(q, it.toLong(), "f$it") } +
            (0..5).map { skipped(q, it.toLong(), "s$it") }
        val report = PracticeStatsCalculator.report(listOf(q), history)
        assertEquals(PracticeTotals(5, 5), report.overview.totals)
        assertEquals(10, report.overview.totals.effective)
        assertEquals("50%", report.overview.totals.accuracyLabel)
        assertEquals(6, report.overview.skipped)
        assertEquals(PracticeTotals(5, 5), report.questions.single().totals)
        val result = PracticeResultSummary.fromAnswers(16, history.map { it.answer })
        assertEquals(report.overview.totals, result.totals)
        assertEquals(PracticeAnswerKinds.SELF_ASSESSMENT, history[6].answer.kind)
    }

    @Test fun zeroEffectiveAnswersShowNoAccuracyIncludingAllSkipped() {
        assertEquals("暂无", PracticeTotals(0, 0).accuracyLabel)
        val q = practiceQuestion("q")
        val report = PracticeStatsCalculator.report(listOf(q), listOf(skipped(q, 1)))
        assertEquals(0, report.overview.totals.effective)
        assertNull(report.overview.totals.accuracyPercent)
        assertEquals(1, report.overview.skipped)
        assertEquals(PracticeTotals(0, 0), PracticeResultSummary.fromAnswers(0, emptyList()).totals)
    }

    @Test fun hundredQuestionRoundWithTwentySkipsAddsOneRoundAndEightyAnswers() = runTest {
        val libraries = TestLibraryStore()
        libraries.addLibrary(testLibrary(id = "lib"))
        val questions = (0..99).map { practiceQuestion("q$it", position = it) }
        questions.forEach(libraries::addQuestion)
        val store = TestPracticeStore()
        val practice = PracticeRepository(libraries, store)
        val stats = PracticeStatsRepository(libraries, store)
        val started = practice.start("lib", PracticeModes.SEQUENTIAL) as StartPracticeOutcome.Started
        for (i in questions.indices) {
            val answer = PracticeUserAnswer(selectedKeys = listOf(if (i < 40) "A" else "B"))
            if (i < 80) practice.submitAutomatic(started.sessionId, i, answer)
            else practice.skip(started.sessionId, i, PracticeUserAnswer())
            if (i == 99) assertEquals(0, stats.libraryReport("lib").overview.rounds.normalCompleted)
            assertTrue(practice.advance(started.sessionId, i) is AdvancePracticeOutcome.Advanced)
        }
        val report = stats.libraryReport("lib").overview
        assertEquals(PracticeRoundCounts(1, 0), report.rounds)
        assertEquals(PracticeTotals(40, 40), report.totals)
        assertEquals(80, report.practiceCount)
        assertEquals(20, report.skipped)
        assertEquals(report, stats.libraryReport("lib").overview)
        practice.start("lib", PracticeModes.RANDOM)
        assertEquals(1, stats.libraryReport("lib").overview.rounds.normalCompleted)
    }

    @Test fun allSkippedRoundStillCountsAndWrongbookCompletionIsSeparate() = runTest {
        val libraries = TestLibraryStore()
        libraries.addLibrary(testLibrary(id = "lib"))
        val q = practiceQuestion("q")
        libraries.addQuestion(q)
        val store = TestPracticeStore()
        val practice = PracticeRepository(libraries, store)
        val stats = PracticeStatsRepository(libraries, store)
        val normal = practice.start("lib", PracticeModes.SEQUENTIAL) as StartPracticeOutcome.Started
        practice.skip(normal.sessionId, 0, PracticeUserAnswer())
        assertEquals(0, stats.libraryReport("lib").overview.rounds.normalCompleted)
        practice.advance(normal.sessionId, 0)
        assertEquals(PracticeRoundCounts(1, 0), stats.libraryReport("lib").overview.rounds)
        assertEquals("暂无", stats.libraryReport("lib").overview.totals.accuracyLabel)
        val wrongbook = practice.startWrongbook("lib", PracticeModes.SEQUENTIAL, listOf("q")) as StartPracticeOutcome.Started
        practice.submitAutomatic(wrongbook.sessionId, 0, PracticeUserAnswer(selectedKeys = listOf("A")))
        practice.advance(wrongbook.sessionId, 0)
        val overview = stats.libraryReport("lib").overview
        assertEquals(PracticeRoundCounts(1, 1), overview.rounds)
        assertEquals(1, overview.totals.effective)
        assertEquals("100%", overview.totals.accuracyLabel)
    }

    @Test fun onlyFinishedSessionsCountAndOtherLibrariesRemainIsolated() = runTest {
        val libraries = TestLibraryStore()
        val q = practiceQuestion("q")
        libraries.addLibrary(testLibrary(id = "lib"))
        libraries.addQuestion(q)
        val store = TestPracticeStore()
        val complete = practiceSession("done", listOf(q), status = PracticeStatuses.COMPLETED,
            phase = PracticePhases.COMPLETED, currentIndex = 1)
        store.addSession(complete)
        store.addSession(complete.copy(id = "other", libraryId = "other"))
        store.addSession(complete.copy(id = "active", status = PracticeStatuses.ACTIVE, completedAt = null))
        store.addSession(complete.copy(id = "old", status = PracticeStatuses.SUPERSEDED))
        store.addSession(complete.copy(id = "no-time", completedAt = null))
        store.addSession(complete.copy(id = "feedback", phase = PracticePhases.FEEDBACK))
        val overview = PracticeStatsRepository(libraries, store).libraryReport("lib").overview
        assertEquals(PracticeRoundCounts(1, 0), overview.rounds)
        assertEquals(0, overview.totals.effective)
    }

    @Test fun repeatedAnswersAndEditsKeepHistoricalTotalsAndCompletedRounds() = runTest {
        val libraries = TestLibraryStore()
        libraries.addLibrary(testLibrary(id = "lib"))
        val q = practiceQuestion("q")
        libraries.addQuestion(q)
        val store = TestPracticeStore()
        val practice = PracticeRepository(libraries, store)
        val stats = PracticeStatsRepository(libraries, store)
        repeat(2) {
            val round = practice.start("lib", PracticeModes.SEQUENTIAL) as StartPracticeOutcome.Started
            practice.submitAutomatic(round.sessionId, 0, PracticeUserAnswer(selectedKeys = listOf("A")))
            practice.advance(round.sessionId, 0)
        }
        val before = stats.libraryReport("lib").overview
        assertEquals(2, before.totals.effective)
        assertEquals(2, before.rounds.normalCompleted)
        libraries.addQuestion(q.copy(answers = listOf("B"), editedAt = 9000))
        val after = stats.libraryReport("lib").overview
        assertEquals(before.totals, after.totals)
        assertEquals(before.rounds, after.rounds)
        assertEquals(2, store.answerMap.size)
    }
}
