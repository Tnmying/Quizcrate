package com.offlinequiz.app

import com.offlinequiz.app.data.DraftSaveOutcome
import com.offlinequiz.app.data.LibraryStore
import com.offlinequiz.app.data.PracticeJson
import com.offlinequiz.app.data.PracticeStore
import com.offlinequiz.app.data.PracticeSubmitResult
import com.offlinequiz.app.model.LibraryRecord
import com.offlinequiz.app.model.LibrarySummary
import com.offlinequiz.app.model.PracticeAnswerKinds
import com.offlinequiz.app.model.PracticeDraft
import com.offlinequiz.app.model.PracticeHistoryEntry
import com.offlinequiz.app.model.PracticePhases
import com.offlinequiz.app.model.PracticeQuestionSnapshot
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
import com.offlinequiz.app.model.toPracticeSnapshot
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * In-memory stores shared by the 03B statistics/wrongbook tests. They intentionally keep their own
 * history so a test can create answers from completed, active and superseded sessions.
 */
class TestLibraryStore : LibraryStore {
    val libraries = linkedMapOf<String, LibraryRecord>()
    val questionMap = linkedMapOf<String, StoredQuestion>()
    private val libraryFlow = MutableStateFlow<LibraryRecord?>(null)
    private val questionsFlow = MutableStateFlow<List<StoredQuestion>>(emptyList())
    var failingQuestionReads = 0

    fun addLibrary(record: LibraryRecord) {
        libraries[record.id] = record
    }

    fun addQuestion(question: StoredQuestion) {
        questionMap[question.id] = question
    }

    /** Pushes the current library/questions into the observation flows used by ViewModels. */
    fun publish(libraryId: String) {
        libraryFlow.value = libraries[libraryId]
        questionsFlow.value = questionMap.values
            .filter { it.libraryId == libraryId }
            .sortedBy { it.position }
    }

    override fun observeSummaries(): Flow<List<LibrarySummary>> = flowOf(emptyList())

    override fun observeLibrary(id: String): Flow<LibraryRecord?> = libraryFlow

    override fun observeQuestions(libraryId: String): Flow<List<StoredQuestion>> = questionsFlow

    override suspend fun questions(libraryId: String): List<StoredQuestion> {
        if (failingQuestionReads > 0) {
            failingQuestionReads -= 1
            throw IOException("simulated questions read failure")
        }
        return questionMap.values.filter { it.libraryId == libraryId }.sortedBy { it.position }
    }

    override suspend fun library(id: String): LibraryRecord? = libraries[id]

    override suspend fun question(id: String): StoredQuestion? = questionMap[id]

    override suspend fun createLibrary(library: LibraryRecord, questions: List<StoredQuestion>) {
        libraries[library.id] = library
        questions.forEach { questionMap[it.id] = it }
    }

    override suspend fun renameLibrary(id: String, name: String, updatedAt: Long) {
        libraries[id] = libraries.getValue(id).copy(name = name, updatedAt = updatedAt)
    }

    override suspend fun saveQuestion(question: StoredQuestion, updatedAt: Long) {
        questionMap[question.id] = question
    }

    override suspend fun libraryIds(): List<String> = libraries.keys.toList()
}

class TestPracticeStore : PracticeStore {
    val sessions = linkedMapOf<String, PracticeSessionRecord>()
    val answerMap = linkedMapOf<String, PracticeRecordedAnswer>()
    val snapshotByAnswer = mutableMapOf<String, PracticeQuestionSnapshot>()
    val activeFlow = MutableStateFlow<PracticeSessionSummary?>(null)
    var historyReads = 0
    var historyFailures = 0
    var submitCalls = 0

    fun addSession(session: PracticeSessionRecord) {
        sessions[session.id] = session
        // Only a still-active session becomes the "continue practice" target.
        if (session.status == PracticeStatuses.ACTIVE) {
            activeFlow.value = summary(session)
        }
    }

    fun addAnswer(answer: PracticeRecordedAnswer, snapshot: PracticeQuestionSnapshot?) {
        answerMap[answer.id] = answer
        if (snapshot != null) snapshotByAnswer[answer.id] = snapshot
    }

    override fun observeActiveSession(libraryId: String): Flow<PracticeSessionSummary?> = activeFlow

    override suspend fun activeSession(libraryId: String): PracticeSessionSummary? = activeFlow.value

    override suspend fun createSession(session: PracticeSessionRecord) {
        if (session.source == PracticeSources.SINGLE_REVIEW) {
            sessions.entries.filter { it.value.libraryId == session.libraryId &&
                it.value.source == PracticeSources.SINGLE_REVIEW && it.value.status == PracticeStatuses.ACTIVE }
                .forEach { it.setValue(it.value.copy(status = PracticeStatuses.SUPERSEDED)) }
        }
        sessions[session.id] = session
        if (session.source != PracticeSources.SINGLE_REVIEW) activeFlow.value = summary(session)
    }

    override suspend fun session(id: String): PracticeSessionRecord? = sessions[id]

    override suspend fun answers(sessionId: String): List<PracticeRecordedAnswer> =
        answerMap.values.filter { it.sessionId == sessionId }.sortedBy { it.position }

    override suspend fun answerAt(sessionId: String, position: Int): PracticeRecordedAnswer? =
        answerMap.values.firstOrNull { it.sessionId == sessionId && it.position == position }

    override suspend fun submitAnswer(
        sessionId: String,
        expectedPosition: Int,
        answer: PracticeRecordedAnswer,
        referenceSnapshotJson: String
    ): PracticeSubmitResult? {
        submitCalls += 1
        val session = sessions[sessionId] ?: return null
        if (session.status != PracticeStatuses.ACTIVE) return null
        if (session.currentIndex != expectedPosition) return null
        if (session.phase != PracticePhases.ANSWERING) return null
        answerMap.values
            .firstOrNull { it.sessionId == sessionId && it.position == expectedPosition }
            ?.let { return PracticeSubmitResult(it, alreadyRecorded = true) }
        answerMap[answer.id] = answer
        PracticeJson.decodeQuestions(referenceSnapshotJson).firstOrNull()?.let {
            snapshotByAnswer[answer.id] = it
        }
        val updated = session.copy(
            phase = PracticePhases.FEEDBACK,
            draft = PracticeDraft(),
            draftRevision = session.draftRevision + 1,
            updatedAt = answer.submittedAt
        )
        sessions[sessionId] = updated
        if (updated.source != PracticeSources.SINGLE_REVIEW) activeFlow.value = summary(updated)
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
        if (session.phase != PracticePhases.FEEDBACK) return null
        if (session.currentIndex != expectedPosition) return null
        if (answerAt(sessionId, expectedPosition) == null) return null
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
        if (updated.source != PracticeSources.SINGLE_REVIEW) activeFlow.value = summary(updated)
        return updated
    }

    override suspend fun saveDraft(
        sessionId: String,
        position: Int,
        draft: PracticeDraft,
        revision: Long,
        now: Long
    ): DraftSaveOutcome = DraftSaveOutcome.Saved

    override suspend fun libraryStatsSnapshot(libraryId: String) =
        com.offlinequiz.app.model.PracticeStatsSnapshot(historyForLibrary(libraryId),
            com.offlinequiz.app.model.PracticeRoundCounts.fromSessions(sessions.values.filter { it.libraryId == libraryId }))

    override suspend fun historyForLibrary(libraryId: String): List<PracticeHistoryEntry> {
        historyReads += 1
        failHistoryIfRequested()
        return entriesFor(libraryId, questionId = null)
    }

    override suspend fun historyForQuestion(
        libraryId: String,
        questionId: String
    ): List<PracticeHistoryEntry> {
        historyReads += 1
        failHistoryIfRequested()
        return entriesFor(libraryId, questionId)
    }

    private fun failHistoryIfRequested() {
        if (historyFailures > 0) {
            historyFailures -= 1
            throw IOException("simulated history read failure")
        }
    }

    private fun entriesFor(libraryId: String, questionId: String?): List<PracticeHistoryEntry> =
        answerMap.values
            .filter { answer ->
                sessions[answer.sessionId]?.libraryId == libraryId &&
                    (questionId == null || answer.questionId == questionId)
            }
            .sortedWith(compareBy({ it.submittedAt }, { it.id }))
            .map { answer ->
                PracticeHistoryEntry(
                    answer = answer,
                    referenceSnapshot = snapshotByAnswer[answer.id],
                    sessionSource = sessions[answer.sessionId]?.source ?: PracticeSources.NORMAL
                )
            }

    private fun summary(session: PracticeSessionRecord): PracticeSessionSummary =
        PracticeSessionSummary(
            id = session.id,
            libraryId = session.libraryId,
            mode = session.mode,
            status = session.status,
            total = session.total,
            currentIndex = session.currentIndex,
            phase = session.phase,
            answeredCount = answerMap.values.count { it.sessionId == session.id },
            createdAt = session.createdAt,
            updatedAt = session.updatedAt,
            source = session.source
        )
}

fun practiceQuestion(
    id: String,
    libraryId: String = "lib",
    position: Int = 0,
    type: String = QuestionTypes.SINGLE_CHOICE,
    stem: String = "题干 $id",
    options: List<QuizOption> = listOf(
        QuizOption("A", "甲", markerIndex = 0),
        QuizOption("B", "乙", markerIndex = 1)
    ),
    answers: List<String> = listOf("A"),
    acceptableAnswers: List<List<String>> = emptyList(),
    referenceAnswer: String? = null,
    confirmed: Boolean = true,
    reviewReasons: List<String> = emptyList()
): StoredQuestion = testQuestion(
    id = id,
    libraryId = libraryId,
    position = position,
    type = type,
    stem = stem,
    options = options,
    answers = answers,
    acceptableAnswers = acceptableAnswers,
    referenceAnswer = referenceAnswer,
    originalReviewReasons = reviewReasons,
    confirmed = confirmed
)

fun practiceAnswer(
    id: String,
    question: StoredQuestion,
    kind: String,
    result: String,
    submittedAt: Long,
    sessionId: String = "session-$id",
    position: Int = 0,
    userAnswer: PracticeUserAnswer = PracticeUserAnswer()
): PracticeRecordedAnswer = PracticeRecordedAnswer(
    id = id,
    sessionId = sessionId,
    questionId = question.id,
    position = position,
    kind = kind,
    result = result,
    userAnswer = userAnswer,
    submittedAt = submittedAt
)

fun historyEntry(
    question: StoredQuestion,
    kind: String,
    result: String,
    submittedAt: Long,
    id: String = "a-$submittedAt-$kind-$result",
    sessionId: String = "s-$id",
    snapshot: PracticeQuestionSnapshot? = question.toPracticeSnapshot(),
    userAnswer: PracticeUserAnswer = PracticeUserAnswer(),
    source: String = PracticeSources.NORMAL
): PracticeHistoryEntry = PracticeHistoryEntry(
    answer = practiceAnswer(
        id = id,
        question = question,
        kind = kind,
        result = result,
        submittedAt = submittedAt,
        sessionId = sessionId,
        userAnswer = userAnswer
    ),
    referenceSnapshot = snapshot,
    sessionSource = source
)

fun practiceSession(
    id: String,
    questions: List<StoredQuestion>,
    libraryId: String = "lib",
    status: String = PracticeStatuses.ACTIVE,
    phase: String = PracticePhases.ANSWERING,
    currentIndex: Int = 0,
    source: String = PracticeSources.NORMAL,
    createdAt: Long = 1_000L
): PracticeSessionRecord = PracticeSessionRecord(
    id = id,
    libraryId = libraryId,
    mode = "sequential",
    status = status,
    questions = questions.map { it.toPracticeSnapshot() },
    currentIndex = currentIndex,
    phase = phase,
    draft = PracticeDraft(),
    draftRevision = 0L,
    createdAt = createdAt,
    updatedAt = createdAt,
    completedAt = if (status == PracticeStatuses.COMPLETED) createdAt else null,
    source = source
)

fun automaticCorrect(question: StoredQuestion, at: Long, id: String = "auto-correct-$at") =
    historyEntry(question, PracticeAnswerKinds.AUTOMATIC, PracticeResults.CORRECT, at, id)

fun automaticIncorrect(question: StoredQuestion, at: Long, id: String = "auto-wrong-$at") =
    historyEntry(question, PracticeAnswerKinds.AUTOMATIC, PracticeResults.INCORRECT, at, id)

fun selfRemembered(question: StoredQuestion, at: Long, id: String = "self-remembered-$at") =
    historyEntry(question, PracticeAnswerKinds.SELF_ASSESSMENT, PracticeResults.REMEMBERED, at, id)

fun selfForgotten(question: StoredQuestion, at: Long, id: String = "self-forgotten-$at") =
    historyEntry(question, PracticeAnswerKinds.SELF_ASSESSMENT, PracticeResults.FORGOTTEN, at, id)

fun skipped(question: StoredQuestion, at: Long, id: String = "skip-$at") =
    historyEntry(question, PracticeAnswerKinds.SKIPPED, PracticeResults.SKIPPED, at, id)

/**
 * Allocates the Application without running its constructor: the ViewModels only store it, and a
 * JVM test must not open a Room database or initialise PDFBox assets.
 */
fun applicationWithoutAndroidRuntime(): OfflineQuizApplication {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
    return allocateInstance.invoke(unsafe, OfflineQuizApplication::class.java) as OfflineQuizApplication
}

/** Runs a ViewModel test with a controllable main dispatcher. */
@OptIn(ExperimentalCoroutinesApi::class)
fun statsViewModelTest(block: suspend TestScope.() -> Unit) = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
        block()
    } finally {
        // Drain pending debounce/delay jobs before the main dispatcher is unset; leaving them
        // queued would make runTest's cleanup touch a removed Dispatchers.Main.
        runCatching { advanceUntilIdle() }
        Dispatchers.resetMain()
    }
}
