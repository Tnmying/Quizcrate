package com.offlinequiz.app.backup

import com.offlinequiz.app.model.LibraryRecord
import com.offlinequiz.app.model.PracticeQuestionSnapshot
import com.offlinequiz.app.model.PracticeRecordedAnswer
import com.offlinequiz.app.model.PracticeSessionRecord
import com.offlinequiz.app.model.StoredQuestion

/** Manifest-level library metadata; never contains a device path or the private PDF path. */
data class BackupLibraryMeta(
    val ordinal: Int,
    val record: LibraryRecord,
    val questionCount: Int,
    val sessionCount: Int,
    val answerCount: Int,
    val pdf: BackupPdfInfo?,
    val hasSourceText: Boolean = record.sourceText != null
)

/** A PDF payload described by the manifest and validated while streaming. */
data class BackupPdfInfo(
    val entry: String,
    val byteSize: Long,
    val sha256: String
)

enum class BackupEntryKind { QUESTIONS, SESSIONS, ANSWERS, PDF, TEXT }

/** One non-manifest ZIP entry with its expected role and record count. */
data class BackupEntryRef(
    val path: String,
    val kind: BackupEntryKind,
    val ordinal: Int,
    val recordCount: Int
)

data class BackupManifest(
    val createdAt: Long,
    val databaseVersion: Int,
    val libraries: List<BackupLibraryMeta>,
    val entries: List<BackupEntryRef>
)

/** An answer plus the historical snapshot captured at submit time (null = unavailable). */
data class BackupAnswer(
    val answer: PracticeRecordedAnswer,
    val referenceSnapshot: PracticeQuestionSnapshot?
)

/** Fully validated, in-memory backup content (bounded by [BackupLimits]). */
data class BackupBundle(
    val manifest: BackupManifest,
    val libraries: List<BackupLibrary>
)

data class BackupLibrary(
    val record: LibraryRecord,
    val questions: List<StoredQuestion>,
    val sessions: List<PracticeSessionRecord>,
    val answers: List<BackupAnswer>,
    val pdf: BackupPdfInfo?
)

/** Export-side header supplied by [BackupSource] before the data itself is streamed. */
data class BackupLibraryHeader(
    val record: LibraryRecord,
    val questionCount: Int,
    val sessionCount: Int,
    val answerCount: Int,
    val pdfAvailable: Boolean
)

/** Summary that 03D2 can show before asking the user to confirm a restore. */
data class RestoreSummary(
    val libraryCount: Int,
    val questionCount: Int,
    val sessionCount: Int,
    val answerCount: Int,
    val pdfIncludedLibraries: Int,
    val pdfMissingLibraries: Int,
    val pendingReviewQuestions: Int,
    val activeSessions: Int,
    val completedSessions: Int,
    val supersededSessions: Int,
    val textSourceLibraries: Int = 0
)

/** Pure plan; generating it never writes Room or copies files. */
data class RestorePlan(
    val summary: RestoreSummary,
    val libraries: List<RestoreLibrary>
)

data class RestoreLibrary(
    val library: LibraryRecord,
    val questions: List<StoredQuestion>,
    val sessions: List<PracticeSessionRecord>,
    val answers: List<RestoreAnswer>,
    val pdf: RestorePdf?
)

data class RestoreAnswer(
    val answer: PracticeRecordedAnswer,
    val referenceSnapshot: PracticeQuestionSnapshot?,
    val idempotencyKey: String
)

data class RestorePdf(
    val entry: String,
    val byteSize: Long,
    val sha256: String,
    /** New private relative path 03D2 must copy the entry bytes to. */
    val relativePath: String
)

fun interface BackupIdFactory {
    fun newId(): String
}

fun libraryEntryOrdinal(ordinal: Int): String = ordinal.toString()

fun questionsEntry(ordinal: Int): String = "libraries/${libraryEntryOrdinal(ordinal)}/questions.ndjson"

fun sessionsEntry(ordinal: Int): String = "libraries/${libraryEntryOrdinal(ordinal)}/sessions.ndjson"

fun answersEntry(ordinal: Int): String = "libraries/${libraryEntryOrdinal(ordinal)}/answers.ndjson"

fun pdfEntry(ordinal: Int): String = "libraries/${libraryEntryOrdinal(ordinal)}/resources/source.pdf"

fun textEntry(ordinal: Int): String = "libraries/${libraryEntryOrdinal(ordinal)}/resources/source.txt"

const val MANIFEST_ENTRY = "manifest.json"
