package com.offlinequiz.app

import com.offlinequiz.app.model.QuestionEditSession
import com.offlinequiz.app.model.SaveOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuestionEditSessionTest {

    @Test
    fun editsDuringSaveAreRejectedAndReadbackCannotOverwriteTheSnapshot() = runTest {
        val gate = CompletableDeferred<Unit>()
        val initial = testQuestion(stem = "保存前的题干____。", answers = listOf("甲"))
        val persisted = initial.copy(editedAt = 11L)
        val session = QuestionEditSession(
            saveQuestion = { _, _ ->
                gate.await()
                SaveOutcome.Saved(confirmed = false, changed = true)
            },
            reloadQuestion = { persisted }
        )
        session.load(initial)

        val snapshot = session.beginSave()
        assertTrue(session.saving)
        assertEquals("保存前的题干____。", snapshot!!.stem)
        assertFalse(session.update { it.copy(stem = "保存期间输入的题干____。") })
        assertEquals("保存前的题干____。", session.question!!.stem)

        val saveJob = launch { session.finishSave(snapshot, confirm = false) }
        runCurrent()
        assertTrue(session.saving)
        assertFalse(session.update { it.copy(stem = "保存挂起时输入的题干____。") })
        gate.complete(Unit)
        saveJob.join()

        assertFalse(session.saving)
        assertEquals("保存前的题干____。", session.question!!.stem)
        assertFalse(session.hasUnsavedChanges())
        assertEquals("已保存", session.message)
        // Only after the save completes may a later edit be accepted normally.
        assertTrue(session.update { it.copy(stem = "保存完成后的题干____。") })
        assertTrue(session.hasUnsavedChanges())
    }

    @Test
    fun failedSaveKeepsTheEditedContentAndDirtyState() = runTest {
        val initial = testQuestion(stem = "旧题干____。")
        val session = QuestionEditSession(
            saveQuestion = { _, _ -> SaveOutcome.Failed("保存失败，原有内容未改变") },
            reloadQuestion = { null }
        )
        session.load(initial)
        assertTrue(session.update { it.copy(stem = "新题干____。") })

        val snapshot = session.beginSave()!!
        session.finishSave(snapshot, confirm = false)

        assertFalse(session.saving)
        assertEquals("新题干____。", session.question!!.stem)
        assertTrue(session.hasUnsavedChanges())
        assertEquals("保存失败，原有内容未改变", session.message)
        assertTrue(session.errors.isEmpty())
    }

    @Test
    fun invalidSaveKeepsTheEditedContentAndReportsErrors() = runTest {
        val initial = testQuestion(stem = "旧题干____。")
        val session = QuestionEditSession(
            saveQuestion = { _, _ -> SaveOutcome.Invalid(listOf("题干不能为空")) },
            reloadQuestion = { null }
        )
        session.load(initial)
        assertTrue(session.update { it.copy(stem = "   ") })

        val snapshot = session.beginSave()!!
        session.finishSave(snapshot, confirm = true)

        assertFalse(session.saving)
        assertEquals("   ", session.question!!.stem)
        assertEquals(listOf("题干不能为空"), session.errors)
        assertNull(session.message)
        assertTrue(session.hasUnsavedChanges())
    }

    @Test
    fun successfulSaveReloadsTheQuestionAndClearsDirtyState() = runTest {
        val initial = testQuestion(stem = "旧题干____。")
        val saved = initial.copy(stem = "新题干____。", confirmed = true, confirmedAt = 20L, editedAt = 19L)
        val session = QuestionEditSession(
            saveQuestion = { _, _ -> SaveOutcome.Saved(confirmed = true, changed = true) },
            reloadQuestion = { saved }
        )
        session.load(initial)
        assertTrue(session.update { it.copy(stem = "新题干____。") })

        val snapshot = session.beginSave()!!
        session.finishSave(snapshot, confirm = true)

        assertFalse(session.saving)
        assertEquals(saved, session.question)
        assertFalse(session.hasUnsavedChanges())
        assertEquals("已保存并标记为已确认", session.message)
    }

    @Test
    fun secondSaveRequestIsRejectedWhileTheFirstIsInFlight() = runTest {
        val gate = CompletableDeferred<Unit>()
        val initial = testQuestion()
        val session = QuestionEditSession(
            saveQuestion = { _, _ ->
                gate.await()
                SaveOutcome.Saved(confirmed = false, changed = false)
            },
            reloadQuestion = { initial }
        )
        session.load(initial)
        assertTrue(session.beginSave() != null)
        assertTrue(session.saving)
        assertNull(session.beginSave())
        gate.complete(Unit)
    }
}
