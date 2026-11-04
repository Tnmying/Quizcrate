package com.offlinequiz.app

import com.offlinequiz.app.ui.AppRouteState
import org.junit.Assert.assertEquals
import org.junit.Test

/** The new entry is a normal route and survives rotation/process route persistence. */
class BackupRouteTest {

    @Test
    fun backupRouteCanBeEnteredPersistedAndLeft() {
        val entered = AppRouteState().startBackup()
        assertEquals(AppRouteState.Route.BACKUP, entered.route)
        val restored = AppRouteState.fromPersistedList(entered.toPersistedList())
        assertEquals(AppRouteState.Route.BACKUP, restored.route)
        assertEquals(AppRouteState.Route.HOME, restored.backFromBackup().route)
    }
}
