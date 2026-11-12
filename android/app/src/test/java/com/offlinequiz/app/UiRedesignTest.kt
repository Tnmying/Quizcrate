package com.offlinequiz.app

import com.offlinequiz.app.model.*
import com.offlinequiz.app.ui.AppRouteState
import com.offlinequiz.app.ui.AppRouteState.Route
import org.junit.Assert.*
import org.junit.Test

class UiRedesignTest {
    @Test fun wrongbookIndexSurvivesNestedPracticeCorrectionAndRestore() {
        val state = AppRouteState(route = Route.WRONGBOOK_INDEX).openWrongbook("lib")
            .openPractice("session", Route.WRONGBOOK).openEditor("question", Route.PRACTICE)
        val restored = AppRouteState.fromPersistedList(state.toPersistedList())
        assertEquals("session", restored.backFromEditor().practiceSessionId)
        assertEquals(Route.WRONGBOOK_INDEX, restored.backFromEditor().backFromPractice().backFromWrongbook().route)
    }
    @Test fun profileBackupAndRestoredLibraryReturnToProfile() {
        val backup = AppRouteState(route = Route.PROFILE).startBackup()
        assertEquals(Route.PROFILE, backup.backFromBackup().route)
        assertEquals(Route.PROFILE, AppRouteState.fromPersistedList(backup.openLibrary("restored").toPersistedList()).backFromLibrary().route)
    }
    @Test fun recordsLibraryReturnsToRecords() {
        assertEquals(Route.RECORDS, AppRouteState(route = Route.RECORDS).openLibrary("lib").backFromLibrary().route)
    }
    @Test fun homeResumeCorrectionReturnsHomeWithLibraryContext() {
        val state = AppRouteState(libraryId = "lib").openPractice("session", Route.HOME).openEditor("q", Route.PRACTICE)
        val restored = AppRouteState.fromPersistedList(state.toPersistedList()).backFromEditor()
        assertEquals("lib", restored.libraryId)
        assertEquals(Route.HOME, restored.backFromPractice().route)
    }
    @Test fun oldSavedNavigationStillLoads() {
        val old = listOf(Route.WRONGBOOK, "lib", "", "", Route.LIBRARY, Route.LIBRARY, Route.LIBRARY)
        assertEquals(Route.LIBRARY, AppRouteState.fromPersistedList(old).backFromWrongbook().route)
    }
    private fun stats(correct: Int, wrong: Int, remembered: Int = 0, forgotten: Int = 0) = PracticeLibraryStats(
        correct + wrong + remembered + forgotten, correct, wrong, remembered, forgotten,
        100, 12, null, 0, 0
    )
    @Test fun globalAccuracyUsesWeightedCountsIncludesSelfAndExcludesSkips() {
        val total = globalPracticeTotals(listOf(stats(1,0), stats(0,9), stats(0,0,2,1)))
        assertEquals(13, total.effective)
        assertEquals(3, total.correct)
        assertEquals(23, total.accuracyPercent)
    }
    @Test fun emptyStatsHaveNoAccuracy() {
        assertNull(globalPracticeTotals(emptyList()).accuracyPercent)
        assertEquals(0, globalPracticeTotals(listOf(stats(0,0))).effective)
    }
    private fun session(id: String, time: Long, status: String = PracticeStatuses.ACTIVE, phase: String = PracticePhases.ANSWERING) =
        PracticeSessionSummary(id, id, PracticeModes.SEQUENTIAL, status, 10, 2, phase, 2, 0, time)
    @Test fun latestResumeExcludesCompletedAndUsesUpdateTime() {
        assertEquals("recent", latestUnfinishedPractice(listOf(session("older",1), session("recent",9),
            session("completed",10,PracticeStatuses.COMPLETED), session("ended",11,phase = PracticePhases.COMPLETED)))?.id)
        assertNull(latestUnfinishedPractice(emptyList()))
    }
}
