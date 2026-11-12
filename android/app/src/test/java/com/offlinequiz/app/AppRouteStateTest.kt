package com.offlinequiz.app

import com.offlinequiz.app.ui.AppRouteState
import com.offlinequiz.app.ui.AppRouteState.Route
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Routing regression for the history entry: a history screen must return to wherever it was opened
 * from (library or wrongbook), the editor must return to history, and rotation restore must keep
 * every id/return destination.
 */
class AppRouteStateTest {
    @Test
    fun nestedManagementBackSequenceVisitsEveryParentInsteadOfExiting() {
        var state = AppRouteState().openLibrary("lib-a").openQuestionManager(onlyPending = false)
            .openHistory("q1", Route.QUESTIONS).openEditor("q1", Route.HISTORY)
        state = state.backFromEditor()
        assertEquals(Route.HISTORY, state.route)
        state = state.backFromHistory()
        assertEquals(Route.QUESTIONS, state.route)
        assertEquals("ALL", state.questionFilter)
        state = state.backFromQuestionManager()
        assertEquals(Route.LIBRARY, state.route)
        assertEquals("lib-a", state.libraryId)
        state = state.backFromLibrary()
        assertEquals(Route.HOME, state.route)
        assertEquals("", state.libraryId)
    }

    @Test
    fun restoredPracticeCorrectionReturnsThroughWrongbookAndLibraryToHome() {
        val saved = AppRouteState().openLibrary("lib-a").openWrongbook()
            .openPractice("s1", Route.WRONGBOOK).openEditor("q1", Route.PRACTICE).toPersistedList()
        var state = AppRouteState.fromPersistedList(saved).backFromEditor()
        assertEquals(Route.PRACTICE, state.route)
        assertEquals("s1", state.practiceSessionId)
        state = state.backFromPractice()
        assertEquals(Route.WRONGBOOK, state.route)
        state = state.backFromWrongbook()
        assertEquals(Route.LIBRARY, state.route)
        assertEquals(Route.HOME, state.backFromLibrary().route)
    }
    @Test
    fun allQuestionsAndPendingEntryUseDifferentFiltersAndKeepReturnTargets() {
        val library = AppRouteState().openLibrary("lib-a")
        val all = library.openQuestionManager(onlyPending = false)
        assertEquals("ALL", all.questionFilter)
        assertEquals(Route.QUESTIONS, all.route)
        val pending = library.openQuestionManager()
        assertEquals("ATTENTION", pending.questionFilter)
        assertEquals(Route.QUESTIONS, all.openHistory("q1", Route.QUESTIONS).backFromHistory().route)
        assertEquals(Route.QUESTIONS, pending.openEditor("q1", Route.QUESTIONS).backFromEditor().route)
        assertEquals(Route.LIBRARY, all.backFromQuestionManager().route)
        assertEquals("lib-a", all.backFromQuestionManager().libraryId)
    }

    @Test
    fun reviewOpensDedicatedPendingListAndEditorReturnsToSameFilter() {
        val state = AppRouteState().openLibrary("lib-a").openQuestionManager()
        assertEquals(Route.QUESTIONS, state.route)
        assertEquals("ATTENTION", state.questionFilter)
        val edited = state.openEditor("q1", Route.QUESTIONS).backFromEditor()
        assertEquals(Route.QUESTIONS, edited.route)
        assertEquals("ATTENTION", edited.questionFilter)
        val restored = AppRouteState.fromPersistedList(edited.toPersistedList())
        assertEquals(edited, restored)
        assertEquals(Route.LIBRARY, restored.backFromQuestionManager().route)
        assertEquals("lib-a", restored.backFromQuestionManager().libraryId)
        assertEquals("ATTENTION", state.copy(questionFilter = "ALL").openQuestionManager().questionFilter)
    }

    @Test
    fun correctionKeepsPracticeSessionAndWrongbookReturnAcrossRestore() {
        val state = AppRouteState().openLibrary("lib-a").openWrongbook()
            .openPractice("s1", Route.WRONGBOOK).openEditor("q1", Route.PRACTICE)
        val restored = AppRouteState.fromPersistedList(state.toPersistedList())
        val back = restored.backFromEditor()
        assertEquals(Route.PRACTICE, back.route)
        assertEquals("s1", back.practiceSessionId)
        assertEquals("q1", back.questionId)
        assertEquals(Route.WRONGBOOK, back.backFromPractice().route)
    }

    @Test
    fun previousSevenFieldNavigationStillRestores() {
        val original = AppRouteState().openLibrary("lib-a").openPractice("s1", Route.LIBRARY)
        assertEquals(original, AppRouteState.fromPersistedList(original.toPersistedList().take(7)))
    }

    @Test
    fun historyOpenedFromLibraryReturnsToLibraryWithIds() {
        val state = AppRouteState().openLibrary("lib-a").openHistory("q1", Route.LIBRARY)
        assertEquals(Route.HISTORY, state.route)
        assertEquals("lib-a", state.libraryId)
        assertEquals("q1", state.questionId)
        assertEquals(Route.LIBRARY, state.historyReturn)

        val back = state.backFromHistory()
        assertEquals(Route.LIBRARY, back.route)
        assertEquals("lib-a", back.libraryId)
        assertEquals("q1", back.questionId)
    }

    @Test
    fun historyOpenedFromWrongbookReturnsToWrongbook() {
        val state = AppRouteState().openLibrary("lib-a").openWrongbook()
            .openHistory("q1", Route.WRONGBOOK)
        assertEquals(Route.WRONGBOOK, state.historyReturn)
        assertEquals(Route.WRONGBOOK, state.backFromHistory().route)
        assertEquals("lib-a", state.backFromHistory().libraryId)
    }

    @Test
    fun editorOpenedFromHistoryReturnsToHistoryWithQuestion() {
        val state = AppRouteState().openLibrary("lib-a")
            .openHistory("q1", Route.LIBRARY)
            .openEditor("q1", Route.HISTORY)
        assertEquals(Route.EDITOR, state.route)
        assertEquals(Route.HISTORY, state.editorReturn)

        val back = state.backFromEditor()
        assertEquals(Route.HISTORY, back.route)
        assertEquals("q1", back.questionId)
    }

    @Test
    fun editorOpenedFromLibraryReturnsToLibraryAndClearsQuestion() {
        val state = AppRouteState().openLibrary("lib-a").openEditor("q1", Route.LIBRARY)
        val back = state.backFromEditor()
        assertEquals(Route.LIBRARY, back.route)
        assertEquals("", back.questionId)
        assertEquals("lib-a", back.libraryId)
    }

    @Test
    fun practiceReturnsToTheScreenThatStartedIt() {
        val fromLibrary = AppRouteState().openLibrary("lib-a").openPractice("s1", Route.LIBRARY)
        assertEquals(Route.LIBRARY, fromLibrary.backFromPractice().route)
        assertEquals("", fromLibrary.backFromPractice().practiceSessionId)

        val fromWrongbook = AppRouteState().openLibrary("lib-a").openWrongbook()
            .openPractice("s2", Route.WRONGBOOK)
        assertEquals(Route.WRONGBOOK, fromWrongbook.practiceReturn)
        assertEquals(Route.WRONGBOOK, fromWrongbook.backFromPractice().route)
    }

    @Test
    fun libraryBackGoesHomeAndClearsSession() {
        val state = AppRouteState().openLibrary("lib-a").openPractice("s1", Route.LIBRARY)
        val back = state.backFromLibrary()
        assertEquals(Route.HOME, back.route)
        assertEquals("", back.libraryId)
        assertEquals("", back.practiceSessionId)
    }

    @Test
    fun persistedListRestoresEveryNavigationField() {
        val state = AppRouteState(
            route = Route.PRACTICE,
            libraryId = "lib-a",
            questionId = "q1",
            practiceSessionId = "s1",
            practiceReturn = Route.WRONGBOOK,
            editorReturn = Route.HISTORY,
            historyReturn = Route.LIBRARY,
            wrongbookReturn = Route.WRONGBOOK_INDEX,
            backupReturn = Route.PROFILE,
            libraryReturn = Route.RECORDS
        )
        val saved = state.toPersistedList()
        assertEquals(11, saved.size)
        assertEquals(state, AppRouteState.fromPersistedList(saved))
        // A truncated list must not crash the restore path.
        assertEquals(AppRouteState(), AppRouteState.fromPersistedList(emptyList()))
    }
}
