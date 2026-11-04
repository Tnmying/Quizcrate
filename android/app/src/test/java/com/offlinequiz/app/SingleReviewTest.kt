package com.offlinequiz.app

import com.offlinequiz.app.data.*
import com.offlinequiz.app.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SingleReviewTest {
    private class Fixture {
        val library = TestLibraryStore().apply { addLibrary(testLibrary("lib")) }
        val store = TestPracticeStore()
        val question = practiceQuestion("q")
        private var id = 0
        val repo = PracticeRepository(library, store, clock = { 1000L }, idFactory = { "id-${id++}" })
        val stats = PracticeStatsRepository(library, store)
        init { library.addQuestion(question) }
        suspend fun seedWrong() {
            val s = repo.start("lib", PracticeModes.SEQUENTIAL) as StartPracticeOutcome.Started
            repo.submitAutomatic(s.sessionId, 0, PracticeUserAnswer(selectedKeys = listOf("B")))
            repo.advance(s.sessionId,0)
        }
    }

    @Test fun chosenQuestionUsesNormalAnsweringAndCorrectClearsDueWithoutAddingFullRound() = runTest {
        val f = Fixture(); f.seedWrong()
        val started = f.repo.startSingleReview("lib","q") as StartPracticeOutcome.Started
        val state = f.repo.loadState(started.sessionId)!!
        assertEquals(1,state.session.total)
        assertEquals(PracticeSources.SINGLE_REVIEW,state.session.source)
        assertEquals(PracticePhases.ANSWERING,state.session.phase)
        assertFalse(state.session.draft.referenceRevealed)
        f.repo.submitAutomatic(started.sessionId,0,PracticeUserAnswer(selectedKeys=listOf("A")))
        assertTrue(f.stats.libraryReport("lib").due.isEmpty()) // Passing is immediate, before completion button.
        f.repo.advance(started.sessionId,0)
        val report=f.stats.libraryReport("lib")
        assertEquals(1,report.wrongbook.size)
        assertEquals(PracticeTotals(1,1),report.overview.totals)
        assertEquals(PracticeRoundCounts(1,0),report.overview.rounds)
    }

    @Test fun isolatedReviewDoesNotReplaceOrdinaryRoundOrItsDraft() = runTest {
        val f=Fixture();f.seedWrong()
        val ordinary=f.repo.start("lib",PracticeModes.RANDOM) as StartPracticeOutcome.Started
        val before=f.store.sessions.getValue(ordinary.sessionId).copy(draft=PracticeDraft(selectedKeys=listOf("B")))
        f.store.sessions[ordinary.sessionId]=before
        val single=f.repo.startSingleReview("lib","q") as StartPracticeOutcome.Started
        assertEquals(before,f.store.sessions[ordinary.sessionId])
        assertEquals(ordinary.sessionId,f.repo.activeSession("lib")!!.id)
        f.repo.submitAutomatic(single.sessionId,0,PracticeUserAnswer(selectedKeys=listOf("A")))
        f.repo.advance(single.sessionId,0)
        assertEquals(before,f.store.sessions[ordinary.sessionId])
        assertEquals(ordinary.sessionId,f.repo.activeSession("lib")!!.id)
    }

    @Test fun wrongPeekAndSkipRemainDue() = runTest {
        for (action in listOf("wrong","peek","skip")) {
            val f=Fixture();f.seedWrong()
            val single=f.repo.startSingleReview("lib","q") as StartPracticeOutcome.Started
            when(action) {
                "wrong" -> f.repo.submitAutomatic(single.sessionId,0,PracticeUserAnswer(selectedKeys=listOf("B")))
                "peek" -> f.repo.peek(single.sessionId,0,PracticeUserAnswer())
                else -> f.repo.skip(single.sessionId,0,PracticeUserAnswer())
            }
            f.repo.advance(single.sessionId,0)
            assertEquals(1,f.stats.libraryReport("lib").due.size)
            assertEquals(1,f.stats.libraryReport("lib").overview.rounds.normalCompleted)
        }
    }

    @Test fun nonWrongMissingForeignAndIneligibleQuestionsCannotStart() = runTest {
        val f=Fixture()
        assertTrue(f.repo.startSingleReview("lib","q") is StartPracticeOutcome.Failed)
        f.seedWrong()
        assertTrue(f.repo.startSingleReview("other","q") is StartPracticeOutcome.Failed)
        assertTrue(f.repo.startSingleReview("lib","missing") is StartPracticeOutcome.Failed)
        f.library.questionMap["q"]=f.question.copy(answers=emptyList())
        assertTrue(f.repo.startSingleReview("lib","q") is StartPracticeOutcome.NoEligible)
    }

    @Test fun nextCardReviewSupersedesOnlyPreviousSingleReview() = runTest {
        val f=Fixture();f.seedWrong()
        val ordinary=f.repo.start("lib",PracticeModes.SEQUENTIAL) as StartPracticeOutcome.Started
        val first=f.repo.startSingleReview("lib","q") as StartPracticeOutcome.Started
        val next=f.repo.startSingleReview("lib","q") as StartPracticeOutcome.Started
        assertEquals(PracticeStatuses.SUPERSEDED,f.store.sessions[first.sessionId]!!.status)
        assertEquals(PracticeStatuses.ACTIVE,f.store.sessions[next.sessionId]!!.status)
        assertEquals(PracticeStatuses.ACTIVE,f.store.sessions[ordinary.sessionId]!!.status)
    }
}
