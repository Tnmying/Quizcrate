package com.offlinequiz.app

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offlinequiz.app.data.PdfCopyException
import com.offlinequiz.app.data.PdfPageRenderer
import com.offlinequiz.app.data.PdfRenderException
import com.offlinequiz.app.data.PrivatePdfStore
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-only coverage for the private PDF copy and page renderer.
 *
 * Not executed here (no device/emulator). Run via the checklist in docs/reports/02b-device-checklist.md.
 */
@RunWith(AndroidJUnit4::class)
class PdfStorageDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun copyFromContentResolverStoresPrivateCopyAndDeleteRemovesIt() {
        val source = File(context.cacheDir, "source-${System.nanoTime()}.pdf")
        createPdf(source)
        val store = PrivatePdfStore(context.filesDir)
        val libraryId = "device-test-${System.nanoTime()}"
        try {
            val relative = store.copyFromResolver(context.contentResolver, Uri.fromFile(source), libraryId)
            val copied = store.resolve(relative)
            assertNotNull(copied)
            assertTrue(copied!!.length() > 0)
            assertTrue(copied.inputStream().use { input ->
                val header = ByteArray(5)
                input.read(header) == 5 && String(header, Charsets.US_ASCII) == "%PDF-"
            })

            store.delete(relative)
            assertNull(store.resolve(relative))
        } finally {
            source.delete()
        }
    }

    @Test
    fun nonPdfInputFailsWithoutLeavingBrokenCopy() {
        val source = File(context.cacheDir, "not-pdf-${System.nanoTime()}.bin")
        source.writeText("not a pdf")
        val store = PrivatePdfStore(context.filesDir)
        val libraryId = "device-bad-${System.nanoTime()}"
        try {
            store.copyFromResolver(context.contentResolver, Uri.fromFile(source), libraryId)
            fail("expected PdfCopyException")
        } catch (e: PdfCopyException) {
            assertTrue(e.message!!.contains("PDF"))
        } finally {
            source.delete()
        }
        assertNull(store.resolve("libraries/$libraryId/source.pdf"))
    }

    @Test
    fun rendersOnlyTheRequestedPageAndRejectsOutOfRangePage() {
        val source = File(context.cacheDir, "render-${System.nanoTime()}.pdf")
        createPdf(source)
        try {
            val bitmap = PdfPageRenderer.render(source, 1, targetWidth = 720)
            assertTrue(bitmap.width > 0)
            assertTrue(bitmap.height > 0)
            bitmap.recycle()

            try {
                PdfPageRenderer.render(source, 2)
                fail("expected PdfRenderException")
            } catch (e: PdfRenderException) {
                assertTrue(e.message!!.contains("第 2 页"))
            }
        } finally {
            source.delete()
        }
    }

    private fun createPdf(file: File) {
        val document = PDDocument()
        try {
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            val content = PDPageContentStream(document, page)
            try {
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 14f)
                content.newLineAtOffset(72f, 720f)
                content.showText("OfflineQuiz private source page")
                content.endText()
            } finally {
                content.close()
            }
            document.save(file)
        } finally {
            document.close()
        }
    }
}
