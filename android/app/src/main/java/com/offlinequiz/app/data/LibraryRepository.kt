package com.offlinequiz.app.data

import com.offlinequiz.app.model.LibraryRecord
import com.offlinequiz.app.model.LibrarySummary
import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.model.QuestionValidator
import com.offlinequiz.app.model.SaveOutcome
import com.offlinequiz.app.model.StoredQuestion
import com.offlinequiz.app.model.withFillAnswerVariants
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import pdfprobe.Model

class RepositoryException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Coordinates the private PDF copy, question mapping and the atomic database write.
 *
 * A failed import deletes its partial PDF folder and never touches an existing library. A failed
 * question save is reported without changing the previously stored row.
 */
class LibraryRepository(
    private val store: LibraryStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {

    fun observeSummaries(): Flow<List<LibrarySummary>> = store.observeSummaries()

    fun observeLibrary(id: String): Flow<LibraryRecord?> = store.observeLibrary(id)

    fun observeQuestions(libraryId: String): Flow<List<StoredQuestion>> = store.observeQuestions(libraryId)

    suspend fun library(id: String): LibraryRecord? = store.library(id)

    suspend fun question(id: String): StoredQuestion? = store.question(id)

    suspend fun importLibrary(
        name: String,
        fileName: String,
        pageCount: Int,
        result: Model.ParseResult,
        archive: PdfArchive? = null,
        sourceText: String? = null
    ): String {
        val safeName = validateName(name)
        if (archive == null && sourceText.isNullOrBlank()) {
            throw RepositoryException("缺少来源内容，题库未创建")
        }
        if (archive != null && sourceText != null) {
            throw RepositoryException("来源类型冲突，题库未创建")
        }
        if (sourceText != null && sourceText.toByteArray(Charsets.UTF_8).size > 512 * 1024) {
            throw RepositoryException("文字内容超过 512 KB，请分批导入")
        }
        val libraryId = idFactory()
        val relativePath = try {
            archive?.copy(libraryId)
        } catch (e: PdfCopyException) {
            throw RepositoryException("保存 PDF 私有副本失败，题库未创建：${e.message}", e)
        } catch (e: Exception) {
            throw RepositoryException("保存 PDF 私有副本失败，题库未创建", e)
        }

        var committed = false
        try {
            val questions = QuestionMapper.fromParseResult(result, libraryId, idFactory)
            if (questions.isEmpty()) throw RepositoryException("没有可保存的题目")
            val now = clock()
            val record = LibraryRecord(
                id = libraryId,
                name = safeName,
                createdAt = now,
                updatedAt = now,
                sourceFileName = fileName,
                sourcePageCount = pageCount,
                pdfRelativePath = relativePath,
                schemaVersion = QuizDatabase.VERSION,
                sourceText = sourceText
            )
            store.createLibrary(record, questions)
            committed = true
            return libraryId
        } catch (e: RepositoryException) {
            throw e
        } catch (e: Exception) {
            throw RepositoryException("保存题库失败，没有覆盖任何已有题库", e)
        } finally {
            if (!committed) {
                if (relativePath != null) runCatching { archive?.delete(relativePath) }
            }
        }
    }

    suspend fun renameLibrary(id: String, name: String) {
        val safeName = validateName(name)
        try {
            store.renameLibrary(id, safeName, clock())
        } catch (e: Exception) {
            throw RepositoryException("重命名失败，原名称保持不变", e)
        }
    }

    suspend fun saveQuestion(question: StoredQuestion, confirm: Boolean): SaveOutcome {
        val current = try {
            store.question(question.id)
        } catch (e: Exception) {
            return SaveOutcome.Failed("读取题目失败，原有内容未改变")
        } ?: return SaveOutcome.Failed("题目不存在，可能已被移除")

        if (current.libraryId != question.libraryId) {
            return SaveOutcome.Failed("题目不属于当前题库，未保存")
        }

        // Empty "acceptable answer" editor rows are not meaningful data; drop them before validation
        // and comparison while keeping the answer list order unchanged.
        val normalized = question.withFillAnswerVariants()
        val candidate = normalized.copy(acceptableAnswers = normalized.normalizedAcceptableAnswers())
        val errors = QuestionValidator.validate(candidate)
        if (errors.isNotEmpty()) return SaveOutcome.Invalid(errors)

        val changed = current.editableContent() != candidate.editableContent()
        val now = clock()
        val updated = when {
            changed -> candidate.copy(
                editedAt = now,
                confirmed = confirm,
                confirmedAt = if (confirm) now else null,
                learningBlankCount = if (candidate.type == QuestionTypes.FILL_BLANK) {
                    candidate.answers.size
                } else {
                    candidate.learningBlankCount
                }
            )
            // Saving without changes must not unconditionally clear an existing confirmation,
            // and must not drop the stored edit/confirmation metadata.
            confirm && !current.confirmed -> current.copy(confirmed = true, confirmedAt = now)
            else -> current
        }

        if (updated == current) return SaveOutcome.Unchanged

        return try {
            store.saveQuestion(updated, now)
            SaveOutcome.Saved(confirmed = updated.confirmed, changed = changed)
        } catch (e: Exception) {
            SaveOutcome.Failed("保存失败，原有内容未改变")
        }
    }

    suspend fun allLibraryIds(): List<String> = store.libraryIds()

    private fun validateName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw RepositoryException("题库名称不能为空")
        if (trimmed.length > 80) throw RepositoryException("题库名称不能超过 80 个字符")
        return trimmed
    }
}
