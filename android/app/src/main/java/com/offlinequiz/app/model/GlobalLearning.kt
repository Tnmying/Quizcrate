package com.offlinequiz.app.model

/** Weight by answers, never average per-library percentages. */
fun globalPracticeTotals(stats: Collection<PracticeLibraryStats>): PracticeTotals = PracticeTotals(
    correct = stats.sumOf { it.totals.correct }, incorrect = stats.sumOf { it.totals.incorrect }
)

fun latestUnfinishedPractice(sessions: Collection<PracticeSessionSummary>): PracticeSessionSummary? =
    sessions.filter { it.status == PracticeStatuses.ACTIVE && it.phase != PracticePhases.COMPLETED }
        .maxWithOrNull(compareBy<PracticeSessionSummary> { it.updatedAt }.thenBy { it.id })
