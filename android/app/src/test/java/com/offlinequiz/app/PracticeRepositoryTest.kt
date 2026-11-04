package com.offlinequiz.app

import com.offlinequiz.app.data.AdvancePracticeOutcome
import com.offlinequiz.app.data.DraftSaveOutcome
import com.offlinequiz.app.data.LibraryStore
import com.offlinequiz.app.data.PracticeRepository
import com.offlinequiz.app.data.PracticeStore
import com.offlinequiz.app.data.PracticeSubmitResult
import com.offlinequiz.app.data.StartPracticeOutcome
import com.offlinequiz.app.data.SubmitPracticeOutcome
import com.offlinequiz.app.model.LibraryRecord
import com.offlinequiz.app.model.LibrarySummary
import com.offlinequiz.app.model.PracticeDraft
import com.offlinequiz.app.model.PracticeHistoryEntry
import com.offlinequiz.app.model.PracticeModes
import com.offlinequiz.app.model.PracticePhases
import com.offlinequiz.app.model.PracticeRecordedAnswer
import com.offlinequiz.app.model.PracticeResults
import com.offlinequiz.app.model.PracticeSessionRecord
import com.offlinequiz.app.model.PracticeSessionSummary
import com.offlinequiz.app.model.PracticeSources
import com.offlinequiz.app.model.PracticeStatuses
import com.offlinequiz.app.model.PracticeUserAnswer
import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.model.QuizOption
import com.offlinequiz.app.model.StoredQuestion
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeRepositoryTest {

    private class FakeLibraryStore : LibraryStore {
        val libraries = linkedMapOf<String, LibraryRecord>()
        val questions = linkedMapOf<String, StoredQuestion>()

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
            libraries[library.id] = library
            questions.forEach { this.questions[it.id] = it }
        }

        override suspend fun renameLibrary(id: String, name: String, updatedAt: Long) {
            libraries[id] = libraries.getValue(id).copy(name = name, updatedAt = updatedAt)
        }

        override suspend fun saveQuestion(question: StoredQuestion, updatedAt: Long) {
            questions[question.id] = question
        }

        override suspend fun libraryIds(): List<String> = libraries.keys.toList()
    }

    private class FakePracticeStore : PracticeStore {
        val sessions = linkedMapOf<String, PracticeSessionRecord>()
        val answerMap = linkedMapOf<String, PracticeRecordedAnswer>()
        var failCreate = false
        var failSubmit = false
        var failDraft = false

        override fun observeActiveSession(libraryId: String): Flow<PracticeSessionSummary?> =
            flowOf(activeSummary(libraryId))

        override suspend fun activeSession(libraryId: String): PracticeSessionSummary? =
            activeSummary(libraryId)

        override suspend fun createSession(session: PracticeSessionRecord) {
            if (failCreate) throw IOException("create failed")
            sessions.values
                .filter { it.libraryId == session.libraryId && it.status == PracticeStatuses.ACTIVE }
                .forEach { sessions[it.id] = it.copy(status = PracticeStatuses.SUPERSEDED, updatedAt = session.createdAt) }
            sessions[session.id] = session
        }

        override suspend fun session(id: String): PracticeSessionRecord? = sessions[id]

        override suspend fun answers(sessionId: String): List<PracticeRecordedAnswer> =
            answerMap.values.filter { it.sessionId == sessionId }.sortedBy { it.position }

        override suspend fun answerAt(sessionId: String, position: Int): PracticeRecordedAnswer? =
            answerMap["$sessionId:$position"]

        override suspend fun submitAnswer(
            sessionId: String,
            expectedPosition: Int,
            answer: PracticeRecordedAnswer,
            referenceSnapshotJson: String
        ): PracticeSubmitResult? {
            if (failSubmit) throw IOException("submit failed")
            val session = sessions[sessionId] ?: return null
            if (session.status != PracticeStatuses.ACTIVE || session.currentIndex != expectedPosition) return null
            val key = "$sessionId:$expectedPosition"
            answerMap[key]?.let { return PracticeSubmitResult(it, alreadyRecorded = true) }
            answerMap[key] = answer
            sessions[sessionId] = session.copy(
                phase = PracticePhases.FEEDBACK,
                draft = PracticeDraft(),
                draftRevision = session.draftRevision + 1,
                updatedAt = answer.submittedAt
            )
            return PracticeSubmitResult(answer, alreadyRecorded = false)
        }

        override suspend fun advanceSession(
            sessionId: String,
            expectedPosition: Int,
            nextIndex: Int,
            completed: Boolean,
            now: Long
        ): PracticeSessionRecord? {
            val session = sessions[sessionId] ?: return null
            if (session.status != PracticeStatuses.ACTIVE) return null
            if (session.currentIndex != expectedPosition || session.phase != PracticePhases.FEEDBACK) return null
            if (answerMap["$sessionId:$expectedPosition"] == null) return null
            val updated = session.copy(
                currentIndex = nextIndex,
                phase = if (completed) PracticePhases.COMPLETED else PracticePhases.ANSWERING,
                status = if (completed) PracticeStatuses.COMPLETED else PracticeStatuses.ACTIVE,
                draft = PracticeDraft(),
                draftRevision = session.draftRevision + 1,
                updatedAt = now,
                completedAt = if (completed) now else null
            )
            sessions[sessionId] = updated
            return updated
        }

        override suspend fun saveDraft(
            sessionId: String,
            position: Int,
            draft: PracticeDraft,
            revision: Long,
            now: Long
        ): DraftSaveOutcome {
            if (failDraft) return DraftSaveOutcome.Failed("save draft failed")
            val session = sessions[sessionId] ?: return DraftSaveOutcome.Stale
            if (session.status != PracticeStatuses.ACTIVE ||
                session.phase != PracticePhases.ANSWERING ||
                session.currentIndex != position ||
                revision <= session.draftRevision
            ) {
                return DraftSaveOutcome.Stale
            }
            sessions[sessionId] = session.copy(draft = draft, draftRevision = revision, updatedAt = now)
            return DraftSaveOutcome.Saved
        }

        override suspend fun libraryStatsSnapshot(libraryId: String) =
            com.offlinequiz.app.model.PracticeStatsSnapshot(historyForLibrary(libraryId),
                com.offlinequiz.app.model.PracticeRoundCounts.fromSessions(sessions.values.filter { it.libraryId == libraryId }))

        override suspend fun historyForLibrary(libraryId: String): List<PracticeHistoryEntry> =
            answerMap.values
                .filter { sessions[it.sessionId]?.libraryId == libraryId }
                .map { it.toHistoryEntry() }

        override suspend fun historyForQuestion(
            libraryId: String,
            questionId: String
        ): List<PracticeHistoryEntry> =
            answerMap.values
                .filter {
                    it.questionId == questionId && sessions[it.sessionId]?.libraryId == libraryId
                }
                .map { it.toHistoryEntry() }

        private fun PracticeRecordedAnswer.toHistoryEntry(): PracticeHistoryEntry =
            PracticeHistoryEntry(
                answer = this,
                referenceSnapshot = null,
                sessionSource = sessions[sessionId]?.source ?: PracticeSources.NORMAL
            )

        private fun activeSummary(libraryId: String): PracticeSessionSummary? {            val session = sessions.values
                .filter { it.libraryId == libraryId && it.status == PracticeStatuses.ACTIVE }
                .maxByOrNull { it.updatedAt } ?: return null
            return PracticeSessionSummary(
                id = session.id,
                libraryId = session.libraryId,
                mode = session.mode,
                status = session.status,
                total = session.total,
                currentIndex = session.currentIndex,
                phase = session.phase,
                answeredCount = answerMap.values.count { it.sessionId == session.id },
                createdAt = session.createdAt,
                updatedAt = session.updatedAt
            )
        }
    }

    private fun options(vararg pairs: Pair<String, String>): List<QuizOption> =
        pairs.mapIndexed { index, pair -> QuizOption(pair.first, pair.second, markerIndex = index) }

    private fun single(id: String, position: Int, answer: String = "A"): StoredQuestion = testQuestion(
        id = id,
        position = position,
        type = QuestionTypes.SINGLE_CHOICE,
        stem = "单选题干",
        options = options("A" to "甲", "B" to "乙"),
        answers = listOf(answer)
    )

    private fun fill(id: String, position: Int, answer: String = "甲"): StoredQuestion = testQuestion(
        id = id,
        position = position,
        type = QuestionTypes.FILL_BLANK,
        stem = "填空____",
        answers = listOf(answer)
    )

    private fun short(id: String, position: Int): StoredQuestion = testQuestion(
        id = id,
        position = position,
        type = QuestionTypes.SHORT_ANSWER,
        stem = "简答题干",
        answers = emptyList(),
        referenceAnswer = "参考答案"
    )

    private fun setup(vararg questions: StoredQuestion): Pair<FakeLibraryStore, FakePracticeStore> {
        val libraryStore = FakeLibraryStore()
        libraryStore.libraries["lib"] = testLibrary(id = "lib", name = "练习题库")
        questions.forEach { libraryStore.questions[it.id] = it.copy(libraryId = "lib") }
        return libraryStore to FakePracticeStore()
    }

    private fun repository(
        libraryStore: FakeLibraryStore,
        practiceStore: FakePracticeStore
    ): PracticeRepository {
        var counter = 0
        return PracticeRepository(
            libraryStore = libraryStore,
            practiceStore = practiceStore,
            clock = { 1_000L },
            idFactory = {
                counter += 1
                "generated-$counter"
            }
        )
    }

    @Test
    fun correctionSavesAndConfirmsSourceButKeepsCurrentRoundAndRecordedResults() = runTest {
        val (libraries, sessions) = setup(single("q1", 0))
        val practice = repository(libraries, sessions)
        val editing = com.offlinequiz.app.data.LibraryRepository(libraries, clock = { 2_000L })
        val first = practice.start("lib", PracticeModes.SEQUENTIAL) as StartPracticeOutcome.Started
        val snapshot = practice.loadState(first.sessionId)!!.currentQuestion!!
        val recorded = practice.submitAutomatic(first.sessionId, 0, PracticeUserAnswer(selectedKeys = listOf("A")))
            as SubmitPracticeOutcome.Recorded
        assertEquals(PracticeResults.CORRECT, recorded.answer.result)

        val old = libraries.questions.getValue("q1")
        val result = editing.saveQuestion(old.copy(answers = listOf("B")), confirm = true)
        assertTrue(result is com.offlinequiz.app.model.SaveOutcome.Saved)
        val corrected = libraries.questions.getValue("q1")
        assertTrue(corrected.confirmed)
        assertEquals(listOf("B"), corrected.answers)
        assertEquals(old.originalAnswers, corrected.originalAnswers)
        assertEquals(snapshot, practice.loadState(first.sessionId)!!.currentQuestion)
        assertEquals(recorded.answer, sessions.answerMap["${first.sessionId}:0"])

        val second = practice.start("lib", PracticeModes.SEQUENTIAL) as StartPracticeOutcome.Started
        assertEquals(listOf("B"), practice.loadState(second.sessionId)!!.currentQuestion!!.answers)
        assertEquals(recorded.answer, sessions.answerMap["${first.sessionId}:0"])
    }

    @Test
    fun confirmingPendingQuestionRemovesItFromAttentionWithoutConfirmingOtherQuestions() = runTest {
        val pending = single("q1", 0).copy(originalNeedsReview = true, originalReviewReasons = listOf("ambiguous"))
        val (libraries, _) = setup(pending, single("q2", 1))
        val editing = com.offlinequiz.app.data.LibraryRepository(libraries)
        val before = libraries.questions.values.toList()
        assertEquals(listOf("q1"), before.filter(com.offlinequiz.app.model.QuestionFilter.ATTENTION::includes).map { it.id })
        editing.saveQuestion(libraries.questions.getValue("q1"), confirm = true)
        assertTrue(libraries.questions.values.none(com.offlinequiz.app.model.QuestionReview::needsAttention))
        assertTrue(libraries.questions.getValue("q1").confirmed)
        assertFalse(libraries.questions.getValue("q2").confirmed)
        assertTrue(libraries.questions.getValue("q1").originalNeedsReview)
    }

    @Test
    fun sequentialStartUsesPositionOrderAndKeepsTheSnapshotAfterQuestionEdit() = runTest {
        val (libraryStore, practiceStore) = setup(fill("q2", 1), fill("q1", 0))
        val repo = repository(libraryStore, practiceStore)
        val started = repo.start("lib", PracticeModes.SEQUENTIAL) as StartPracticeOutcome.Started
        val state = repo.loadState(started.sessionId)!!
        assertEquals(listOf("q1", "q2"), state.session.questions.map { it.questionId })
        assertEquals(listOf("甲"), state.session.questions.first().answers)

        libraryStore.questions["q1"] = libraryStore.questions.getValue("q1")
            .copy(answers = listOf("新答案"))
        val afterEdit = repo.loadState(started.sessionId)!!
        assertEquals(listOf("甲"), afterEdit.session.questions.first().answers)
    }

    @Test
    fun randomStartIsFixedForTheSessionAndContainsEveryQuestionOnce() = runTest {
        val questions = (0 until 6).map { fill("q$it", it) }.toTypedArray()
        val (libraryStore, practiceStore) = setup(*questions)
        val repo = repository(libraryStore, practiceStore)
        val first = repo.start("lib", PracticeModes.RANDOM, Random(42)) as StartPracticeOutcome.Started
        val firstOrder = repo.loadState(first.sessionId)!!.session.questions.map { it.questionId }
        assertEquals(6, firstOrder.size)
        assertEquals(firstOrder.toSet().size, firstOrder.size)
        assertEquals((0 until 6).map { "q$it" }.toSet(), firstOrder.toSet())

        val second = repo.start("lib", PracticeModes.RANDOM, Random(42)) as StartPracticeOutcome.Started
        val secondOrder = repo.loadState(second.sessionId)!!.session.questions.map { it.questionId }
        assertEquals(firstOrder, secondOrder)
        assertNotEquals(first.sessionId, second.sessionId)
        assertEquals(PracticeStatuses.SUPERSEDED, practiceStore.sessions.getValue(first.sessionId).status)
    }

    @Test
    fun emptyLibraryReturnsNoEligibleWithoutCreatingASession() = runTest {
        val (libraryStore, practiceStore) = setup()
        val repo = repository(libraryStore, practiceStore)
        val outcome = repo.start("lib", PracticeModes.SEQUENTIAL)
        assertTrue(outcome is StartPracticeOutcome.NoEligible)
        assertEquals(0, (outcome as StartPracticeOutcome.NoEligible).report.eligible.size)
        assertTrue(practiceStore.sessions.isEmpty())
    }

    @Test
    fun noEligibleQuestionsReturnsReviewReport() = runTest {
        val review = testQuestion(
            id = "review",
            type = QuestionTypes.SINGLE_CHOICE,
            stem = "题干",
            options = options("A" to "甲", "B" to "乙"),
            answers = listOf("A"),
            originalReviewReasons = listOf("no_answer_in_parentheses"),
            confirmed = false
        )
        val (libraryStore, practiceStore) = setup(review)
        val repo = repository(libraryStore, practiceStore)
        val outcome = repo.start("lib", PracticeModes.SEQUENTIAL)
        assertTrue(outcome is StartPracticeOutcome.NoEligible)
        assertEquals(1, (outcome as StartPracticeOutcome.NoEligible).report.reviewExcluded)
        assertTrue(practiceStore.sessions.isEmpty())
    }

    @Test
    fun submitRetryReturnsTheExistingRecordAndDoesNotRecordTwice() = runTest {
        val (libraryStore, practiceStore) = setup(single("q1", 0))
        val repo = repository(libraryStore, practiceStore)
        val started = repo.start("lib", PracticeModes.SEQUENTIAL) as StartPracticeOutcome.Started
        val user = PracticeUserAnswer(selectedKeys = listOf("A"))

        val first = repo.submitAutomatic(started.sessionId, 0, user)
        assertTrue(first is SubmitPracticeOutcome.Recorded)
        assertFalse((first as SubmitPracticeOutcome.Recorded).alreadyRecorded)
        assertEquals(1, practiceStore.answerMap.size)

        val retry = repo.submitAutomatic(started.sessionId, 0, user)
        assertTrue(retry is SubmitPracticeOutcome.Recorded)
        assertTrue((retry as SubmitPracticeOutcome.Recorded).alreadyRecorded)
        assertEquals(1, practiceStore.answerMap.size)
        val restored = repo.loadState(started.sessionId)!!
        assertEquals(PracticePhases.FEEDBACK, restored.session.phase)
        assertEquals(PracticeResults.CORRECT, restored.feedback?.result)
        assertEquals(listOf("A"), restored.feedback?.userAnswer?.selectedKeys)
    }

    @Test
    fun shortAnswerSelfAssessmentIsRecordedAsForgottenWithoutAutomaticJudging() = runTest {
        val (libraryStore, practiceStore) = setup(short("q1", 0))
        val repo = repository(libraryStore, practiceStore)
        val started = repo.start("lib", PracticeModes.SEQUENTIAL) as StartPracticeOutcome.Started
        val sessionId = started.sessionId

        val recorded = repo.submitSelfAssessment(
            sessionId,
            0,
            PracticeUserAnswer(text = "我的简答"),
            remembered = false
        )
        assertTrue(recorded is SubmitPracticeOutcome.Recorded)
        val state = repo.loadState(sessionId)!!
        assertEquals(PracticeResults.FORGOTTEN, state.feedback?.result)
        assertEquals("我的简答", state.feedback?.userAnswer?.text)
        assertEquals(0, state.summary.automaticCorrect)
        assertEquals(0, state.summary.automaticIncorrect)
        assertEquals(0, state.summary.remembered)
        assertEquals(1, state.summary.forgotten)

        val completed = repo.advance(sessionId, 0)
        assertTrue(completed is AdvancePracticeOutcome.Advanced)
        assertEquals(PracticePhases.COMPLETED, (completed as AdvancePracticeOutcome.Advanced).state.session.phase)
    }

    @Test
    fun emptyInputIsRejectedWithoutRecordingAnAnswer() = runTest {
        val (libraryStore, practiceStore) = setup(single("q1", 0))
        val repo = repository(libraryStore, practiceStore)
        val started = repo.start("lib", PracticeModes.SEQUENTIAL) as StartPracticeOutcome.Started

        val outcome = repo.submitAutomatic(started.sessionId, 0, PracticeUserAnswer())
        assertTrue(outcome is SubmitPracticeOutcome.Rejected)
        assertTrue(practiceStore.answerMap.isEmpty())
        assertEquals(PracticePhases.ANSWERING, repo.loadState(started.sessionId)!!.session.phase)
    }

    @Test
    fun failedDraftSaveReportsFailureAndKeepsStoredDraft() = runTest {
        val (libraryStore, practiceStore) = setup(fill("q1", 0))
        val repo = repository(libraryStore, practiceStore)
        val started = repo.start("lib", PracticeModes.SEQUENTIAL) as StartPracticeOutcome.Started
        practiceStore.failDraft = true

        val outcome = repo.saveDraft(started.sessionId, 0, PracticeDraft(text = "失败输入"), 1)
        assertTrue(outcome is DraftSaveOutcome.Failed)
        assertEquals("", repo.loadState(started.sessionId)!!.session.draft.text)
    }

    @Test
    fun failedSubmitDoesNotAdvanceAndCanBeRetried() = runTest {
        val (libraryStore, practiceStore) = setup(single("q1", 0))
        val repo = repository(libraryStore, practiceStore)
        val started = repo.start("lib", PracticeModes.SEQUENTIAL) as StartPracticeOutcome.Started
        val user = PracticeUserAnswer(selectedKeys = listOf("A"))
        practiceStore.failSubmit = true

        val failed = repo.submitAutomatic(started.sessionId, 0, user)
        assertTrue(failed is SubmitPracticeOutcome.Failed)
        assertTrue(practiceStore.answerMap.isEmpty())
        assertEquals(PracticePhases.ANSWERING, repo.loadState(started.sessionId)!!.session.phase)

        practiceStore.failSubmit = false
        val retried = repo.submitAutomatic(started.sessionId, 0, user)
        assertTrue(retried is SubmitPracticeOutcome.Recorded)
        assertEquals(1, practiceStore.answerMap.size)
    }

    @Test
    fun completionSeparatesAutomaticSelfAssessmentAndSkipCounts() = runTest {
        val (libraryStore, practiceStore) = setup(
            single("q1", 0),
            fill("q2", 1),
            short("q3", 2)
        )
        val repo = repository(libraryStore, practiceStore)
        val started = repo.start("lib", PracticeModes.SEQUENTIAL) as StartPracticeOutcome.Started
        val sessionId = started.sessionId

        assertTrue(repo.submitAutomatic(sessionId, 0, PracticeUserAnswer(selectedKeys = listOf("A"))) is SubmitPracticeOutcome.Recorded)
        assertTrue(repo.advance(sessionId, 0) is AdvancePracticeOutcome.Advanced)
        assertTrue(repo.submitSelfAssessment(sessionId, 1, PracticeUserAnswer(blankAnswers = listOf("甲")), remembered = true) is SubmitPracticeOutcome.Recorded)
        assertTrue(repo.advance(sessionId, 1) is AdvancePracticeOutcome.Advanced)
        assertTrue(repo.skip(sessionId, 2, PracticeUserAnswer()) is SubmitPracticeOutcome.Recorded)
        val completed = repo.advance(sessionId, 2)
        assertTrue(completed is AdvancePracticeOutcome.Advanced)
        val state = (completed as AdvancePracticeOutcome.Advanced).state
        assertEquals(PracticePhases.COMPLETED, state.session.phase)
        assertEquals(1, state.summary.automaticCorrect)
        assertEquals(0, state.summary.automaticIncorrect)
        assertEquals(1, state.summary.remembered)
        assertEquals(0, state.summary.forgotten)
        assertEquals(1, state.summary.skipped)
        assertEquals(3, state.summary.total)
        assertEquals(3, state.summary.answered)
    }

    @Test
    fun draftRevisionRejectsAnOlderConcurrentWrite() = runTest {
        val (libraryStore, practiceStore) = setup(fill("q1", 0))
        val repo = repository(libraryStore, practiceStore)
        val started = repo.start("lib", PracticeModes.SEQUENTIAL) as StartPracticeOutcome.Started
        val sessionId = started.sessionId

        assertEquals(DraftSaveOutcome.Saved, repo.saveDraft(sessionId, 0, PracticeDraft(text = "新输入"), 2))
        assertEquals(DraftSaveOutcome.Stale, repo.saveDraft(sessionId, 0, PracticeDraft(text = "旧输入"), 1))
        val state = repo.loadState(sessionId)!!
        assertEquals("新输入", state.session.draft.text)
        assertEquals(2L, state.session.draftRevision)
    }

    @Test
    fun startingNewRoundSupersedesButKeepsPreviousSessionAndAnswers() = runTest {
        val (libraryStore, practiceStore) = setup(single("q1", 0))
        val repo = repository(libraryStore, practiceStore)
        val first = repo.start("lib", PracticeModes.SEQUENTIAL) as StartPracticeOutcome.Started
        assertTrue(repo.submitAutomatic(first.sessionId, 0, PracticeUserAnswer(selectedKeys = listOf("A"))) is SubmitPracticeOutcome.Recorded)
        val firstAnswerCount = practiceStore.answerMap.size

        val second = repo.start("lib", PracticeModes.RANDOM) as StartPracticeOutcome.Started
        assertEquals(PracticeStatuses.SUPERSEDED, practiceStore.sessions.getValue(first.sessionId).status)
        assertEquals(PracticeStatuses.ACTIVE, practiceStore.sessions.getValue(second.sessionId).status)
        assertEquals(firstAnswerCount, practiceStore.answerMap.size)
        assertNotNull(repo.activeSession("lib"))
        assertEquals(second.sessionId, repo.activeSession("lib")!!.id)
    }

    @Test
    fun advanceTwiceDoesNotMoveProgressTwice() = runTest {
        val (libraryStore, practiceStore) = setup(single("q1", 0), fill("q2", 1))
        val repo = repository(libraryStore, practiceStore)
        val started = repo.start("lib", PracticeModes.SEQUENTIAL) as StartPracticeOutcome.Started
        val sessionId = started.sessionId
        assertTrue(repo.submitAutomatic(sessionId, 0, PracticeUserAnswer(selectedKeys = listOf("A"))) is SubmitPracticeOutcome.Recorded)
        assertTrue(repo.advance(sessionId, 0) is AdvancePracticeOutcome.Advanced)
        assertTrue(repo.advance(sessionId, 0) is AdvancePracticeOutcome.Failed)
        assertEquals(1, repo.loadState(sessionId)!!.session.currentIndex)
    }
}
