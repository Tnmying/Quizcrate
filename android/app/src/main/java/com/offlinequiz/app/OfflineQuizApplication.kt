package com.offlinequiz.app

import android.app.Application
import com.offlinequiz.app.data.LibraryRepository
import com.offlinequiz.app.data.PracticeRepository
import com.offlinequiz.app.data.PracticeStatsRepository
import com.offlinequiz.app.data.PrivatePdfStore
import com.offlinequiz.app.data.QuizDatabase
import com.offlinequiz.app.data.RoomLibraryStore
import com.offlinequiz.app.data.RoomPracticeStore
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class OfflineQuizApplication : Application() {

    val database: QuizDatabase by lazy { QuizDatabase.create(this) }

    private val libraryStore by lazy { RoomLibraryStore(database.libraryDao()) }

    private val practiceStore by lazy { RoomPracticeStore(database.practiceDao()) }

    val repository: LibraryRepository by lazy { LibraryRepository(libraryStore) }

    val maintenanceRepository by lazy {
        com.offlinequiz.app.data.LibraryMaintenanceRepository(database.maintenanceDao(), deletePdf = { path ->
            pdfStore.delete(path)
            check(pdfStore.resolve(path) == null) { "PDF cleanup incomplete" }
        })
    }

    val practiceRepository: PracticeRepository by lazy { PracticeRepository(libraryStore, practiceStore) }

    val practiceStatsRepository: PracticeStatsRepository by lazy {
        PracticeStatsRepository(libraryStore, practiceStore)
    }

    val pdfStore: PrivatePdfStore by lazy { PrivatePdfStore(filesDir) }

    override fun onCreate() {
        super.onCreate()
        // PdfBox-Android loads glyph/CMap resources from its packaged assets.
        PDFBoxResourceLoader.init(this)
        // A process restart must never silently resume a backup request. These are only the
        // app-private cache temp files used by BackupService; libraries/PDFs are untouched.
        java.io.File(cacheDir, "backup").listFiles()?.forEach { it.delete() }
    }
}
