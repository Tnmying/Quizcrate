package com.offlinequiz.app.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlinequiz.app.OfflineQuizApplication
import com.offlinequiz.app.model.BlankEditor
import com.offlinequiz.app.model.LibraryRecord
import com.offlinequiz.app.model.QuestionEditSession
import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.model.QuizOption
import com.offlinequiz.app.model.StoredQuestion
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class QuestionEditViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as OfflineQuizApplication
    private val repository = app.repository
    private val session = QuestionEditSession(
        saveQuestion = { question, confirm -> repository.saveQuestion(question, confirm) },
        reloadQuestion = { id -> repository.question(id) }
    )
    private var loadJob: Job? = null

    var library by mutableStateOf<LibraryRecord?>(null)
        private set

    var question by mutableStateOf<StoredQuestion?>(null)
        private set

    var loading by mutableStateOf(true)
        private set

    var saving by mutableStateOf(false)
        private set

    var errors by mutableStateOf<List<String>>(emptyList())
        private set

    var message by mutableStateOf<String?>(null)
        private set

    fun load(libraryId: String, questionId: String) {
        // A configuration change must not reset the question while the save/read-back is in flight.
        if (session.saving) return
        loadJob?.cancel()
        session.clear()
        syncFromSession()
        loading = true
        library = null
        loadJob = viewModelScope.launch {
            try {
                val lib = repository.library(libraryId)
                val loaded = repository.question(questionId)
                if (loaded == null || loaded.libraryId != libraryId) {
                    errors = listOf("题目不存在或已被移除")
                    loading = false
                    return@launch
                }
                library = lib
                session.load(loaded)
                syncFromSession()
                loading = false
            } catch (e: Exception) {
                session.clear()
                syncFromSession()
                errors = listOf("读取题目失败，请返回题库后重试")
                loading = false
            }
        }
    }

    fun hasUnsavedChanges(): Boolean = session.hasUnsavedChanges()

    fun pdfFile(): File? = app.pdfStore.resolve(library?.pdfRelativePath)

    fun updateStem(value: String) {
        mutate { it.copy(stem = value) }
    }

    fun updateType(type: String) {
        if (type !in setOf(QuestionTypes.SINGLE_CHOICE, QuestionTypes.MULTIPLE_CHOICE,
                QuestionTypes.TRUE_FALSE, QuestionTypes.FILL_BLANK, QuestionTypes.SHORT_ANSWER)) return
        mutate { q -> q.copy(type = type,
            referenceAnswer = if (type == QuestionTypes.SHORT_ANSWER && q.referenceAnswer.isNullOrBlank()) q.answers.joinToString("；") else q.referenceAnswer)
        }
    }

    fun updateOptionKey(index: Int, value: String) {
        mutate { q ->
            if (index !in q.options.indices) return@mutate q
            val options = q.options.toMutableList()
            val oldKey = options[index].key.trim().uppercase()
            val newKey = value.trim().uppercase().take(1)
            options[index] = options[index].copy(key = newKey)
            val answers = if (oldKey.isEmpty()) {
                q.answers
            } else {
                q.answers.map { if (it.trim().uppercase() == oldKey) newKey else it }
            }
            q.copy(options = options, answers = answers)
        }
    }

    fun updateOptionText(index: Int, value: String) {
        mutate { q ->
            if (index !in q.options.indices) return@mutate q
            val options = q.options.toMutableList()
            options[index] = options[index].copy(text = value)
            q.copy(options = options)
        }
    }

    fun addOption() {
        if (saving || session.saving) return
        val q = question ?: return
        if (q.options.size >= 26) {
            session.notify("最多支持 26 个选项")
            syncFromSession()
            return
        }
        val used = q.options.map { it.key.trim().uppercase() }.toSet()
        val key = ('A'..'Z').firstOrNull { it.toString() !in used }?.toString()
        if (key == null) {
            session.notify("最多支持 26 个选项")
            syncFromSession()
            return
        }
        mutate { current ->
            current.copy(options = current.options + QuizOption(key = key, text = "", markerIndex = current.options.size))
        }
    }

    fun removeOption(index: Int) {
        mutate { q ->
            if (index !in q.options.indices || q.options.size <= 2) return@mutate q
            val removedKey = q.options[index].key.trim().uppercase()
            val options = q.options.toMutableList().apply { removeAt(index) }
            val answers = q.answers.filterNot { it.trim().uppercase() == removedKey }
            q.copy(options = options, answers = answers)
        }
    }

    fun toggleChoiceAnswer(key: String) {
        mutate { q ->
            val normalized = key.trim().uppercase()
            if (q.type == QuestionTypes.SINGLE_CHOICE) {
                q.copy(answers = listOf(normalized))
            } else {
                val selected = q.answers.map { it.trim().uppercase() }.toMutableSet()
                if (!selected.add(normalized)) selected.remove(normalized)
                val ordered = q.options.map { it.key.trim().uppercase() }
                    .filter { it in selected }
                    .distinct()
                q.copy(answers = ordered)
            }
        }
    }

    fun setTrueFalse(answer: String) {
        mutate { it.copy(answers = listOf(answer)) }
    }

    fun updateReferenceAnswer(value: String) {
        mutate { it.copy(referenceAnswer = value) }
    }

    fun updateBlankAnswer(index: Int, value: String) {
        mutate { q ->
            if (index !in q.answers.indices) return@mutate q
            val answers = q.answers.toMutableList().apply { this[index] = value }
            q.copy(answers = answers, learningBlankCount = answers.size)
        }
    }

    fun updateAcceptableAnswer(blankIndex: Int, acceptedIndex: Int, value: String) {
        mutate { q ->
            val acceptable = q.acceptableAnswers.toMutableList()
            while (acceptable.size < q.answers.size) acceptable.add(emptyList())
            if (blankIndex !in acceptable.indices) return@mutate q
            val list = acceptable[blankIndex].toMutableList()
            if (acceptedIndex in list.indices) {
                list[acceptedIndex] = value
            } else if (acceptedIndex == list.size) {
                list.add(value)
            } else {
                return@mutate q
            }
            acceptable[blankIndex] = list
            q.copy(acceptableAnswers = acceptable)
        }
    }

    fun addAcceptableAnswer(blankIndex: Int) {
        mutate { q ->
            val acceptable = q.acceptableAnswers.toMutableList()
            while (acceptable.size < q.answers.size) acceptable.add(emptyList())
            if (blankIndex !in acceptable.indices) return@mutate q
            acceptable[blankIndex] = acceptable[blankIndex] + ""
            q.copy(acceptableAnswers = acceptable)
        }
    }

    fun removeAcceptableAnswer(blankIndex: Int, acceptedIndex: Int) {
        mutate { q ->
            val acceptable = q.acceptableAnswers.toMutableList()
            if (blankIndex !in acceptable.indices) return@mutate q
            val list = acceptable[blankIndex].toMutableList()
            if (acceptedIndex !in list.indices) return@mutate q
            list.removeAt(acceptedIndex)
            acceptable[blankIndex] = list
            q.copy(acceptableAnswers = acceptable)
        }
    }

    fun addBlank() {
        mutate { q ->
            val result = BlankEditor.addBlank(q.stem, q.answers, q.acceptableAnswers)
            q.copy(
                stem = result.stem,
                answers = result.answers,
                acceptableAnswers = result.acceptableAnswers,
                learningBlankCount = result.answers.size
            )
        }
    }

    fun removeBlank(index: Int) {
        mutate { q ->
            if (q.answers.size <= 1) return@mutate q
            val result = BlankEditor.removeBlank(q.stem, q.answers, q.acceptableAnswers, index)
            q.copy(
                stem = result.stem,
                answers = result.answers,
                acceptableAnswers = result.acceptableAnswers,
                learningBlankCount = result.answers.size
            )
        }
    }

    fun mergeBlank(index: Int) {
        if (saving || session.saving) return
        val q = question ?: return
        val result = BlankEditor.mergeBlank(q.stem, q.answers, q.acceptableAnswers, index)
        if (result.rejected) {
            session.notify(result.rejectedReason ?: "无法安全合并，请手动校对")
            syncFromSession()
            return
        }
        mutate { current ->
            current.copy(
                stem = result.stem,
                answers = result.answers,
                acceptableAnswers = result.acceptableAnswers,
                learningBlankCount = result.answers.size
            )
        }
        if (!saving) {
            session.notify(result.notice ?: "已合并相邻两空。")
            syncFromSession()
        }
    }

    fun save(confirm: Boolean) {
        if (saving || session.saving) return
        val snapshot = session.beginSave() ?: return
        syncFromSession()
        viewModelScope.launch {
            session.finishSave(snapshot, confirm)
            syncFromSession()
        }
    }

    fun consumeMessage() {
        session.consumeMessage()
        syncFromSession()
    }

    private fun mutate(block: (StoredQuestion) -> StoredQuestion) {
        if (saving || session.saving) return
        if (!session.update(block)) return
        syncFromSession()
    }

    private fun syncFromSession() {
        question = session.question
        saving = session.saving
        errors = session.errors
        message = session.message
    }
}
