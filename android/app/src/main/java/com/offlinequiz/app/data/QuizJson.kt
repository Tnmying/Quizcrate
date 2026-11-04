package com.offlinequiz.app.data

import com.offlinequiz.app.model.BlankSegment
import com.offlinequiz.app.model.BlankSpan
import com.offlinequiz.app.model.LearningBlankRef
import com.offlinequiz.app.model.QuizOption
import com.offlinequiz.app.model.SourceLineRef
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small JSON codec for nested question evidence.
 *
 * Relational columns hold queryable question identity; nested options/blank spans are stored as
 * version-tolerant JSON documents. org.json is part of the Android platform (the JVM tests add the
 * reference implementation on the test classpath).
 */
object QuizJson {

    fun encodeOptions(options: List<QuizOption>): String {
        val array = JSONArray()
        for (option in options) {
            val item = JSONObject()
            item.put("key", option.key)
            item.put("text", option.text)
            item.put("markerIndex", option.markerIndex)
            item.put("duplicate", option.duplicate)
            item.put("supported", option.supported)
            array.put(item)
        }
        return array.toString()
    }

    fun decodeOptions(raw: String?): List<QuizOption> = safe(raw, emptyList()) { text ->
        val array = JSONArray(text)
        (0 until array.length()).map { index ->
            val item = array.optJSONObject(index) ?: JSONObject()
            QuizOption(
                key = item.optString("key", ""),
                text = item.optString("text", ""),
                markerIndex = item.optInt("markerIndex", index),
                duplicate = item.optBoolean("duplicate", false),
                supported = item.optBoolean("supported", true)
            )
        }
    }

    fun encodeStrings(values: List<String>): String = JSONArray(values).toString()

    fun decodeStrings(raw: String?): List<String> = safe(raw, emptyList()) { text ->
        val array = JSONArray(text)
        (0 until array.length()).map { index ->
            if (array.isNull(index)) "" else array.optString(index, "")
        }
    }

    fun encodeInts(values: List<Int>): String = JSONArray(values).toString()

    fun decodeInts(raw: String?): List<Int> = safe(raw, emptyList()) { text ->
        val array = JSONArray(text)
        (0 until array.length()).map { index -> array.optInt(index, 0) }
    }

    fun encodeStringLists(values: List<List<String>>): String {
        val outer = JSONArray()
        for (inner in values) outer.put(JSONArray(inner))
        return outer.toString()
    }

    fun decodeStringLists(raw: String?): List<List<String>> = safe(raw, emptyList()) { text ->
        val outer = JSONArray(text)
        (0 until outer.length()).map { index ->
            val inner = outer.optJSONArray(index) ?: JSONArray()
            (0 until inner.length()).map { innerIndex ->
                if (inner.isNull(innerIndex)) "" else inner.optString(innerIndex, "")
            }
        }
    }

    fun encodeSourceLines(lines: List<SourceLineRef>): String {
        val array = JSONArray()
        for (line in lines) {
            val item = JSONObject()
            item.put("page", line.page)
            item.put("line", line.line)
            array.put(item)
        }
        return array.toString()
    }

    fun decodeSourceLines(raw: String?): List<SourceLineRef> = safe(raw, emptyList()) { text ->
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            SourceLineRef(page = item.optInt("page", 0), line = item.optInt("line", 0))
        }
    }

    fun encodeBlanks(blanks: List<BlankSpan>): String {
        val array = JSONArray()
        for (blank in blanks) {
            val item = JSONObject()
            item.put("answer", blank.answer)
            item.put("originalAnswer", blank.originalAnswer)
            item.put("crossLine", blank.crossLine)
            item.put("splitByEnumeration", blank.splitByEnumeration)
            item.put("sentenceStart", blank.sentenceStart)
            item.put("sentenceEnd", blank.sentenceEnd)

            val segments = JSONArray()
            for (segment in blank.segments) {
                val segmentItem = JSONObject()
                segmentItem.put("page", segment.page)
                segmentItem.put("line", segment.line)
                segmentItem.put("x0", segment.x0.toDouble())
                segmentItem.put("x1", segment.x1.toDouble())
                segmentItem.put("y", segment.y.toDouble())
                segmentItem.put("text", segment.text)
                segments.put(segmentItem)
            }
            item.put("segments", segments)

            val learningBlanks = JSONArray()
            for (learning in blank.learningBlanks) {
                val learningItem = JSONObject()
                learningItem.put("answer", learning.answer)
                learningItem.put("sentenceStart", learning.sentenceStart)
                learningItem.put("sentenceEnd", learning.sentenceEnd)
                learningBlanks.put(learningItem)
            }
            item.put("learningBlanks", learningBlanks)
            array.put(item)
        }
        return array.toString()
    }

    fun decodeBlanks(raw: String?): List<BlankSpan> = safe(raw, emptyList()) { text ->
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val segmentArray = item.optJSONArray("segments") ?: JSONArray()
            val segments = (0 until segmentArray.length()).mapNotNull { segmentIndex ->
                val segmentItem = segmentArray.optJSONObject(segmentIndex) ?: return@mapNotNull null
                BlankSegment(
                    page = segmentItem.optInt("page", 0),
                    line = segmentItem.optInt("line", 0),
                    x0 = segmentItem.optDouble("x0", 0.0).toFloat(),
                    x1 = segmentItem.optDouble("x1", 0.0).toFloat(),
                    y = segmentItem.optDouble("y", 0.0).toFloat(),
                    text = segmentItem.optString("text", "")
                )
            }
            val learningArray = item.optJSONArray("learningBlanks") ?: JSONArray()
            val learningBlanks = (0 until learningArray.length()).mapNotNull { learningIndex ->
                val learningItem = learningArray.optJSONObject(learningIndex) ?: return@mapNotNull null
                LearningBlankRef(
                    answer = learningItem.optString("answer", ""),
                    sentenceStart = learningItem.optInt("sentenceStart", 0),
                    sentenceEnd = learningItem.optInt("sentenceEnd", 0)
                )
            }
            BlankSpan(
                answer = item.optString("answer", ""),
                originalAnswer = item.optString("originalAnswer", ""),
                crossLine = item.optBoolean("crossLine", false),
                splitByEnumeration = item.optBoolean("splitByEnumeration", false),
                sentenceStart = item.optInt("sentenceStart", 0),
                sentenceEnd = item.optInt("sentenceEnd", 0),
                segments = segments,
                learningBlanks = learningBlanks
            )
        }
    }

    private inline fun <T> safe(raw: String?, fallback: T, block: (String) -> T): T {
        if (raw.isNullOrBlank()) return fallback
        return try {
            block(raw)
        } catch (_: Exception) {
            fallback
        }
    }
}
