package com.offlinequiz.app.backup

import java.io.IOException

const val BACKUP_FORMAT_NAME = "offlinequiz-backup"
const val BACKUP_FORMAT_VERSION = 2

/** Machine-readable rejection reasons; the message is user-facing and never contains host paths. */
enum class BackupError {
    NOT_A_BACKUP,
    FUTURE_VERSION,
    UNSUPPORTED_VERSION,
    BAD_MANIFEST,
    BAD_JSON,
    BAD_FIELD,
    BAD_ENUM,
    BAD_INDEX,
    DUPLICATE_ID,
    DUPLICATE_ENTRY,
    UNKNOWN_ENTRY,
    MISSING_ENTRY,
    BAD_PATH,
    TOO_LARGE,
    BAD_RESOURCE,
    CHECKSUM_MISMATCH,
    DANGLING_REF,
    CANCELLED,
    IO
}

class BackupException(
    val error: BackupError,
    override val message: String,
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * Hard limits for both export and import. Tests use small values; production uses the defaults.
 * All values are maxima for one backup file; actual bytes are counted while streaming, never taken
 * from ZipEntry.size or from the manifest.
 */
data class BackupLimits(
    val maxLibraries: Int = 200,
    val maxEntries: Int = 2000,
    val maxManifestBytes: Long = 2L * 1024 * 1024,
    val maxEntryBytes: Long = 64L * 1024 * 1024,
    val maxTotalBytes: Long = 1024L * 1024 * 1024,
    val maxLineBytes: Int = 8 * 1024 * 1024,
    val maxStringBytes: Int = 1024 * 1024,
    val maxQuestionsPerLibrary: Int = 50_000,
    val maxSessionsPerLibrary: Int = 50_000,
    val maxAnswersPerLibrary: Int = 500_000,
    val maxPdfBytes: Long = 256L * 1024 * 1024
)

/** An openable, re-readable PDF source supplied by the caller (file or in-memory bytes). */
fun interface BackupPdfSource {
    fun open(): java.io.InputStream
}

/** Streaming read side implemented by 03D2 over the (consistent) Room snapshot. */
interface BackupSource {
    suspend fun libraries(): List<BackupLibraryHeader>

    suspend fun questions(libraryId: String): List<com.offlinequiz.app.model.StoredQuestion>

    suspend fun sessions(libraryId: String): List<com.offlinequiz.app.model.PracticeSessionRecord>

    suspend fun answers(libraryId: String): List<BackupAnswer>

    /** Null when the source PDF is not available; the library is still exportable without it. */
    suspend fun openPdf(libraryId: String): BackupPdfSource?
}
