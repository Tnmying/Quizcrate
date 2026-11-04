package com.offlinequiz.app

import com.offlinequiz.app.data.PracticeStatsRepository
import com.offlinequiz.app.model.PracticeAnswerKinds
import com.offlinequiz.app.model.PracticeMastery
import com.offlinequiz.app.model.PracticePhases
import com.offlinequiz.app.model.PracticeResults
import com.offlinequiz.app.model.PracticeStatuses
import com.offlinequiz.app.model.toPracticeSnapshot
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Repository-level checks for history joins/isolation (the SQL itself is covered by androidTest). */
class PracticeStatsRepositoryTest {

    @Test
    fun reportCountsSupersededSessionsAndIsolatesLibraries() = runTest {
        val libraryStore = TestLibraryStore()
        libraryStore.addLibrary(testLibrary(id = "lib-a", name = "A"))
        libraryStore.addLibrary(testLibrary(id = "lib-b", name = "B"))
        val qa = practiceQuestion("qa", libraryId = "lib-a", position = 0)
        val qb = practiceQuestion("qb", libraryId = "lib-b", position = 0)
        libraryStore.addQuestion(qa)
        libraryStore.addQuestion(qb)

        val practiceStore = TestPracticeStore()
        practiceStore.addSession(
            practiceSession(
                "a-old", listOf(qa), libraryId = "lib-a",
                status = PracticeStatuses.SUPERSEDED, phase = PracticePhases.FEEDBACK
            )
        )
        practiceStore.addSession(practiceSession("a-new", listOf(qa), libraryId = "lib-a"))
        practiceStore.addSession(practiceSession("b-one", listOf(qb), libraryId = "lib-b"))
        practiceStore.addAnswer(
            practiceAnswer("a1", qa, PracticeAnswerKinds.AUTOMATIC, PracticeResults.INCORRECT, 100, sessionId = "a-old"),
            qa.toPracticeSnapshot()
        )
        practiceStore.addAnswer(
            practiceAnswer("a2", qa, PracticeAnswerKinds.AUTOMATIC, PracticeResults.CORRECT, 200, sessionId = "a-new"),
            qa.toPracticeSnapshot()
        )
        practiceStore.addAnswer(
            practiceAnswer("b1", qb, PracticeAnswerKinds.AUTOMATIC, PracticeResults.INCORRECT, 300, sessionId = "b-one"),
            qb.toPracticeSnapshot()
        )

        val repository = PracticeStatsRepository(libraryStore, practiceStore)
        val reportA = repository.libraryReport("lib-a")
        assertEquals("answers of a superseded session still count", 2, reportA.overview.practiceCount)
        assertEquals(1, reportA.overview.automaticIncorrect)
        assertEquals(1, reportA.overview.automaticCorrect)
        assertEquals("qa", reportA.questions.single().questionId)
        assertTrue(reportA.questions.single().mastery is PracticeMastery.Passed)

        val reportB = repository.libraryReport("lib-b")
        assertEquals(1, reportB.overview.practiceCount)
        assertEquals("qb", reportB.questions.single().questionId)
        assertTrue(reportB.questions.single().mastery is PracticeMastery.Due)
    }

    @Test
    fun questionHistoryCannotReadAnotherLibraryQuestion() = runTest {
        val libraryStore = TestLibraryStore()
        libraryStore.addLibrary(testLibrary(id = "lib-a", name = "A"))
        libraryStore.addLibrary(testLibrary(id = "lib-b", name = "B"))
        val qa = practiceQuestion("qa", libraryId = "lib-a")
        val qb = practiceQuestion("qb", libraryId = "lib-b")
        libraryStore.addQuestion(qa)
        libraryStore.addQuestion(qb)

        val practiceStore = TestPracticeStore()
        practiceStore.addSession(practiceSession("a", listOf(qa), libraryId = "lib-a"))
        practiceStore.addSession(practiceSession("b", listOf(qb), libraryId = "lib-b"))
        practiceStore.addAnswer(
            practiceAnswer("a1", qa, PracticeAnswerKinds.AUTOMATIC, PracticeResults.CORRECT, 100, sessionId = "a"),
            qa.toPracticeSnapshot()
        )
        practiceStore.addAnswer(
            practiceAnswer("b1", qb, PracticeAnswerKinds.AUTOMATIC, PracticeResults.CORRECT, 100, sessionId = "b"),
            qb.toPracticeSnapshot()
        )

        val repository = PracticeStatsRepository(libraryStore, practiceStore)
        val history = repository.questionHistory("lib-a", "qb")
        assertNull("a question id from another library must not resolve", history.question)
        assertTrue(history.records.isEmpty())
        assertNull(history.stats)

        val missing = repository.questionHistory("lib-a", "missing")
        assertNull(missing.question)
        assertTrue(missing.records.isEmpty())
    }

    @Test
    fun idempotentSubmitRetryDoesNotDoubleCountStatistics() = runTest {
        val libraryStore = TestLibraryStore()
        libraryStore.addLibrary(testLibrary(id = "lib"))
        val question = practiceQuestion("q1", libraryId = "lib")
        libraryStore.addQuestion(question)
        val practiceStore = TestPracticeStore()
        val practiceRepository = com.offlinequiz.app.data.PracticeRepository(
            libraryStore = libraryStore,
            practiceStore = practiceStore,
            clock = { 1_000L },
            idFactory = { "generated" }
        )
        val session = practiceRepository.start("lib", com.offlinequiz.app.model.PracticeModes.SEQUENTIAL)
            as com.offlinequiz.app.data.StartPracticeOutcome.Started
        val userAnswer = com.offlinequiz.app.model.PracticeUserAnswer(selectedKeys = listOf("A"))

        practiceRepository.submitAutomatic(session.sessionId, 0, userAnswer)
        // Same position again: the idempotency key returns the stored row instead of adding one.
        practiceRepository.submitAutomatic(session.sessionId, 0, userAnswer)

        assertEquals(1, practiceStore.historyForLibrary("lib").size)
        val report = PracticeStatsRepository(libraryStore, practiceStore).libraryReport("lib")
        assertEquals(1, report.overview.practiceCount)
        assertEquals(1, report.questions.single().practiceCount)
    }

    @Test(expected = IOException::class)
    fun readFailurePropagatesInsteadOfReturningZeroStatistics() = runTest {
        val libraryStore = TestLibraryStore()
        libraryStore.addLibrary(testLibrary(id = "lib"))
        libraryStore.addQuestion(practiceQuestion("q1"))
        val practiceStore = TestPracticeStore()
        practiceStore.historyFailures = 1

        PracticeStatsRepository(libraryStore, practiceStore).libraryReport("lib")
    }
}
