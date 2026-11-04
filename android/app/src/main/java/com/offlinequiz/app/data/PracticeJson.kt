package com.offlinequiz.app.data

import com.offlinequiz.app.model.PracticeDraft
import com.offlinequiz.app.model.PracticeQuestionSnapshot
import com.offlinequiz.app.model.PracticeUserAnswer
import org.json.JSONArray
import org.json.JSONObject

/** JSON documents for practice snapshots and drafts. org.json is supplied by Android/tests. */
object PracticeJson {

    fun encodeQuestions(questions: List<PracticeQuestionSnapshot>): String {
        val array = JSONArray()
        for (question in questions) array.put(encodeQuestionObject(question))
        return array.toString()
    }

    fun decodeQuestions(raw: String?): List<PracticeQuestionSnapshot> = safe(raw, emptyList()) { text ->
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            decodeQuestionObject(item)
        }
    }

    fun encodeDraft(draft: PracticeDraft): String {
        val item = JSONObject()
        item.put("selectedKeys", JSONArray(draft.selectedKeys))
        item.put("blankAnswers", JSONArray(draft.blankAnswers))
        item.put("text", draft.text)
        item.put("referenceRevealed", draft.referenceRevealed)
        return item.toString()
    }

    fun decodeDraft(raw: String?): PracticeDraft = safe(raw, PracticeDraft()) { text ->
        val item = JSONObject(text)
        PracticeDraft(
            selectedKeys = decodeStringArray(item.optJSONArray("selectedKeys")),
            blankAnswers = decodeStringArray(item.optJSONArray("blankAnswers")),
            text = item.optString("text", ""),
            referenceRevealed = item.optBoolean("referenceRevealed", false)
        )
    }

    fun encodeUserAnswer(answer: PracticeUserAnswer): String {
        val item = JSONObject()
        item.put("selectedKeys", JSONArray(answer.selectedKeys))
        item.put("blankAnswers", JSONArray(answer.blankAnswers))
        item.put("text", answer.text)
        return item.toString()
    }

    fun decodeUserAnswer(raw: String?): PracticeUserAnswer = safe(raw, PracticeUserAnswer()) { text ->
        val item = JSONObject(text)
        PracticeUserAnswer(
            selectedKeys = decodeStringArray(item.optJSONArray("selectedKeys")),
            blankAnswers = decodeStringArray(item.optJSONArray("blankAnswers")),
            text = item.optString("text", "")
        )
    }

    private fun encodeQuestionObject(question: PracticeQuestionSnapshot): JSONObject {
        val item = JSONObject()
        item.put("questionId", question.questionId)
        item.put("libraryId", question.libraryId)
        item.put("position", question.position)
        item.put("type", question.type)
        question.originalNumber?.let { item.put("originalNumber", it) }
        item.put("stem", question.stem)
        item.put("optionsJson", QuizJson.encodeOptions(question.options))
        item.put("answersJson", QuizJson.encodeStrings(question.answers))
        item.put("acceptableAnswersJson", QuizJson.encodeStringLists(question.acceptableAnswers))
        question.referenceAnswer?.let { item.put("referenceAnswer", it) }
        question.editedAt?.let { item.put("editedAt", it) }
        return item
    }

    private fun decodeQuestionObject(item: JSONObject): PracticeQuestionSnapshot {
        return PracticeQuestionSnapshot(
            questionId = item.optString("questionId", ""),
            libraryId = item.optString("libraryId", ""),
            position = item.optInt("position", 0),
            type = item.optString("type", ""),
            originalNumber = nullableString(item, "originalNumber"),
            stem = item.optString("stem", ""),
            options = QuizJson.decodeOptions(item.optString("optionsJson", "[]")),
            answers = QuizJson.decodeStrings(item.optString("answersJson", "[]")),
            acceptableAnswers = QuizJson.decodeStringLists(item.optString("acceptableAnswersJson", "[]")),
            referenceAnswer = nullableString(item, "referenceAnswer"),
            editedAt = if (item.has("editedAt") && !item.isNull("editedAt")) item.optLong("editedAt") else null
        )
    }

    private fun decodeStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { index ->
            if (array.isNull(index)) "" else array.optString(index, "")
        }
    }

    private fun nullableString(item: JSONObject, key: String): String? {
        return if (item.has(key) && !item.isNull(key)) item.optString(key, "") else null
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
