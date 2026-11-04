package com.offlinequiz.app

import com.offlinequiz.app.model.HistorySnapshotView
import com.offlinequiz.app.model.PracticeAnswerKinds
import com.offlinequiz.app.model.PracticeResults
import com.offlinequiz.app.model.PracticeUserAnswer
import com.offlinequiz.app.model.QuestionHistoryRecord
import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.model.QuizOption
import com.offlinequiz.app.model.StoredQuestion
import com.offlinequiz.app.model.toPracticeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The history card renders this presentation object, which is built only from the stored
 * reference snapshot. These tests pin that the old stem/options/reference survive an edit of the
 * current question, and that a missing snapshot is reported without inventing content.
 */
class PracticeHistorySnapshotViewTest {

    private fun record(
        snapshot: StoredQuestion?,
        userAnswer: PracticeUserAnswer = PracticeUserAnswer(selectedKeys = listOf("A")),
        kind: String = PracticeAnswerKinds.AUTOMATIC,
        result: String = PracticeResults.CORRECT,
        currentVersion: Boolean = true
    ): QuestionHistoryRecord = QuestionHistoryRecord(
        answer = practiceAnswer(
            id = "answer-1",
            question = snapshot ?: practiceQuestion("q1"),
            kind = kind,
            result = result,
            submittedAt = 100L,
            userAnswer = userAnswer
        ),
        referenceSnapshot = snapshot?.toPracticeSnapshot(),
        currentContentVersion = currentVersion
    )

    private fun options(vararg pairs: Pair<String, String>): List<QuizOption> =
        pairs.mapIndexed { index, pair -> QuizOption(pair.first, pair.second, markerIndex = index) }

    @Test
    fun editedStemAndOptionsStillShowTheSnapshotContent() {
        val current = practiceQuestion(
            "q1",
            stem = "新题干",
            options = options("A" to "新甲", "B" to "新乙")
        )
        val old = practiceQuestion(
            "q1",
            stem = "旧题干",
            options = options("A" to "甲", "B" to "乙")
        )

        val view = HistorySnapshotView.of(record(old, currentVersion = false))

        assertTrue(view.available)
        assertEquals("旧题干", view.stem)
        assertEquals(listOf("A" to "甲", "B" to "乙"), view.options.map { it.key to it.text })
        assertEquals("单选", view.typeLabel)
        assertEquals("A", view.referenceAnswer)
        assertEquals("A", view.userAnswer)
        assertNotEquals("the old record must not borrow the edited stem", current.stem, view.stem)
    }

    @Test
    fun changedOptionTextKeepsTheOldOptionMeaning() {
        val old = practiceQuestion(
            "q1",
            stem = "同一题干",
            options = options("A" to "旧甲", "B" to "旧乙")
        )
        val current = practiceQuestion(
            "q1",
            stem = "同一题干",
            options = options("A" to "改后甲", "B" to "改后乙")
        )

        val view = HistorySnapshotView.of(record(old, currentVersion = false))

        assertEquals("同一题干", view.stem)
        assertEquals("旧甲", view.options.first { it.key == "A" }.text)
        assertEquals("旧乙", view.options.first { it.key == "B" }.text)
        assertNotEquals(current.options.first().text, view.options.first().text)
    }

    @Test
    fun fillBlankSnapshotShowsEachBlankAndAcceptableAnswer() {
        val fill = practiceQuestion(
            "f1",
            type = QuestionTypes.FILL_BLANK,
            stem = "填空____和____",
            options = emptyList(),
            answers = listOf("甲", "乙"),
            acceptableAnswers = listOf(listOf("A"), listOf("B"))
        )

        val view = HistorySnapshotView.of(
            record(fill, userAnswer = PracticeUserAnswer(blankAnswers = listOf("甲", "乙")))
        )

        assertTrue(view.referenceAnswer.contains("第 1 空：甲（可接受：A）"))
        assertTrue(view.referenceAnswer.contains("第 2 空：乙（可接受：B）"))
        assertEquals("第 1 空：甲；第 2 空：乙", view.userAnswer)
        assertTrue(view.options.isEmpty())
    }

    @Test
    fun shortAnswerSnapshotShowsReferenceContent() {
        val short = practiceQuestion(
            "s1",
            type = QuestionTypes.SHORT_ANSWER,
            stem = "简答题干",
            options = emptyList(),
            answers = emptyList(),
            referenceAnswer = "参考答案内容"
        )

        val view = HistorySnapshotView.of(
            record(short, userAnswer = PracticeUserAnswer(text = "我的回答"))
        )

        assertEquals("参考答案内容", view.referenceAnswer)
        assertEquals("我的回答", view.userAnswer)
        assertEquals("简答", view.typeLabel)
    }

    @Test
    fun trueFalseSnapshotShowsTypeAndNumber() {
        val question = practiceQuestion(
            "t7",
            type = QuestionTypes.TRUE_FALSE,
            stem = "判断题干",
            options = emptyList(),
            answers = listOf("√")
        )

        val view = HistorySnapshotView.of(
            record(question, userAnswer = PracticeUserAnswer(selectedKeys = listOf("√")))
        )

        assertEquals("判断", view.typeLabel)
        assertEquals("1", view.originalNumber)
        assertEquals("正确（√）", view.userAnswer)
        assertTrue(view.referenceAnswer.contains("正确"))
    }

    @Test
    fun missingSnapshotKeepsRecordedAnswerAndReportsUnavailable() {
        val view = HistorySnapshotView.of(
            record(
                snapshot = null,
                userAnswer = PracticeUserAnswer(selectedKeys = listOf("B")),
                currentVersion = false
            )
        )

        assertFalse(view.available)
        assertEquals("", view.stem)
        assertTrue(view.options.isEmpty())
        assertEquals("", view.referenceAnswer)
        assertEquals("B", view.userAnswer)
        assertTrue(HistorySnapshotView.UNAVAILABLE_MESSAGE.contains("当时题目快照不可用"))
    }
}
