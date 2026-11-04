package com.offlinequiz.app

import com.offlinequiz.app.ui.*
import com.offlinequiz.app.ui.AppRouteState.Route
import org.junit.Assert.*
import org.junit.Test

class PageMotionTest {
    @Test fun initialAndRestoredDestinationHaveNoNavigationAnimation() {
        val restored = AppRouteState().openLibrary("lib").openEditor("q", Route.LIBRARY)
        assertEquals(PageMotionStyle.NONE, PageMotionTracker(restored).next(restored))
        assertEquals(PageMotionStyle.NONE, PageMotion.between(restored, restored))
    }

    @Test fun pageEntryAndReturnHaveOppositeDirections() {
        val home = AppRouteState()
        val library = home.openLibrary("lib")
        val manager = library.openQuestionManager(false)
        val wrongbook = library.openWrongbook()
        val practice = library.openPractice("session", Route.LIBRARY)
        for (page in listOf(manager, wrongbook, practice)) {
            assertEquals(PageMotionStyle.FORWARD, PageMotion.between(library, page))
            assertEquals(PageMotionStyle.BACK, PageMotion.between(page, library))
        }
        assertEquals(PageMotionStyle.FORWARD, PageMotion.between(home, library))
        assertEquals(PageMotionStyle.BACK, PageMotion.between(library, library.backFromLibrary()))
        assertEquals(PageMotionStyle.BACK, PageMotion.between(home.startBackup(), home))
        assertEquals(PageMotionStyle.BACK, PageMotion.between(home.startImport(), home))
    }

    @Test fun cardEntryDoesNotConfuseNestedEditorReturnWithForwardHistoryEntry() {
        val manager = AppRouteState().openLibrary("lib").openQuestionManager(false)
        val history = manager.openHistory("q", Route.QUESTIONS)
        val editor = history.openEditor("q", Route.HISTORY)
        assertEquals(PageMotionStyle.CARD, PageMotion.between(manager, history))
        assertEquals(PageMotionStyle.CARD, PageMotion.between(history, editor))
        assertEquals(PageMotionStyle.BACK, PageMotion.between(editor, editor.backFromEditor()))
        assertEquals(PageMotionStyle.BACK, PageMotion.between(history, history.backFromHistory()))
        val practice = manager.openPractice("s", Route.WRONGBOOK)
        val correction = practice.openEditor("q", Route.PRACTICE)
        assertEquals(PageMotionStyle.BACK, PageMotion.between(correction, correction.backFromEditor()))
    }

    @Test fun filterAndNonvisualFieldsDoNotCreateAnotherDestination() {
        val manager = AppRouteState().openLibrary("lib").openQuestionManager(false)
        val changed = manager.copy(questionFilter = "ATTENTION", questionId = "unused")
        assertEquals(PageMotion.key(manager), PageMotion.key(changed))
        assertEquals(PageMotionStyle.NONE, PageMotion.between(manager, changed))
        val tracker = PageMotionTracker(manager)
        assertEquals(PageMotionStyle.NONE, tracker.next(changed))
    }

    @Test fun differentQuestionLibraryAndSessionIdsAreDistinctDestinations() {
        val library = AppRouteState().openLibrary("one")
        assertNotEquals(PageMotion.key(library), PageMotion.key(library.openLibrary("two")))
        val editor = library.openEditor("q1", Route.LIBRARY)
        assertNotEquals(PageMotion.key(editor), PageMotion.key(editor.copy(questionId = "q2")))
        val practice = library.openPractice("s1", Route.LIBRARY)
        assertNotEquals(PageMotion.key(practice), PageMotion.key(practice.copy(practiceSessionId = "s2")))
    }
}
