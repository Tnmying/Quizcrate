package com.offlinequiz.app.model

import java.util.Locale
import kotlin.math.roundToInt

/** Why a short-answer text match is not available (used for the Chinese empty states). */
enum class ShortAnswerMatchUnavailable {
    USER_TEXT_EMPTY,
    REFERENCE_EMPTY,
    NORMALIZED_USER_EMPTY,
    NORMALIZED_REFERENCE_EMPTY
}

/**
 * Display-only character overlap result. This is **not** a correctness judgement: no threshold,
 * never feeds self-assessment, statistics, wrongbook or mastery.
 */
data class ShortAnswerMatchResult(
    val available: Boolean,
    val score: Double,
    val percent: Int,
    /** Per-order Dice for transparency/tests; null when that order is not applicable. */
    val order2Dice: Double?,
    val order3Dice: Double?,
    /** Both sides were shorter than 2 code points; the documented single-code-point fallback ran. */
    val singleCharacterFallback: Boolean,
    /**
     * The bounded preprocessing/comparison did not examine the whole input (or kept only
     * [ShortAnswerTextMatch.MAX_CODE_POINTS] effective code points). The UI must say so.
     */
    val truncated: Boolean,
    val unavailableReason: ShortAnswerMatchUnavailable?
)

/**
 * Offline character 2-gram / 3-gram multiset Dice similarity.
 *
 * Algorithm version: `dice-char23-v2`.
 *
 * Normalization (conservative, no word segmentation, no stop-word removal):
 * 1. Unicode code points only (surrogate pairs are never split).
 * 2. Full-width ASCII (U+FF01..U+FF5E) folded to ASCII, ideographic space (U+3000) to space.
 * 3. Lowercase with [Locale.ROOT].
 * 4. Whitespace removed.
 * 5. Ordinary layout punctuation removed (sentence punctuation, quotes, ellipsis, dashes,
 *    enumeration comma, bullet, Chinese book-title brackets) **except** a numeric separator
 *    (`.` `:` `,`) that sits directly between two digits: those are kept, so `1.5`≠`15`,
 *    `1:2`≠`12` and `1,000`≠`1000`. Full-width `．`/`：`/`，` fold to the same forms first.
 * 6. Everything else is kept: letters/digits, negation words (不/无/未/…), conditions (至少/至多/…)
 *    and math/comparison symbols (+, -, %, <, >, =, /, *, brackets, …). The original strings are
 *    never modified.
 *
 * Bounded preprocessing (v2): only the first [MAX_SCANNED_CODE_POINTS] raw code points are folded
 * and lowercased, and at most [MAX_CODE_POINTS] effective code points are kept per side. Therefore
 * the preprocessing output/memory is bounded too, not just the gram counting. The result carries
 * [ShortAnswerMatchResult.truncated] whenever input beyond those bounds existed.
 *
 * Similarity:
 * - For order n in {2,3}, count every n-code-point window as a multiset entry.
 * - overlap = Σ min(countA, countB) over the gram keys.
 * - dice_n = 2*overlap / (totalA + totalB). If only one side has n-grams the order scores 0
 *   (not skipped); an order is skipped only when both sides have none.
 * - Final score = equal-weight average of the applicable orders → 0..1, displayed as percent.
 * - Both sides shorter than 2 code points: single-code-point fallback (equal → 100%, else 0%) with
 *   a "short text" note. Two empty normalized sides never produce 100%.
 * - No division by zero; the score is always finite.
 */
object ShortAnswerTextMatch {

    const val ALGORITHM_VERSION = "dice-char23-v2"

    /** Effective code points kept and compared per side. */
    const val MAX_CODE_POINTS = 2000

    /** Raw code points folded/lowercased per side; bounds preprocessing work and memory. */
    const val MAX_SCANNED_CODE_POINTS = 8000

    private val PUNCTUATION: Set<Int> = buildSet {
        // Sentence punctuation and separators (ASCII + Chinese).
        for (ch in listOf(',', '.', '!', '?', ';', ':', '`')) add(ch.code)
        for (ch in listOf('、', '，', '。', '！', '？', '；', '：')) add(ch.code)
        // Quotes and typographic marks.
        for (ch in listOf('"', '\'')) add(ch.code)
        for (ch in listOf('“', '”', '‘', '’', '「', '」', '『', '』')) add(ch.code)
        for (ch in listOf('…', '—', '–', '·', '•')) add(ch.code)
        // Chinese book-title/angle brackets; ASCII math brackets are intentionally preserved.
        for (ch in listOf('《', '》', '【', '】', '〈', '〉')) add(ch.code)
    }

    /**
     * Punctuation that is dropped unless it directly separates two digits. Comma is included
     * because `1,000` vs `1000` is a meaningful difference; a comma between digits in prose is
     * left in place conservatively rather than guessed away.
     */
    private val NUMERIC_SEPARATORS: Set<Int> = setOf(','.code, '.'.code, ':'.code)

    private class BoundedNormalization(
        val points: IntArray,
        val truncated: Boolean,
        /** Some non-whitespace character existed in the examined prefix (even if removed later). */
        val sawNonWhitespace: Boolean
    )

    fun match(userText: String, referenceAnswer: String?): ShortAnswerMatchResult {
        val reference = referenceAnswer.orEmpty()
        val user = normalizeBounded(userText)
        val expected = normalizeBounded(reference)

        if (expected.points.isEmpty()) {
            // Only claim "empty" when the whole (untruncated) reference was whitespace.
            val reason = if (!expected.sawNonWhitespace) {
                ShortAnswerMatchUnavailable.REFERENCE_EMPTY
            } else {
                ShortAnswerMatchUnavailable.NORMALIZED_REFERENCE_EMPTY
            }
            return unavailable(reason, expected.truncated)
        }
        if (user.points.isEmpty()) {
            val reason = if (!user.sawNonWhitespace) {
                ShortAnswerMatchUnavailable.USER_TEXT_EMPTY
            } else {
                ShortAnswerMatchUnavailable.NORMALIZED_USER_EMPTY
            }
            return unavailable(reason, user.truncated)
        }

        val truncated = user.truncated || expected.truncated
        if (user.points.size == 1 && expected.points.size == 1) {
            val equal = user.points[0] == expected.points[0]
            return ShortAnswerMatchResult(
                available = true,
                score = if (equal) 1.0 else 0.0,
                percent = if (equal) 100 else 0,
                order2Dice = null,
                order3Dice = null,
                singleCharacterFallback = true,
                truncated = truncated,
                unavailableReason = null
            )
        }

        val order2 = dice(user.points, expected.points, 2)
        val order3 = dice(user.points, expected.points, 3)
        val scores = listOfNotNull(order2, order3)
        // At least one side has >= 2 code points here, so order 2 is always applicable.
        val score = scores.sum() / scores.size
        return ShortAnswerMatchResult(
            available = true,
            score = score,
            percent = (score * 100.0).roundToInt().coerceIn(0, 100),
            order2Dice = order2,
            order3Dice = order3,
            singleCharacterFallback = false,
            truncated = truncated,
            unavailableReason = null
        )
    }

    /**
     * Code-point safe, bounded normalization. At most [MAX_SCANNED_CODE_POINTS] raw code points are
     * folded and at most [MAX_CODE_POINTS] effective code points are returned; exposed for tests.
     */
    fun normalizeToCodePoints(text: String): IntArray = normalizeBounded(text).points

    /** Order-n Dice over code-point gram multisets; null only when both sides have no n-grams. */
    fun dice(a: IntArray, b: IntArray, order: Int): Double? {
        require(order >= 1) { "order must be positive" }
        val aTotal = (a.size - order + 1).coerceAtLeast(0)
        val bTotal = (b.size - order + 1).coerceAtLeast(0)
        if (aTotal == 0 && bTotal == 0) return null
        val aCounts = gramCounts(a, order)
        val bCounts = gramCounts(b, order)
        var overlap = 0
        for ((key, count) in aCounts) {
            val other = bCounts[key] ?: continue
            overlap += minOf(count, other)
        }
        return 2.0 * overlap / (aTotal + bTotal)
    }

    private fun normalizeBounded(text: String): BoundedNormalization {
        if (text.isEmpty()) return BoundedNormalization(IntArray(0), false, false)

        val folded = StringBuilder(minOf(text.length, MAX_SCANNED_CODE_POINTS))
        var index = 0
        var scanned = 0
        var sawNonWhitespace = false
        while (index < text.length && scanned < MAX_SCANNED_CODE_POINTS) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            scanned += 1
            val foldedCodePoint = foldWidth(codePoint)
            if (!isSpaceCodePoint(foldedCodePoint)) sawNonWhitespace = true
            folded.appendCodePoint(foldedCodePoint)
        }
        val truncatedByScan = index < text.length

        val lowered = folded.toString().lowercase(Locale.ROOT)
        val points = ArrayList<Int>(minOf(lowered.length, MAX_CODE_POINTS))
        var i = 0
        var truncatedByKeep = false
        while (i < lowered.length) {
            val codePoint = lowered.codePointAt(i)
            val next = i + Character.charCount(codePoint)
            if (!isSpaceCodePoint(codePoint)) {
                val keep = if (codePoint in PUNCTUATION) {
                    codePoint in NUMERIC_SEPARATORS && isNumericSeparatorContext(lowered, i, next)
                } else {
                    true
                }
                if (keep) {
                    if (points.size >= MAX_CODE_POINTS) {
                        truncatedByKeep = true
                        break
                    }
                    points.add(codePoint)
                }
            }
            i = next
        }

        return BoundedNormalization(
            points = points.toIntArray(),
            truncated = truncatedByScan || truncatedByKeep,
            sawNonWhitespace = sawNonWhitespace
        )
    }

    /** True when `.`/`:`/`,` sits directly between two digits in the normalized prefix. */
    private fun isNumericSeparatorContext(text: String, start: Int, end: Int): Boolean {
        if (start <= 0 || end >= text.length) return false
        val before = text.codePointBefore(start)
        val after = text.codePointAt(end)
        return Character.isDigit(before) && Character.isDigit(after)
    }

    private fun gramCounts(codePoints: IntArray, order: Int): HashMap<Long, Int> {
        val counts = HashMap<Long, Int>(codePoints.size)
        if (codePoints.size < order) return counts
        for (start in 0..(codePoints.size - order)) {
            var key = 0L
            for (offset in 0 until order) {
                key = (key shl 21) or codePoints[start + offset].toLong()
            }
            counts[key] = (counts[key] ?: 0) + 1
        }
        return counts
    }

    private fun foldWidth(codePoint: Int): Int = when {
        codePoint == 0x3000 -> ' '.code
        codePoint in 0xFF01..0xFF5E -> codePoint - 0xFEE0
        else -> codePoint
    }

    private fun isSpaceCodePoint(codePoint: Int): Boolean =
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)

    private fun unavailable(
        reason: ShortAnswerMatchUnavailable,
        truncated: Boolean
    ): ShortAnswerMatchResult =
        ShortAnswerMatchResult(
            available = false,
            score = 0.0,
            percent = 0,
            order2Dice = null,
            order3Dice = null,
            singleCharacterFallback = false,
            truncated = truncated,
            unavailableReason = reason
        )
}
