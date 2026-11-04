package com.offlinequiz.app

import com.offlinequiz.app.model.ShortAnswerTextMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Produces the self-made evaluation table used by docs/design/short-answer-match.md and pins the
 * known limitations (negation / number / ordering can score high). No real question-bank text.
 */
class ShortAnswerMatchEvaluationTest {

    private data class Case(val label: String, val reference: String, val user: String)

    private val cases = listOf(
        Case(
            "原文",
            "绿色植物在光照下利用水和二氧化碳合成有机物并释放氧气",
            "绿色植物在光照下利用水和二氧化碳合成有机物并释放氧气"
        ),
        Case(
            "同义改写",
            "绿色植物在光照下利用水和二氧化碳合成有机物并释放氧气",
            "植物借助光能，把水和二氧化碳转化为有机物，同时放出氧气"
        ),
        Case(
            "漏答",
            "绿色植物在光照下利用水和二氧化碳合成有机物并释放氧气",
            "绿色植物在光照下利用水合成有机物"
        ),
        Case(
            "反义/否定",
            "需要光照",
            "不需要光照"
        ),
        Case(
            "数字变化",
            "温度低于10摄氏度时停止生长",
            "温度低于30摄氏度时停止生长"
        ),
        Case(
            "条件变化",
            "温度至少需要十摄氏度",
            "温度至多需要十摄氏度"
        ),
        Case(
            "乱序",
            "甲在乙之前完成",
            "乙在甲之前完成"
        ),
        Case(
            "堆字",
            "甲乙丙丁",
            "甲乙丙丁甲乙丙丁"
        ),
        Case(
            "无关答案",
            "绿色植物在光照下利用水和二氧化碳合成有机物并释放氧气",
            "今天天气很好，适合出门散步"
        )
    )

    @Test
    fun evaluationTableIsStableAndDocumentsKnownLimitations() {
        val results = cases.map { case ->
            val result = ShortAnswerTextMatch.match(case.user, case.reference)
            assertTrue("${case.label} must be computable", result.available)
            assertTrue(result.percent in 0..100)
            println(
                "EVAL case=${case.label} percent=${result.percent} " +
                    "order2=${format(result.order2Dice)} order3=${format(result.order3Dice)}"
            )
            case.label to result
        }.toMap()

        assertEquals(100, results.getValue("原文").percent)
        assertTrue(
            "unrelated text must stay low, was ${results.getValue("无关答案").percent}",
            results.getValue("无关答案").percent < 30
        )
        assertTrue(
            "repeated padding must not reach full score, was ${results.getValue("堆字").percent}",
            results.getValue("堆字").percent < 100
        )
        // Known limitations: these are intentionally asserted so a future algorithm change is visible.
        assertTrue(
            "negation must remain a documented high-score limitation, was ${results.getValue("反义/否定").percent}",
            results.getValue("反义/否定").percent > 50
        )
        assertTrue(
            "number change must remain a documented high-score limitation, was ${results.getValue("数字变化").percent}",
            results.getValue("数字变化").percent > 50
        )
        assertTrue(
            "reordering is a mid-score case rather than a semantic check, was ${results.getValue("乱序").percent}",
            results.getValue("乱序").percent in 30..70
        )
    }

    private fun format(value: Double?): String = value?.let { String.format(java.util.Locale.ROOT, "%.4f", it) } ?: "null"
}
