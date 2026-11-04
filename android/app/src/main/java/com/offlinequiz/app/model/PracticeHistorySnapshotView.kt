package com.offlinequiz.app.model

data class HistorySnapshotOption(val key: String, val text: String)

/**
 * Everything a history card renders about the question **as it was when the answer was submitted**.
 *
 * The screen builds this from [QuestionHistoryRecord.referenceSnapshot] only. It must never fall
 * back to the current library content: after an edit, the old record has to keep showing the old
 * stem/options/answers so the user can tell what they actually answered.
 */
data class HistorySnapshotView(
    val available: Boolean,
    val typeLabel: String,
    val originalNumber: String?,
    val stem: String,
    val options: List<HistorySnapshotOption>,
    val userAnswer: String,
    val referenceAnswer: String
) {
    companion object {
        const val UNAVAILABLE_MESSAGE = "当时题目快照不可用，仅保留已记录的作答、时间与结果。"

        fun of(record: QuestionHistoryRecord): HistorySnapshotView {
            val snapshot = record.referenceSnapshot
            return HistorySnapshotView(
                available = snapshot != null,
                typeLabel = snapshot?.let { QuestionTypes.label(it.type) }.orEmpty(),
                originalNumber = snapshot?.originalNumber,
                stem = snapshot?.stem.orEmpty(),
                options = snapshot?.options.orEmpty().map {
                    HistorySnapshotOption(key = it.key, text = it.text)
                },
                userAnswer = PracticeHistoryText.userAnswer(snapshot, record.answer.userAnswer),
                referenceAnswer = snapshot?.let { PracticeHistoryText.referenceAnswer(it) }.orEmpty()
            )
        }
    }
}
