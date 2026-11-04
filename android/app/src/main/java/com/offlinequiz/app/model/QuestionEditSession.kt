package com.offlinequiz.app.model

/**
 * Pure editing/saving state boundary for one question.
 *
 * The UI and the ViewModel both depend on this class so "saving" guards can be regression-tested
 * without an Android device: while [saving] is true, [update] refuses every mutation. The database
 * read-back after a successful save therefore cannot silently overwrite text typed during the save.
 */
class QuestionEditSession(
    private val saveQuestion: suspend (StoredQuestion, Boolean) -> SaveOutcome,
    private val reloadQuestion: suspend (String) -> StoredQuestion?
) {

    var question: StoredQuestion? = null
        private set

    var baseline: EditableContent? = null
        private set

    var saving: Boolean = false
        private set

    var errors: List<String> = emptyList()
        private set

    var message: String? = null
        private set

    fun clear() {
        question = null
        baseline = null
        saving = false
        errors = emptyList()
        message = null
    }

    fun load(loaded: StoredQuestion) {
        question = loaded
        baseline = loaded.editableContent()
        saving = false
        errors = emptyList()
        message = null
    }

    fun hasUnsavedChanges(): Boolean {
        val current = question ?: return false
        return baseline != null && current.editableContent() != baseline
    }

    /** The single mutation entry point. Returns false while a save is in flight. */
    fun update(block: (StoredQuestion) -> StoredQuestion): Boolean {
        if (saving) return false
        val current = question ?: return false
        val next = block(current)
        if (next == current) return false
        question = next
        errors = emptyList()
        return true
    }

    /**
     * Marks the save as in flight before any suspending call. The returned snapshot is the exact
     * content that will be persisted; later edits are rejected until [finishSave] completes.
     */
    fun beginSave(): StoredQuestion? {
        if (saving) return null
        val snapshot = question ?: return null
        saving = true
        errors = emptyList()
        message = null
        return snapshot
    }

    suspend fun finishSave(snapshot: StoredQuestion, confirm: Boolean) {
        try {
            val outcome = try {
                saveQuestion(snapshot, confirm)
            } catch (e: Exception) {
                SaveOutcome.Failed("保存失败，原有内容未改变")
            }
            when (outcome) {
                is SaveOutcome.Saved -> {
                    val refreshed = runCatching { reloadQuestion(snapshot.id) }.getOrNull()
                    if (refreshed != null) {
                        question = refreshed
                        baseline = refreshed.editableContent()
                        message = if (outcome.confirmed) "已保存并标记为已确认" else "已保存"
                    } else {
                        // The write succeeded; keep the saved snapshot rather than marking it dirty.
                        question = snapshot
                        baseline = snapshot.editableContent()
                        message = "已保存，但重新读取失败，请返回题库后重新打开"
                    }
                }
                is SaveOutcome.Invalid -> errors = outcome.errors
                is SaveOutcome.Failed -> message = outcome.message
                SaveOutcome.Unchanged -> {
                    message = if (confirm) "该题已确认" else "没有需要保存的修改"
                }
            }
        } finally {
            saving = false
        }
    }

    fun notify(text: String) {
        message = text
    }

    fun consumeMessage() {
        message = null
    }
}
