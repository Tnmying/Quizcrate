package com.offlinequiz.app

import com.offlinequiz.app.data.LibraryRepository
import com.offlinequiz.app.data.PracticeRepository
import com.offlinequiz.app.data.PracticeStatsRepository
import com.offlinequiz.app.data.PracticeStore
import com.offlinequiz.app.model.PracticeModes
import com.offlinequiz.app.model.PracticeSessionSummary
import com.offlinequiz.app.model.StoredQuestion
import com.offlinequiz.app.viewmodel.LibraryDetailViewModel
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The normal practice entry on the library screen gets the same "unknown/failed active session"
 * protection as the wrongbook screen: no silent new round without a successful lookup.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryDetailPracticeGuardTest {

    private class Fixture {
        val libraryStore = TestLibraryStore()
        val practiceStore = TestPracticeStore()
        val libraryId = "lib"
        private var counter = 0

        init {
            libraryStore.addLibrary(testLibrary(id = libraryId, name = "题库"))
        }

        fun addQuestion(question: StoredQuestion) {
            libraryStore.addQuestion(question)
            libraryStore.publish(libraryId)
        }

        fun addActiveSession(question: StoredQuestion) {
            practiceStore.addSession(
                practiceSession("existing", listOf(question), libraryId = libraryId)
            )
        }

        fun viewModel(store: PracticeStore = practiceStore): LibraryDetailViewModel =
            LibraryDetailViewModel(
                applicationWithoutAndroidRuntime(),
                LibraryRepository(libraryStore),
                PracticeRepository(
                    libraryStore = libraryStore,
                    practiceStore = store,
                    clock = { 5_000L },
                    idFactory = {
                        counter += 1
                        "new-$counter"
                    }
                ),
                PracticeStatsRepository(libraryStore, store),
                UnconfinedTestDispatcher()
            )

        fun newSessions(): List<String> = practiceStore.sessions.keys.filter { it.startsWith("new-") }
    }

    @Test
    fun activeLookupFailureBlocksStartAndRetryRecovers() = statsViewModelTest {
        val fixture = Fixture()
        fixture.addQuestion(practiceQuestion("q1"))
        var failures = 1
        val failing = object : PracticeStore by fixture.practiceStore {
            override fun observeActiveSession(libraryId: String): Flow<PracticeSessionSummary?> =
                flow {
                    if (failures > 0) {
                        failures -= 1
                        throw IOException("active lookup failed")
                    }
                    emit(fixture.practiceStore.activeFlow.value)
                }
        }
        val viewModel = fixture.viewModel(failing)

        viewModel.load(fixture.libraryId)
        advanceUntilIdle()

        assertTrue(viewModel.practiceLoadFailed)
        assertFalse(viewModel.practiceLoaded)
        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        advanceUntilIdle()
        assertNull(viewModel.startedPracticeId)
        assertTrue(fixture.newSessions().isEmpty())
        assertNotNull(viewModel.message)

        viewModel.retryActivePractice()
        advanceUntilIdle()
        assertFalse(viewModel.practiceLoadFailed)
        assertTrue(viewModel.practiceLoaded)

        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        advanceUntilIdle()
        assertNotNull(viewModel.startedPracticeId)
        assertEquals(1, fixture.newSessions().size)
    }

    @Test
    fun existingSessionRequiresExplicitNewRoundConfirmation() = statsViewModelTest {
        val fixture = Fixture()
        val question = practiceQuestion("q1")
        fixture.addQuestion(question)
        fixture.addActiveSession(question)
        val viewModel = fixture.viewModel()

        viewModel.load(fixture.libraryId)
        advanceUntilIdle()
        assertTrue(viewModel.practiceLoaded)
        assertNotNull(viewModel.activePractice)

        viewModel.startPractice(PracticeModes.SEQUENTIAL)
        advanceUntilIdle()
        assertEquals("plain start must not replace an active session", 1, fixture.practiceStore.sessions.size)
        assertNull(viewModel.startedPracticeId)

        viewModel.startPractice(PracticeModes.SEQUENTIAL, confirmedReplace = true)
        advanceUntilIdle()
        assertEquals(2, fixture.practiceStore.sessions.size)
        assertNotNull(viewModel.startedPracticeId)
    }
}
