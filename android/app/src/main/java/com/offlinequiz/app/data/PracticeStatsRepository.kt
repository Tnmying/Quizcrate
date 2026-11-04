package com.offlinequiz.app.data

import com.offlinequiz.app.model.PracticeLibraryReport
import com.offlinequiz.app.model.PracticeQuestionHistory
import com.offlinequiz.app.model.PracticeStatsCalculator

/**
 * Read-only statistics over the persisted practice answers.
 *
 * Nothing is cached in its own table: every report is rebuilt from `practice_answers` plus the
 * current questions, so a counter can never drift from history. Callers are expected to run this
 * away from the main thread; the result is then held by the screen's ViewModel instead of being
 * recomputed during recomposition.
 */
class PracticeStatsRepository(
    private val libraryStore: LibraryStore,
    private val practiceStore: PracticeStore
) {

    suspend fun libraryReport(libraryId: String): PracticeLibraryReport {
        val questions = libraryStore.questions(libraryId)
        val snapshot = practiceStore.libraryStatsSnapshot(libraryId)
        return PracticeStatsCalculator.report(questions, snapshot.history, snapshot.rounds)
    }

    suspend fun questionHistory(libraryId: String, questionId: String): PracticeQuestionHistory {
        // A question id from another library must never leak into this library's history.
        val question = libraryStore.question(questionId)?.takeIf { it.libraryId == libraryId }
        val history = practiceStore.historyForQuestion(libraryId, questionId)
        return PracticeStatsCalculator.questionHistory(question, history)
    }
}
