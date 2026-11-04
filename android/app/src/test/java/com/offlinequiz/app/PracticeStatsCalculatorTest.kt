package com.offlinequiz.app

import com.offlinequiz.app.model.PracticeAnswerKinds
import com.offlinequiz.app.model.PracticeContentVersion
import com.offlinequiz.app.model.PracticeHistoryText
import com.offlinequiz.app.model.PracticeLibraryReport
import com.offlinequiz.app.model.PracticeMastery
import com.offlinequiz.app.model.PracticeQuestionStats
import com.offlinequiz.app.model.PracticeResults
import com.offlinequiz.app.model.PracticeStatsCalculator
import com.offlinequiz.app.model.PracticeUserAnswer
import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.model.StoredQuestion
import com.offlinequiz.app.model.toPracticeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules regression for the 03B statistics/wrongbook aggregation. The calculator is driven directly
 * with explicit history rows, so these tests pin the semantics without depending on Room.
 */
class PracticeStatsCalculatorTest {

    private fun report(
        questions: List<StoredQuestion>,
        history: List<com.offlinequiz.app.model.PracticeHistoryEntry>
    ): PracticeLibraryReport = PracticeStatsCalculator.report(questions, history)

    private fun stats(report: PracticeLibraryReport): PracticeQuestionStats = report.questions.single()

    @Test
    fun wrongThenCorrectKeepsHistoryAndUpdatesCurrentState() {
        val q = practiceQuestion("q1")
        val result = report(
            listOf(q),
            listOf(automaticIncorrect(q, 100), automaticCorrect(q, 200))
        )

        val item = stats(result)
        assertEquals(2, item.practiceCount)
        assertEquals(1, item.automaticCorrect)
        assertEquals(1, item.automaticIncorrect)
        assertEquals(200L, item.lastPracticeAt)
        assertTrue("historical errors must remain", item.historicalWrong)
        assertTrue(item.mastery is PracticeMastery.Passed)
        assertEquals(PracticeAnswerKinds.AUTOMATIC, (item.mastery as PracticeMastery.Passed).kind)
        assertEquals("correct answer must not delete the wrongbook entry", 1, result.wrongbook.size)
        assertTrue("a passed question is not due", result.due.isEmpty())

        val overview = result.overview
        assertEquals(2, overview.practiceCount)
        assertEquals(1, overview.automaticCorrect)
        assertEquals(1, overview.automaticIncorrect)
        assertEquals(50, overview.automaticAccuracyPercent)
        assertEquals(1, overview.wrongQuestionCount)
        assertEquals(0, overview.dueQuestionCount)
        assertEquals(200L, overview.lastPracticeAt)
    }

    @Test
    fun selfAssessmentIsReportedSeparatelyFromAutomaticAccuracy() {
        val q = practiceQuestion("q1")
        val result = report(
            listOf(q),
            listOf(automaticCorrect(q, 100), selfForgotten(q, 200))
        )

        val item = stats(result)
        assertEquals(1, item.automaticTotal)
        assertEquals(1, item.selfTotal)
        assertEquals(2, item.practiceCount)
        assertEquals(100, result.overview.automaticAccuracyPercent)
        assertEquals(1, result.overview.forgotten)
        assertEquals(0, result.overview.remembered)
        assertTrue(item.mastery is PracticeMastery.Due)
        assertEquals(PracticeAnswerKinds.SELF_ASSESSMENT, (item.mastery as PracticeMastery.Due).kind)
        assertTrue(result.due.any { it.questionId == "q1" })
    }

    @Test
    fun zeroAutomaticAnswersShowsNoAccuracyInsteadOfZeroPercent() {
        val q = practiceQuestion("q1")
        val onlySelf = report(listOf(q), listOf(selfRemembered(q, 100)))
        assertNull(onlySelf.overview.automaticAccuracyPercent)
        assertEquals("暂无自动作答", onlySelf.overview.automaticAccuracyLabel)
        assertEquals(1, onlySelf.overview.selfTotal)

        val empty = report(listOf(q), emptyList())
        assertEquals(0, empty.overview.practiceCount)
        assertNull(empty.overview.automaticAccuracyPercent)
        assertEquals("暂无自动作答", empty.overview.automaticAccuracyLabel)
        assertTrue(empty.questions.isEmpty())
    }

    @Test
    fun skipDoesNotChangeStateAccuracyOrLastPracticeTime() {
        val q = practiceQuestion("q1")
        val result = report(
            listOf(q),
            listOf(automaticIncorrect(q, 100), skipped(q, 200))
        )

        val item = stats(result)
        assertEquals(1, item.skipped)
        assertEquals(1, item.practiceCount)
        assertEquals("skip must not become the last practice time", 100L, item.lastPracticeAt)
        assertEquals(100L, result.overview.lastPracticeAt)
        assertEquals(1, result.overview.skipped)
        assertEquals(0, result.overview.automaticAccuracyPercent)
        assertTrue(item.mastery is PracticeMastery.Due)
        assertTrue(result.due.any { it.questionId == "q1" })
    }

    @Test
    fun skipOnlyQuestionIsCountedAsRecordButNotPracticedOrWrong() {
        val q = practiceQuestion("q1")
        val result = report(listOf(q), listOf(skipped(q, 300)))

        val item = stats(result)
        assertEquals(0, item.practiceCount)
        assertEquals(1, item.skipped)
        assertNull(item.lastPracticeAt)
        assertFalse(item.historicalWrong)
        assertTrue(item.mastery is PracticeMastery.NotPracticed)
        assertTrue(result.wrongbook.isEmpty())
        assertEquals(1, result.overview.questionCount)
        assertEquals(0, result.overview.practiceCount)
        assertEquals(1, result.overview.skipped)
    }

    @Test
    fun editedQuestionCannotBePassedByAnOldVersionCorrectAnswer() {
        val original = practiceQuestion("q1", stem = "旧题干")
        val edited = practiceQuestion("q1", stem = "新题干")
        val oldWrong = automaticIncorrect(original, 100)
        val oldCorrect = automaticCorrect(original, 100)

        val wrongBeforeEdit = stats(report(listOf(edited), listOf(oldWrong)))
        assertEquals(PracticeMastery.DueAfterEdit, wrongBeforeEdit.mastery)
        assertTrue(wrongBeforeEdit.contentChanged)
        assertTrue(wrongBeforeEdit.historicalWrong)
        assertTrue("edited due question stays in the due list", report(listOf(edited), listOf(oldWrong)).due.any { it.questionId == "q1" })

        val correctBeforeEdit = stats(report(listOf(edited), listOf(oldCorrect)))
        assertEquals(
            "an old-version correct answer must not prove the new content is mastered",
            PracticeMastery.NotPracticed,
            correctBeforeEdit.mastery
        )
        assertTrue(correctBeforeEdit.contentChanged)
        assertFalse(correctBeforeEdit.historicalWrong)

        val newCorrect = automaticCorrect(edited, 300, id = "new-correct")
        val afterRetry = stats(report(listOf(edited), listOf(oldWrong, newCorrect)))
        assertTrue(afterRetry.mastery is PracticeMastery.Passed)
        assertTrue("history is never deleted by a later correct answer", afterRetry.historicalWrong)
        assertEquals(1, afterRetry.automaticIncorrect)
        assertEquals(1, afterRetry.automaticCorrect)
    }

    @Test
    fun lastAnswerOnCurrentVersionDecidesAndTiesAreDeterministic() {
        val q = practiceQuestion("q1")
        val correctFirst = historyEntry(
            q, PracticeAnswerKinds.AUTOMATIC, PracticeResults.CORRECT, 100, id = "a"
        )
        val wrongSecond = historyEntry(
            q, PracticeAnswerKinds.AUTOMATIC, PracticeResults.INCORRECT, 100, id = "b"
        )
        // Same timestamp: the higher answer id is defined as the later record.
        assertTrue(stats(report(listOf(q), listOf(correctFirst, wrongSecond))).mastery is PracticeMastery.Due)

        val wrongFirst = historyEntry(
            q, PracticeAnswerKinds.AUTOMATIC, PracticeResults.INCORRECT, 100, id = "b"
        )
        val correctSecond = historyEntry(
            q, PracticeAnswerKinds.AUTOMATIC, PracticeResults.CORRECT, 100, id = "c"
        )
        assertTrue(stats(report(listOf(q), listOf(wrongFirst, correctSecond))).mastery is PracticeMastery.Passed)
    }

    @Test
    fun contentVersionIgnoresNumberAndPositionButNotJudgingFields() {
        val base = practiceQuestion("q1", position = 0)
        val moved = base.copy(position = 7, originalNumber = "99")
        assertEquals(
            PracticeContentVersion.canonical(base.toPracticeSnapshot()),
            PracticeContentVersion.canonical(moved.toPracticeSnapshot())
        )

        assertNotEquals(
            PracticeContentVersion.canonical(base.toPracticeSnapshot()),
            PracticeContentVersion.canonical(base.copy(stem = "改了题干").toPracticeSnapshot())
        )
        assertNotEquals(
            PracticeContentVersion.canonical(base.toPracticeSnapshot()),
            PracticeContentVersion.canonical(base.copy(type = QuestionTypes.MULTIPLE_CHOICE).toPracticeSnapshot())
        )
        assertNotEquals(
            PracticeContentVersion.canonical(base.toPracticeSnapshot()),
            PracticeContentVersion.canonical(base.copy(answers = listOf("B")).toPracticeSnapshot())
        )

        val fill = practiceQuestion(
            "f1",
            type = QuestionTypes.FILL_BLANK,
            options = emptyList(),
            answers = listOf("甲"),
            acceptableAnswers = listOf(listOf("A"))
        )
        val fillWithoutAcceptable = fill.copy(acceptableAnswers = listOf(emptyList()))
        assertNotEquals(
            PracticeContentVersion.canonical(fill.toPracticeSnapshot()),
            PracticeContentVersion.canonical(fillWithoutAcceptable.toPracticeSnapshot())
        )
    }

    @Test
    fun questionHistoryIsNewestFirstAndFlagsOldVersions() {
        val edited = practiceQuestion("q1", stem = "新题干")
        val oldSnapshotQuestion = practiceQuestion("q1", stem = "旧题干")
        val history = PracticeStatsCalculator.questionHistory(
            edited,
            listOf(
                historyEntry(
                    oldSnapshotQuestion, PracticeAnswerKinds.AUTOMATIC, PracticeResults.CORRECT, 100, id = "old"
                ),
                historyEntry(edited, PracticeAnswerKinds.AUTOMATIC, PracticeResults.INCORRECT, 300, id = "new")
            )
        )

        assertEquals(2, history.records.size)
        assertEquals("new", history.records[0].answer.id)
        assertTrue(history.records[0].currentContentVersion)
        assertEquals("old", history.records[1].answer.id)
        assertFalse(history.records[1].currentContentVersion)
        assertTrue(history.stats!!.contentChanged)
    }

    @Test
    fun historyTextShowsKindsAndSnapshotAnswers() {
        assertEquals("跳过", PracticeHistoryText.kindLabel(PracticeAnswerKinds.SKIPPED, PracticeResults.SKIPPED))
        assertTrue(
            PracticeHistoryText.kindLabel(PracticeAnswerKinds.AUTOMATIC, PracticeResults.CORRECT)
                .contains("自动判题")
        )
        assertEquals(
            "未作答",
            PracticeHistoryText.userAnswer(null, PracticeUserAnswer())
        )

        val fill = practiceQuestion(
            "f1",
            type = QuestionTypes.FILL_BLANK,
            options = emptyList(),
            answers = listOf("甲"),
            acceptableAnswers = listOf(listOf("A", "B"))
        )
        val snapshot = fill.toPracticeSnapshot()
        assertEquals(
            "第 1 空：甲",
            PracticeHistoryText.userAnswer(snapshot, PracticeUserAnswer(blankAnswers = listOf("甲")))
        )
        assertEquals(
            "第 1 空：甲（可接受：A；B）",
            PracticeHistoryText.referenceAnswer(snapshot)
        )
    }

    @Test
    fun overviewCountsDistinctQuestionsWithAnyRecord() {
        val q1 = practiceQuestion("q1", position = 0)
        val q2 = practiceQuestion("q2", position = 1)
        val result = report(
            listOf(q1, q2),
            listOf(
                automaticCorrect(q1, 100),
                skipped(q1, 110),
                automaticIncorrect(q2, 200)
            )
        )
        assertEquals(2, result.overview.questionCount)
        assertEquals(2, result.overview.practiceCount)
        assertEquals(1, result.overview.skipped)
        assertEquals(1, result.overview.wrongQuestionCount)
    }
}
