package com.offlinequiz.app.data

import com.offlinequiz.app.model.LibraryRecord
import com.offlinequiz.app.model.LibrarySummary
import com.offlinequiz.app.model.QuestionReview
import com.offlinequiz.app.model.StoredQuestion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

/**
 * Storage boundary. The Room implementation is used by the app; tests can provide a deterministic
 * in-memory implementation to exercise repository rollback behaviour without a device.
 */
interface LibraryStore {
    fun observeSummaries(): Flow<List<LibrarySummary>>
    fun observeLibrary(id: String): Flow<LibraryRecord?>
    fun observeQuestions(libraryId: String): Flow<List<StoredQuestion>>
    suspend fun questions(libraryId: String): List<StoredQuestion>
    suspend fun library(id: String): LibraryRecord?
    suspend fun question(id: String): StoredQuestion?
    suspend fun createLibrary(library: LibraryRecord, questions: List<StoredQuestion>)
    suspend fun renameLibrary(id: String, name: String, updatedAt: Long)
    suspend fun saveQuestion(question: StoredQuestion, updatedAt: Long)
    suspend fun libraryIds(): List<String>
}

class RoomLibraryStore(private val dao: LibraryDao) : LibraryStore {

    override fun observeSummaries(): Flow<List<LibrarySummary>> =
        dao.observeSummaries().map { rows ->
            rows.map { row ->
                QuestionReview.summary(row.library.toRecord(), row.questions.map { it.toModel() })
            }
        }.flowOn(Dispatchers.Default)

    override fun observeLibrary(id: String): Flow<LibraryRecord?> =
        dao.observeLibrary(id).map { it?.toRecord() }

    override fun observeQuestions(libraryId: String): Flow<List<StoredQuestion>> =
        dao.observeQuestions(libraryId).map { entities -> entities.map { it.toModel() } }

    override suspend fun questions(libraryId: String): List<StoredQuestion> =
        dao.questions(libraryId).map { it.toModel() }

    override suspend fun library(id: String): LibraryRecord? = dao.library(id)?.toRecord()

    override suspend fun question(id: String): StoredQuestion? = dao.question(id)?.toModel()

    override suspend fun createLibrary(library: LibraryRecord, questions: List<StoredQuestion>) {
        dao.createLibraryWithQuestions(
            library.toEntity(),
            questions.map { it.toEntity() }
        )
    }

    override suspend fun renameLibrary(id: String, name: String, updatedAt: Long) {
        val current = dao.library(id) ?: throw IllegalStateException("题库不存在")
        dao.updateLibrary(current.copy(name = name, updatedAt = updatedAt))
    }

    override suspend fun saveQuestion(question: StoredQuestion, updatedAt: Long) {
        dao.saveQuestionAndTouchLibrary(question.toEntity(), updatedAt)
    }

    override suspend fun libraryIds(): List<String> = dao.libraryIds()
}
