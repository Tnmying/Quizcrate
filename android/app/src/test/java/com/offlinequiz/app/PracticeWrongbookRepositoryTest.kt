package com.offlinequiz.app

import com.offlinequiz.app.data.PracticeRepository
import com.offlinequiz.app.data.StartPracticeOutcome
import com.offlinequiz.app.data.SubmitPracticeOutcome
import com.offlinequiz.app.model.PracticeAnswerKinds
import com.offlinequiz.app.model.PracticeModes
import com.offlinequiz.app.model.PracticePhases
import com.offlinequiz.app.model.PracticeResults
import com.offlinequiz.app.model.PracticeSources
import com.offlinequiz.app.model.PracticeUserAnswer
import com.offlinequiz.app.model.toPracticeSnapshot
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wrongbook rounds must reuse the normal snapshot/eligibility/idempotency rules. */
class PracticeWrongbookRepositoryTest {

    private fun repository(
        libraryStore: TestLibraryStore,
        practiceStore: TestPracticeStore
    ): PracticeRepository {
        var counter = 0
        return PracticeRepository(
            libraryStore = libraryStore,
            practiceStore = practiceStore,
            clock = { 1_000L },
            idFactory = {
                counter += 1
                "wrongbook-session-$counter"
            }
        )
    }

    private fun setup(vararg questions: com.offlinequiz.app.model.StoredQuestion): Pair<TestLibraryStore, TestPracticeStore> {
        val libraryStore = TestLibraryStore()
        libraryStore.addLibrary(testLibrary(id = "lib", name = "错题本"))
        questions.forEach { libraryStore.addQuestion(it) }
        return libraryStore to TestPracticeStore()
    }

    @Test
    fun startOnlyIncludesSelectedEligibleQuestions() = runTest {
        val (libraryStore, practiceStore) = setup(
            practiceQuestion("q1", position = 0),
            practiceQuestion("q2", position = 1),
            practiceQuestion("q3", position = 2)
        )
        val repo = repository(libraryStore, practiceStore)

        val outcome = repo.startWrongbook("lib", PracticeModes.SEQUENTIAL, listOf("q1", "q3"))
        assertTrue(outcome is StartPracticeOutcome.Started)
        val sessionId = (outcome as StartPracticeOutcome.Started).sessionId
        val session = practiceStore.sessions.getValue(sessionId)
        assertEquals(listOf("q1", "q3"), session.questions.map { it.questionId })
        assertEquals(PracticeSources.WRONGBOOK, session.source)
        assertEquals(2, session.total)
    }

    @Test
    fun ineligibleSelectedQuestionsAreExcludedAndEmptySelectionCreatesNoSession() = runTest {
        val review = practiceQuestion(
            "review",
            position = 0,
            confirmed = false,
            reviewReasons = listOf("no_answer_in_parentheses")
        )
        val (libraryStore, practiceStore) = setup(review, practiceQuestion("q2", position = 1))
        val repo = repository(libraryStore, practiceStore)

        val onlyReview = repo.startWrongbook("lib", PracticeModes.SEQUENTIAL, listOf("review"))
        assertTrue(onlyReview is StartPracticeOutcome.NoEligible)
        assertTrue("no session is created when nothing is practicable", practiceStore.sessions.isEmpty())

        val mixed = repo.startWrongbook("lib", PracticeModes.SEQUENTIAL, listOf("review", "q2"))
        assertTrue(mixed is StartPracticeOutcome.Started)
        val session = practiceStore.sessions.getValue(
            (mixed as StartPracticeOutcome.Started).sessionId
        )
        assertEquals(listOf("q2"), session.questions.map { it.questionId })
    }

    @Test
    fun emptySelectionCreatesNoSession() = runTest {
        val (libraryStore, practiceStore) = setup(practiceQuestion("q1"))
        val repo = repository(libraryStore, practiceStore)

        val outcome = repo.startWrongbook("lib", PracticeModes.RANDOM, emptyList())
        assertTrue(outcome is StartPracticeOutcome.NoEligible)
        assertTrue(practiceStore.sessions.isEmpty())
    }

    @Test
    fun randomRoundIsFixedWithoutDuplicatesAndRestoresTheSameOrder() = runTest {
        val questions = (0 until 6).map { practiceQuestion("q$it", position = it) }.toTypedArray()
        val (libraryStore, practiceStore) = setup(*questions)
        val repo = repository(libraryStore, practiceStore)

        val outcome = repo.startWrongbook(
            "lib",
            PracticeModes.RANDOM,
            questions.map { it.id },
            Random(7)
        )
        assertTrue(outcome is StartPracticeOutcome.Started)
        val sessionId = (outcome as StartPracticeOutcome.Started).sessionId
        val firstOrder = repo.loadState(sessionId)!!.session.questions.map { it.questionId }
        assertEquals(6, firstOrder.size)
        assertEquals(6, firstOrder.toSet().size)

        val restored = repo.loadState(sessionId)!!.session.questions.map { it.questionId }
        assertEquals("reopening must not reshuffle the round", firstOrder, restored)
        assertEquals(PracticeSources.WRONGBOOK, repo.loadState(sessionId)!!.session.source)
    }

    @Test
    fun wrongbookAnswersShareTheSameHistoryAsNormalPractice() = runTest {
        val (libraryStore, practiceStore) = setup(practiceQuestion("q1", position = 0))
        var now = 1_000L
        var counter = 0
        val repo = PracticeRepository(
            libraryStore = libraryStore,
            practiceStore = practiceStore,
            clock = { now++ },
            idFactory = {
                counter += 1
                "generated-$counter"
            }
        )

        val normal = repo.start("lib", PracticeModes.SEQUENTIAL) as StartPracticeOutcome.Started
        assertTrue(
            repo.submitAutomatic(normal.sessionId, 0, PracticeUserAnswer(selectedKeys = listOf("B")))
                is SubmitPracticeOutcome.Recorded
        )

        val wrongbook = repo.startWrongbook("lib", PracticeModes.SEQUENTIAL, listOf("q1")) as StartPracticeOutcome.Started
        assertTrue(
            repo.submitAutomatic(wrongbook.sessionId, 0, PracticeUserAnswer(selectedKeys = listOf("A")))
                is SubmitPracticeOutcome.Recorded
        )

        val history = practiceStore.historyForLibrary("lib")
        assertEquals("both rounds are recorded in the same question history", 2, history.size)
        assertEquals(PracticeResults.INCORRECT, history[0].answer.result)
        assertEquals(PracticeResults.CORRECT, history[1].answer.result)
        assertEquals(PracticeAnswerKinds.AUTOMATIC, history[1].answer.kind)
        assertEquals(
            PracticePhases.FEEDBACK,
            practiceStore.sessions.getValue(wrongbook.sessionId).phase
        )
    }
}
