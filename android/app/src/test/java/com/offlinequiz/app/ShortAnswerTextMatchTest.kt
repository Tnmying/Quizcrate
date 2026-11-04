package com.offlinequiz.app

import com.offlinequiz.app.model.ShortAnswerMatchUnavailable
import com.offlinequiz.app.model.ShortAnswerTextMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Hand-computable checks for the display-only 2/3-gram multiset Dice. */
class ShortAnswerTextMatchTest {

    private val epsilon = 1e-9

    private fun normalized(text: String): String {
        val points = ShortAnswerTextMatch.normalizeToCodePoints(text)
        val builder = StringBuilder()
        for (cp in points) builder.appendCodePoint(cp)
        return builder.toString()
    }

    private fun assertClose(expected: Double, actual: Double?, message: String) {
        assertNotNull(message, actual)
        assertTrue("$message expected=$expected actual=$actual", abs(expected - actual!!) <= 1e-9)
    }

    @Test
    fun identicalTextScoresOneHundred() {
        val result = ShortAnswerTextMatch.match("光合作用需要光照", "光合作用需要光照")
        assertTrue(result.available)
        assertEquals(100, result.percent)
        assertClose(1.0, result.score, "identical")
        assertClose(1.0, result.order2Dice, "order2")
        assertClose(1.0, result.order3Dice, "order3")
        assertFalse(result.singleCharacterFallback)
        assertFalse(result.truncated)
        assertNull(result.unavailableReason)
    }

    @Test
    fun handComputedPartialOverlapUsesBothOrders() {
        // 参考 甲乙丙, 输入 甲乙
        // order2: A={甲乙,乙丙} B={甲乙} overlap 1 -> 2/3
        // order3: A={甲乙丙} B=none -> 0 (one side insufficient, counted as 0)
        val result = ShortAnswerTextMatch.match("甲乙", "甲乙丙")
        assertTrue(result.available)
        assertClose(2.0 / 3.0, result.order2Dice, "order2")
        assertClose(0.0, result.order3Dice, "order3")
        assertClose((2.0 / 3.0) / 2.0, result.score, "average")
        assertEquals(33, result.percent)
    }

    @Test
    fun disjointTextScoresZeroAndSkipsUnavailableOrder() {
        val result = ShortAnswerTextMatch.match("丙丁", "甲乙")
        assertTrue(result.available)
        assertClose(0.0, result.order2Dice, "order2")
        assertNull("both sides have no 3-grams", result.order3Dice)
        assertClose(0.0, result.score, "score")
        assertEquals(0, result.percent)
    }

    @Test
    fun multisetCountsUseMinimumNotSetIntersection() {
        // 参考 甲甲甲, 输入 甲甲
        // order2: A={甲甲:2} B={甲甲:1} overlap 1 -> 2/(2+1)=2/3
        // order3: A={甲甲甲:1} B=none -> 0
        val result = ShortAnswerTextMatch.match("甲甲", "甲甲甲")
        assertClose(2.0 / 3.0, result.order2Dice, "order2")
        assertClose(0.0, result.order3Dice, "order3")
        assertClose(1.0 / 3.0, result.score, "score")
        assertEquals(33, result.percent)
    }

    @Test
    fun repeatedPaddingDoesNotReachFullScore() {
        // 参考 甲乙丙丁, 输入 甲乙丙丁甲乙丙丁
        // order2: A 3 grams, B 7 grams, overlap 3 -> 6/10 = 0.6
        // order3: A 2 grams, B 6 grams, overlap 2 -> 4/8 = 0.5
        val result = ShortAnswerTextMatch.match("甲乙丙丁甲乙丙丁", "甲乙丙丁")
        assertClose(0.6, result.order2Dice, "order2")
        assertClose(0.5, result.order3Dice, "order3")
        assertClose(0.55, result.score, "score")
        assertEquals(55, result.percent)
        assertTrue(result.score < 1.0)
    }

    @Test
    fun oneSideTooShortForAnOrderScoresThatOrderZeroInsteadOfSkippingIt() {
        // 参考 甲乙 (2), 输入 甲乙丙丁戊 (5)
        // order2: A=1 B=4 overlap 1 -> 2/5 = 0.4
        // order3: A=0 B=3 -> 0 (applicable, not null)
        val result = ShortAnswerTextMatch.match("甲乙丙丁戊", "甲乙")
        assertClose(0.4, result.order2Dice, "order2")
        assertClose(0.0, result.order3Dice, "order3")
        assertClose(0.2, result.score, "score")
    }

    @Test
    fun normalizationFoldsWidthCaseAndLayoutPunctuation() {
        assertEquals("abc", normalized("Ａ Ｂ Ｃ"))
        assertEquals("abc", normalized("A，B。C"))
        assertEquals("甲乙丙", normalized("甲、乙。丙"))
        // Math/comparison symbols and negation are preserved.
        assertEquals("a+b>c", normalized("a + b > c"))
        assertEquals("a-b=5%", normalized("A-B=5%"))
        assertEquals("不需要", normalized("不需 要。"))
        assertTrue(normalized("至少3个").contains("至少3个"))
        assertEquals(
            100,
            ShortAnswerTextMatch.match("abc", "Ａ Ｂ Ｃ").percent
        )
    }

    @Test
    fun surrogatePairsAreNeverSplit() {
        // U+20000/U+20001 are outside the BMP and each uses a surrogate pair in UTF-16.
        val text = "\uD840\uDC00\uD840\uDC01"
        val result = ShortAnswerTextMatch.match(text, text)
        assertEquals(100, result.percent)
        assertEquals(2, ShortAnswerTextMatch.normalizeToCodePoints(text).size)

        val oneChar = ShortAnswerTextMatch.match("\uD840\uDC00", text)
        assertTrue(oneChar.available)
        assertClose(0.0, oneChar.score, "one code point vs two")
    }

    @Test
    fun bothSingleCodePointsUseTheDocumentedFallback() {
        val equal = ShortAnswerTextMatch.match("甲", "甲")
        assertTrue(equal.singleCharacterFallback)
        assertEquals(100, equal.percent)
        assertNull(equal.order2Dice)

        val different = ShortAnswerTextMatch.match("甲", "乙")
        assertTrue(different.singleCharacterFallback)
        assertEquals(0, different.percent)
    }

    @Test
    fun emptyInputsReportReasonsInsteadOfZeroOrHundred() {
        val noUser = ShortAnswerTextMatch.match("", "参考")
        assertFalse(noUser.available)
        assertEquals(ShortAnswerMatchUnavailable.USER_TEXT_EMPTY, noUser.unavailableReason)

        val blankUser = ShortAnswerTextMatch.match("   ", "参考")
        assertEquals(ShortAnswerMatchUnavailable.USER_TEXT_EMPTY, blankUser.unavailableReason)

        val noReference = ShortAnswerTextMatch.match("输入", null)
        assertFalse(noReference.available)
        assertEquals(ShortAnswerMatchUnavailable.REFERENCE_EMPTY, noReference.unavailableReason)

        val blankReference = ShortAnswerTextMatch.match("输入", "  ")
        assertEquals(ShortAnswerMatchUnavailable.REFERENCE_EMPTY, blankReference.unavailableReason)

        val punctuationOnlyUser = ShortAnswerTextMatch.match("，。", "参考")
        assertEquals(
            ShortAnswerMatchUnavailable.NORMALIZED_USER_EMPTY,
            punctuationOnlyUser.unavailableReason
        )

        val punctuationOnlyReference = ShortAnswerTextMatch.match("输入", "，。")
        assertEquals(
            ShortAnswerMatchUnavailable.NORMALIZED_REFERENCE_EMPTY,
            punctuationOnlyReference.unavailableReason
        )
    }

    @Test
    fun longTextIsCappedWithAnExplicitFlag() {
        val longUser = "甲".repeat(2500)
        val longReference = "甲".repeat(2500)
        val result = ShortAnswerTextMatch.match(longUser, longReference)
        assertTrue(result.truncated)
        assertEquals(100, result.percent)

        val short = ShortAnswerTextMatch.match("甲", "甲")
        assertFalse(short.truncated)
    }

    @Test
    fun scoreIsAlwaysFinite() {
        val cases = listOf(
            "" to "",
            "甲" to "甲",
            "甲乙" to "丙丁",
            "甲甲甲" to "甲甲",
            "a+b>c" to "a-b=5%",
            "，。" to "。。"
        )
        for ((user, reference) in cases) {
            val result = ShortAnswerTextMatch.match(user, reference)
            assertFalse(result.score.isNaN())
            assertTrue(result.score.isInfinite().not())
            assertTrue(result.percent in 0..100)
        }
    }

    @Test
    fun numericSeparatorsBetweenDigitsArePreserved() {
        // The 03C acceptance defect: deleting '.'/':' turned 1.5 into 15 and 1:2 into 12.
        val decimal = ShortAnswerTextMatch.match("温度为15摄氏度", "温度为1.5摄氏度")
        assertTrue("1.5 and 15 must not normalize to the same text", decimal.score < 1.0)
        val ratio = ShortAnswerTextMatch.match("比例为12", "比例为1:2")
        assertTrue("1:2 and 12 must not normalize to the same text", ratio.score < 1.0)

        assertEquals(100, ShortAnswerTextMatch.match("1.5", "1.5").percent)
        assertEquals(100, ShortAnswerTextMatch.match("1:2", "1:2").percent)
        assertEquals("1.5", normalized("1.5"))
        assertEquals("15", normalized("15"))
        assertEquals("1:2", normalized("1:2"))
    }

    @Test
    fun fullWidthNumericSeparatorsAreEquivalent() {
        assertEquals("1.5", normalized("1．5"))
        assertEquals("1:2", normalized("1：2"))
        assertEquals("1,000", normalized("1，000"))
        assertEquals(100, ShortAnswerTextMatch.match("1．5", "1.5").percent)
        assertEquals(100, ShortAnswerTextMatch.match("1：2", "1:2").percent)
        assertEquals(100, ShortAnswerTextMatch.match("1，000", "1,000").percent)
    }

    @Test
    fun ordinarySentencePunctuationIsStillRemoved() {
        assertEquals(100, ShortAnswerTextMatch.match("甲，乙。丙", "甲乙丙").percent)
        // A trailing full stop after a digit is sentence punctuation, not a decimal point.
        assertEquals(100, ShortAnswerTextMatch.match("答案是1", "答案是1.").percent)
        assertEquals("答案1.5", normalized("答案：1.5"))
    }

    @Test
    fun decimalAndThousandsSeparatorsAreNotCollapsed() {
        assertTrue(ShortAnswerTextMatch.match("0.5", "05").score < 1.0)
        assertTrue(ShortAnswerTextMatch.match("1,000", "1000").score < 1.0)
        assertEquals("1,000", normalized("1,000"))
        assertEquals("1000", normalized("1000"))
    }

    @Test
    fun punctuationOnlyLongTextReportsTruncatedEmptyState() {
        val short = ShortAnswerTextMatch.match("，".repeat(100), "参考")
        assertFalse(short.available)
        assertEquals(ShortAnswerMatchUnavailable.NORMALIZED_USER_EMPTY, short.unavailableReason)
        assertFalse("all 100 code points were examined", short.truncated)

        val long = ShortAnswerTextMatch.match("，".repeat(10000), "参考")
        assertFalse(long.available)
        assertEquals(ShortAnswerMatchUnavailable.NORMALIZED_USER_EMPTY, long.unavailableReason)
        assertTrue("only a bounded prefix was examined", long.truncated)
    }

    @Test
    fun differencesAfterTheScannedPrefixAreNotSeenButFlagged() {
        val user = "甲".repeat(9000)
        val reference = "甲".repeat(8000) + "乙".repeat(1000)
        val result = ShortAnswerTextMatch.match(user, reference)
        assertTrue(result.truncated)
        assertEquals("documented boundary: only the prefix is compared", 100, result.percent)
    }

    @Test
    fun lowercaseExpansionAtTheBoundaryIsHandledWithoutFailure() {
        val text = "İ".repeat(4000)
        val result = ShortAnswerTextMatch.match(text, text)
        assertTrue(result.truncated)
        assertEquals(100, result.percent)
        assertTrue(ShortAnswerTextMatch.normalizeToCodePoints("İ".repeat(10)).size >= 10)
    }

    @Test
    fun astralCodePointsStayIntactAndTheScanBoundIsBounded() {
        // A surrogate pair inside the kept range must remain one code point.
        val keptAstral = "a".repeat(1500) + "\uD840\uDC00" + "b"
        val points = ShortAnswerTextMatch.normalizeToCodePoints(keptAstral)
        assertEquals(1502, points.size)
        assertEquals(0x20000, points[1500])

        // Far beyond both bounds: preprocessing/keeping is capped, no split, truncation flagged.
        val huge = "a".repeat(9000) + "\uD840\uDC00"
        val result = ShortAnswerTextMatch.match(huge, huge)
        assertTrue(result.truncated)
        assertEquals(100, result.percent)
        assertEquals(ShortAnswerTextMatch.MAX_CODE_POINTS, ShortAnswerTextMatch.normalizeToCodePoints(huge).size)
    }
}
