package com.offlinequiz.app.backup

import com.offlinequiz.app.model.LibraryRecord
import com.offlinequiz.app.model.PracticeSessionRecord
import com.offlinequiz.app.model.StoredQuestion

/** One library's complete backup data, grouped from a single DB snapshot. */
data class BackupLibraryData(
    val record: LibraryRecord,
    val questions: List<StoredQuestion>,
    val sessions: List<PracticeSessionRecord>,
    val answers: List<BackupAnswer>
)

data class BackupSnapshot(val libraries: List<BackupLibraryData>)

/**
 * 03D2 boundary: Room reads/writes stay behind this interface so the service can be exercised on
 * the JVM with an in-memory implementation and fault injection.
 */
interface BackupDataStore {
    /** Must read every table from one consistent DB transaction (all session statuses). */
    suspend fun readSnapshot(): BackupSnapshot

    /** One transaction, ABORT on every conflict; never replace/clear. */
    suspend fun insert(plan: RestorePlan)

    /** Null when the source PDF is unavailable; the library can still be exported. */
    fun openPdf(record: LibraryRecord): BackupPdfSource?
}
