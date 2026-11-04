package com.offlinequiz.app

import com.offlinequiz.app.data.*
import com.offlinequiz.app.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test

/** Synthetic data only; SQL cascade/rollback is verified separately against exported schema. */
class LibraryMaintenanceTest {
    private class Store : LibraryMaintenanceStore {
        val libraries = TestLibraryStore()
        val practice = TestPracticeStore()
        var fail = false
        override suspend fun endRound(libraryId: String, expectedSessionId: String, now: Long): Boolean {
            if (fail) error("simulated write failure")
            val s = practice.sessions[expectedSessionId] ?: return false
            if (s.libraryId != libraryId || s.status != PracticeStatuses.ACTIVE) return false
            practice.sessions[expectedSessionId] = s.copy(status = PracticeStatuses.SUPERSEDED, updatedAt = now)
            practice.activeFlow.value = null
            return true
        }
        override suspend fun resetPractice(libraryId: String): Boolean {
            if (fail) error("simulated write failure")
            if (libraryId !in libraries.libraries) return false
            val ids = practice.sessions.values.filter { it.libraryId == libraryId }.map { it.id }.toSet()
            practice.answerMap.entries.removeAll { it.value.sessionId in ids }
            practice.sessions.keys.removeAll(ids)
            practice.activeFlow.value = null
            return true
        }
        override suspend fun deleteLibrary(libraryId: String): DeletedLibrary? {
            if (fail) error("simulated write failure")
            val l = libraries.libraries[libraryId] ?: return null
            resetPractice(libraryId)
            libraries.questionMap.entries.removeAll { it.value.libraryId == libraryId }
            libraries.libraries.remove(libraryId)
            return DeletedLibrary(l.pdfRelativePath)
        }
    }

    private suspend fun fixture(): Store {
        val s = Store()
        for (id in listOf("a", "b")) {
            s.libraries.addLibrary(testLibrary(id).copy(pdfRelativePath = "libraries/$id/source.pdf"))
            val q = practiceQuestion("q-$id", libraryId = id)
            s.libraries.addQuestion(q)
            val repo = PracticeRepository(s.libraries, s.practice, idFactory = { "session-$id" })
            val started = repo.start(id, PracticeModes.SEQUENTIAL) as StartPracticeOutcome.Started
            repo.peek(started.sessionId, 0, PracticeUserAnswer())
        }
        return s
    }

    @Test fun endingKeepsAnswerAndWrongbookWithoutAddingCompletedRound() = runBlocking {
        val s = fixture()
        val before = PracticeStatsRepository(s.libraries, s.practice).libraryReport("a").overview
        assertTrue(LibraryMaintenanceRepository(s).endRound("a", "session-a") is MaintenanceOutcome.Done)
        assertEquals(PracticeStatuses.SUPERSEDED, s.practice.sessions.getValue("session-a").status)
        assertEquals(before, PracticeStatsRepository(s.libraries, s.practice).libraryReport("a").overview)
        assertEquals(0, before.rounds.normalCompleted)
        assertEquals(1, before.wrongQuestionCount)
        assertEquals(PracticeStatuses.ACTIVE, s.practice.sessions.getValue("session-b").status)
    }

    @Test fun staleOrOtherLibraryRoundCannotBeEnded() = runBlocking {
        val s = fixture()
        val repo = LibraryMaintenanceRepository(s)
        assertTrue(repo.endRound("a", "session-b") is MaintenanceOutcome.Failed)
        assertTrue(repo.endRound("a", "missing") is MaintenanceOutcome.Failed)
        assertEquals(PracticeStatuses.ACTIVE, s.practice.sessions.getValue("session-b").status)
        assertTrue(repo.endRound("a", "session-a") is MaintenanceOutcome.Done)
        assertTrue(repo.endRound("a", "session-a") is MaintenanceOutcome.Failed)
    }

    @Test fun resetClearsAllPracticeHistoryAndRoundsButKeepsQuestionsPdfAndOtherLibrary() = runBlocking {
        val s = fixture()
        PracticeRepository(s.libraries, s.practice).advance("session-a", 0)
        val q = s.libraries.questionMap.getValue("q-a")
        val b = s.practice.sessions.getValue("session-b")
        assertTrue(LibraryMaintenanceRepository(s).resetPractice("a") is MaintenanceOutcome.Done)
        val report = PracticeStatsRepository(s.libraries, s.practice).libraryReport("a")
        assertEquals(PracticeTotals(0, 0), report.overview.totals)
        assertEquals(0, report.overview.rounds.normalCompleted)
        assertTrue(report.wrongbook.isEmpty())
        assertTrue(report.due.isEmpty())
        assertEquals(q, s.libraries.questionMap["q-a"])
        assertEquals("libraries/a/source.pdf", s.libraries.libraries.getValue("a").pdfRelativePath)
        assertEquals(b, s.practice.sessions["session-b"])
        assertEquals(1, PracticeStatsRepository(s.libraries, s.practice).libraryReport("b").overview.wrongQuestionCount)
        val stale = PracticeRepository(s.libraries, s.practice).peek("session-a", 0, PracticeUserAnswer())
        assertTrue(stale is SubmitPracticeOutcome.Failed)
    }

    @Test fun deleteCascadesOnlyTargetAndCleansPdfOnlyAfterDatabaseCommit() = runBlocking {
        val s = fixture()
        val cleaned = mutableListOf<String>()
        val repo = LibraryMaintenanceRepository(s, deletePdf = {
            assertFalse(s.libraries.libraries.containsKey("a"))
            cleaned += it
        })
        assertTrue(repo.deleteLibrary("a") is MaintenanceOutcome.Done)
        assertNull(s.libraries.questionMap["q-a"])
        assertNull(s.practice.sessions["session-a"])
        assertTrue(s.practice.answerMap.values.none { it.sessionId == "session-a" })
        assertNotNull(s.libraries.questionMap["q-b"])
        assertNotNull(s.practice.sessions["session-b"])
        assertEquals(listOf("libraries/a/source.pdf"), cleaned)
        assertTrue(repo.deleteLibrary("a") is MaintenanceOutcome.Failed)
        assertEquals(1, cleaned.size)
    }

    @Test fun failureDoesNotCleanPdfAndKeepsTargetRecords() = runBlocking {
        val s = fixture()
        s.fail = true
        var cleaned = false
        val repo = LibraryMaintenanceRepository(s, deletePdf = { cleaned = true })
        assertTrue(repo.deleteLibrary("a") is MaintenanceOutcome.Failed)
        assertTrue(repo.resetPractice("a") is MaintenanceOutcome.Failed)
        assertTrue(repo.endRound("a", "session-a") is MaintenanceOutcome.Failed)
        assertFalse(cleaned)
        assertNotNull(s.libraries.libraries["a"])
        assertNotNull(s.practice.sessions["session-a"])
    }

    @Test fun cleanupFailureReportsCommittedDeletionRatherThanFalseRollback() = runBlocking {
        val s = fixture()
        val result = LibraryMaintenanceRepository(s, deletePdf = { error("disk failure") }).deleteLibrary("a")
        assertTrue(result is MaintenanceOutcome.Done)
        assertTrue((result as MaintenanceOutcome.Done).message.contains("稍后自动清理"))
        assertNull(s.libraries.libraries["a"])
        assertNotNull(s.libraries.libraries["b"])
    }

    @Test fun foreignPdfPathCannotDeleteAnotherLibraryOrEscapeDirectory() = runBlocking {
        for (path in listOf("libraries/b/source.pdf", "../source.pdf")) {
            val s = fixture()
            s.libraries.libraries["a"] = s.libraries.libraries.getValue("a").copy(pdfRelativePath = path)
            var cleaned = false
            assertTrue(LibraryMaintenanceRepository(s, deletePdf = { cleaned = true }).deleteLibrary("a") is MaintenanceOutcome.Done)
            assertFalse(cleaned)
            assertNotNull(s.libraries.libraries["b"])
        }
    }

    @Test fun confirmedCommitFinishesAfterCallerCancellation() = runBlocking {
        val s = fixture()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val delayed = object : LibraryMaintenanceStore by s {
            override suspend fun deleteLibrary(libraryId: String): DeletedLibrary? {
                entered.complete(Unit)
                gate.await()
                return s.deleteLibrary(libraryId)
            }
        }
        var cleaned = false
        val repo = LibraryMaintenanceRepository(delayed, deletePdf = { cleaned = true })
        val job = launch { repo.deleteLibrary("a") }
        entered.await()
        job.cancel()
        gate.complete(Unit)
        job.join()
        assertNull(s.libraries.libraries["a"])
        assertTrue(cleaned)
        assertNotNull(s.libraries.libraries["b"])
    }

    @Test fun concurrentDeleteRequestsCleanTargetOnce() = runBlocking {
        val s = fixture()
        var cleaned = 0
        val repo = LibraryMaintenanceRepository(s, deletePdf = { cleaned++ })
        val first = async { repo.deleteLibrary("a") }
        val second = async { repo.deleteLibrary("a") }
        val results = listOf(first.await(), second.await())
        assertEquals(1, results.count { it is MaintenanceOutcome.Done })
        assertEquals(1, results.count { it is MaintenanceOutcome.Failed })
        assertEquals(1, cleaned)
    }
}
