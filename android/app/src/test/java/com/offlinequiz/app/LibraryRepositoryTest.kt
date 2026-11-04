package com.offlinequiz.app

import com.offlinequiz.app.data.LibraryRepository
import com.offlinequiz.app.data.LibraryStore
import com.offlinequiz.app.data.PdfArchive
import com.offlinequiz.app.data.RepositoryException
import com.offlinequiz.app.model.SaveOutcome
import com.offlinequiz.app.model.LibraryRecord
import com.offlinequiz.app.model.LibrarySummary
import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.model.StoredQuestion
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import pdfprobe.Model

class LibraryRepositoryTest {

    private class FakeStore : LibraryStore {
        val libraries = linkedMapOf<String, LibraryRecord>()
        val questions = linkedMapOf<String, StoredQuestion>()
        var failCreate = false
        var failSave = false

        override fun observeSummaries(): Flow<List<LibrarySummary>> = flowOf(
            libraries.values.map { library ->
                val all = questions.values.filter { it.libraryId == library.id }
                com.offlinequiz.app.model.QuestionReview.summary(library, all)
            }
        )

        override fun observeLibrary(id: String): Flow<LibraryRecord?> = flowOf(libraries[id])

        override fun observeQuestions(libraryId: String): Flow<List<StoredQuestion>> =
            flowOf(questions.values.filter { it.libraryId == libraryId }.sortedBy { it.position })

        override suspend fun questions(libraryId: String): List<StoredQuestion> =
            questions.values.filter { it.libraryId == libraryId }.sortedBy { it.position }

        override suspend fun library(id: String): LibraryRecord? = libraries[id]

        override suspend fun question(id: String): StoredQuestion? = questions[id]

        override suspend fun createLibrary(library: LibraryRecord, questions: List<StoredQuestion>) {
            if (failCreate) throw IOException("simulated create failure")
            libraries[library.id] = library
            questions.forEach { this.questions[it.id] = it }
        }

        override suspend fun renameLibrary(id: String, name: String, updatedAt: Long) {
            val current = libraries[id] ?: throw IllegalStateException("missing")
            libraries[id] = current.copy(name = name, updatedAt = updatedAt)
        }

        override suspend fun saveQuestion(question: StoredQuestion, updatedAt: Long) {
            if (failSave) throw IOException("simulated save failure")
            questions[question.id] = question
        }

        override suspend fun libraryIds(): List<String> = libraries.keys.toList()
    }

    private class FakeArchive : PdfArchive {
        var failCopy = false
        var copiedPath: String? = null
        val deleted = mutableListOf<String>()

        override fun copy(libraryId: String): String {
            if (failCopy) throw com.offlinequiz.app.data.PdfCopyException("simulated copy failure")
            return "libraries/$libraryId/source.pdf".also { copiedPath = it }
        }

        override fun delete(relativePath: String) {
            deleted += relativePath
        }

        override fun resolve(relativePath: String): File? = null

        override fun cleanupOrphans(keepLibraryIds: Set<String>) {}
    }

    private fun parseResult(): Model.ParseResult {
        val result = Model.ParseResult()
        val question = Model.Question()
        question.type = QuestionTypes.FILL_BLANK
        question.sectionTitle = "四、填空题"
        question.originalNumber = "1"
        question.numberValue = 1
        question.stem = "请填写____。"
        question.stemWithAnswer = question.stem
        question.answers.add("甲")
        question.sourcePages.add(2)
        question.learningBlankCount = 1
        question.reviewReasons.add("empty_question_start")
        question.needsReview = true
        result.questions.add(question)
        return result
    }

    private fun repository(
        store: FakeStore,
        clockValue: Long = 1_000L
    ): LibraryRepository {
        var counter = 0
        return LibraryRepository(
            store = store,
            clock = { clockValue },
            idFactory = {
                counter += 1
                "generated-$counter"
            }
        )
    }

    @Test
    fun importPreservesOriginalEvidenceAndCreatesDraftQuestions() = runTest {
        val store = FakeStore()
        val archive = FakeArchive()
        val repo = repository(store)

        val libraryId = repo.importLibrary("测试题库", "demo.pdf", 3, parseResult(), archive)

        assertEquals("generated-1", libraryId)
        val record = store.libraries.getValue(libraryId)
        assertEquals("测试题库", record.name)
        assertEquals(3, record.sourcePageCount)
        assertEquals("libraries/generated-1/source.pdf", record.pdfRelativePath)
        assertEquals(archive.copiedPath, record.pdfRelativePath)

        val saved = store.questions.values.single()
        assertEquals(libraryId, saved.libraryId)
        assertEquals("请填写____。", saved.originalStem)
        assertTrue(saved.originalNeedsReview)
        assertTrue(saved.originalReviewReasons.contains("empty_question_start"))
        assertFalse(saved.confirmed)
        assertEquals(1, saved.learningBlankCount)
    }

    @Test
    fun twoImportsWithSameOriginalNumberDoNotShareIds() = runTest {
        val store = FakeStore()
        val archive = FakeArchive()
        val repo = repository(store)

        val first = repo.importLibrary("题库一", "a.pdf", 2, parseResult(), archive)
        val second = repo.importLibrary("题库二", "b.pdf", 2, parseResult(), archive)

        assertNotEquals(first, second)
        assertEquals(2, store.libraries.size)
        val firstQuestion = store.questions.values.single { it.libraryId == first }
        val secondQuestion = store.questions.values.single { it.libraryId == second }
        assertNotEquals(firstQuestion.id, secondQuestion.id)
        assertEquals("1", firstQuestion.originalNumber)
        assertEquals("1", secondQuestion.originalNumber)
    }

    @Test
    fun pdfCopyFailureCreatesNoLibrary() = runTest {
        val store = FakeStore()
        val archive = FakeArchive().apply { failCopy = true }
        val repo = repository(store)

        try {
            repo.importLibrary("测试题库", "demo.pdf", 3, parseResult(), archive)
            fail("expected RepositoryException")
        } catch (e: RepositoryException) {
            assertTrue(e.message!!.contains("PDF 私有副本"))
        }

        assertTrue(store.libraries.isEmpty())
        assertTrue(store.questions.isEmpty())
    }

    @Test
    fun databaseFailureDoesNotOverwriteExistingLibraryAndRemovesNewPdfCopy() = runTest {
        val store = FakeStore()
        val archive = FakeArchive()
        store.libraries["old"] = testLibrary(id = "old", name = "旧题库")
        store.questions["old-q"] = testQuestion(id = "old-q", libraryId = "old", stem = "旧题干____。")
        store.failCreate = true
        val repo = repository(store)

        try {
            repo.importLibrary("新题库", "new.pdf", 2, parseResult(), archive)
            fail("expected RepositoryException")
        } catch (_: RepositoryException) {
        }

        assertEquals(setOf("old"), store.libraries.keys)
        assertEquals("旧题干____。", store.questions.getValue("old-q").stem)
        assertTrue(archive.deleted.contains("libraries/generated-1/source.pdf"))
    }

    @Test
    fun failedQuestionSaveLeavesOldContentIntact() = runTest {
        val store = FakeStore()
        val old = testQuestion(id = "q", libraryId = "lib", stem = "旧题干____。", confirmed = true)
        store.libraries["lib"] = testLibrary(id = "lib")
        store.questions["q"] = old
        store.failSave = true
        val repo = repository(store)

        val outcome = repo.saveQuestion(old.copy(stem = "新题干____。"), confirm = false)

        assertTrue(outcome is SaveOutcome.Failed)
        assertEquals("旧题干____。", store.questions.getValue("q").stem)
        assertTrue(store.questions.getValue("q").confirmed)
    }

    @Test
    fun confirmAndEditTransitionsAreExplicit() = runTest {
        val store = FakeStore()
        val archive = FakeArchive()
        val repo = repository(store)
        val libraryId = repo.importLibrary("测试题库", "demo.pdf", 3, parseResult(), archive)
        val draft = store.questions.values.single()

        val confirmed = repo.saveQuestion(draft, confirm = true)
        assertTrue(confirmed is SaveOutcome.Saved)
        assertTrue((confirmed as SaveOutcome.Saved).confirmed)
        assertTrue(store.questions.getValue(draft.id).confirmed)

        val edited = repo.saveQuestion(draft.copy(stem = "修改后的题干____。"), confirm = false)
        assertTrue(edited is SaveOutcome.Saved)
        assertFalse((edited as SaveOutcome.Saved).confirmed)
        assertFalse(store.questions.getValue(draft.id).confirmed)

        val unchangedSave = repo.saveQuestion(draft.copy(stem = "修改后的题干____。"), confirm = false)
        assertTrue(unchangedSave is SaveOutcome.Unchanged)
        assertFalse(store.questions.getValue(draft.id).confirmed)

        val reconfirmed = repo.saveQuestion(draft.copy(stem = "修改后的题干____。"), confirm = true)
        assertTrue(reconfirmed is SaveOutcome.Saved)
        assertTrue(store.questions.getValue(draft.id).confirmed)
        assertEquals(libraryId, store.questions.getValue(draft.id).libraryId)
    }

    @Test
    fun structuralErrorsAreRejectedBeforeWriting() = runTest {
        val store = FakeStore()
        val archive = FakeArchive()
        val repo = repository(store)
        repo.importLibrary("测试题库", "demo.pdf", 3, parseResult(), archive)
        val draft = store.questions.values.single()

        val outcome = repo.saveQuestion(draft.copy(stem = "没有空位的题干。"), confirm = true)

        assertTrue(outcome is SaveOutcome.Invalid)
        assertTrue((outcome as SaveOutcome.Invalid).errors.any { it.contains("没有空位") })
        assertEquals("请填写____。", store.questions.getValue(draft.id).stem)
        assertFalse(store.questions.getValue(draft.id).confirmed)
    }

    @Test
    fun renameRejectsBlankNameAndKeepsOldName() = runTest {
        val store = FakeStore()
        store.libraries["lib"] = testLibrary(id = "lib", name = "原名称")
        val repo = repository(store)

        try {
            repo.renameLibrary("lib", "   ")
            fail("expected RepositoryException")
        } catch (_: RepositoryException) {
        }
        assertEquals("原名称", store.libraries.getValue("lib").name)

        repo.renameLibrary("lib", " 新名称 ")
        assertEquals("新名称", store.libraries.getValue("lib").name)
    }
}
