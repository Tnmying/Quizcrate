package com.offlinequiz.app

import com.offlinequiz.app.ui.AppRouteState
import com.offlinequiz.app.ui.AppRouteState.Route
import org.junit.Assert.*
import org.junit.Test
import pdfprobe.Model

class ImportBackNavigationTest {
    private fun preview() = ImportUiState.Preview(
        ImportedPdf("example.pdf", 1, 0, 0, 0, Model.ParseResult()), "测试题库"
    )

    @Test fun idleParsingPreviewAndErrorReturnToHomeExactlyOnce() {
        val states = listOf(ImportUiState.Idle, ImportUiState.Parsing, preview(), ImportUiState.Error("读取失败"))
        for (state in states) {
            var route = AppRouteState().startImport()
            var calls = 0
            assertTrue(state.requestBack { calls++; route = route.backFromImport() })
            assertEquals(1, calls)
            assertEquals(Route.HOME, route.route)
        }
    }

    @Test fun savingAndSavedHandoffCannotLeaveButFailedSavePreviewCan() {
        val states = listOf(ImportUiState.Saving, ImportUiState.Saved("new-library"))
        for (state in states) {
            var route = AppRouteState().startImport()
            var calls = 0
            assertFalse(state.requestBack { calls++; route = route.backFromImport() })
            assertEquals(0, calls)
            assertEquals(Route.IMPORT, route.route)
        }
        val retryable = preview().copy(saveError = "保存失败，题库未创建")
        var calls = 0
        assertTrue(retryable.requestBack { calls++ })
        assertEquals(1, calls)
        assertEquals("测试题库", retryable.suggestedName)
    }
}
