package com.offlinequiz.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.offlinequiz.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Isolated in-memory Room; compiled here, actual execution requires a connected device. */
@RunWith(AndroidJUnit4::class)
class LibraryMaintenanceDatabaseTest {
    private lateinit var db: QuizDatabase
    @Before fun setup() = runBlocking {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), QuizDatabase::class.java)
            .allowMainThreadQueries().build()
        for (id in listOf("a", "b")) {
            db.libraryDao().insertLibrary(LibraryEntity(id,id,1,1,"test.pdf",1,"libraries/$id/source.pdf",3))
            db.practiceDao().insertSession(PracticeSessionEntity("s-$id",id,"sequential","active","[]","[]",0,
                "feedback","{}",0,1,1,null))
            db.practiceDao().insertAnswer(PracticeAnswerEntity("r-$id","s-$id","q-$id",0,"automatic","{}",
                "incorrect","[]","s-$id:0",1))
        }
    }
    @After fun close() { db.close() }
    @Test fun endingKeepsAnswersWithoutCompletionAndRejectsOtherLibrarySession() = runBlocking {
        assertFalse(db.maintenanceDao().endRound("a","s-b",2))
        assertTrue(db.maintenanceDao().endRound("a","s-a",2))
        assertEquals("superseded",db.practiceDao().session("s-a")!!.status)
        assertNull(db.practiceDao().session("s-a")!!.completedAt)
        assertEquals(1,db.practiceDao().answers("s-a").size)
        assertEquals(0,db.practiceDao().completedRoundCounts("a").normalCompleted)
    }
    @Test fun resetClearsOnlyTargetHistoryAndPreservesLibraryPdf() = runBlocking {
        assertTrue(db.maintenanceDao().resetPractice("a"))
        assertNull(db.practiceDao().session("s-a"))
        assertTrue(db.practiceDao().answers("s-a").isEmpty())
        assertEquals("libraries/a/source.pdf",db.libraryDao().library("a")!!.pdfRelativePath)
        assertNotNull(db.practiceDao().session("s-b"))
        assertEquals(1,db.practiceDao().answers("s-b").size)
    }
    @Test fun deletingLibraryCascadesHistoryButNotOtherLibrary() = runBlocking {
        assertEquals(DeletedLibrary("libraries/a/source.pdf"),db.maintenanceDao().deleteLibrary("a"))
        assertNull(db.libraryDao().library("a"))
        assertNull(db.practiceDao().session("s-a"))
        assertTrue(db.practiceDao().answers("s-a").isEmpty())
        assertNotNull(db.libraryDao().library("b"))
        assertEquals(1,db.practiceDao().answers("s-b").size)
        assertNull(db.maintenanceDao().deleteLibrary("a"))
    }
}
