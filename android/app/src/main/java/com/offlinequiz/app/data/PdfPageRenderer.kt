package com.offlinequiz.app.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

class PdfRenderException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Renders exactly one source page with the platform PdfRenderer, then closes the page, renderer and
 * file descriptor. No whole-document render or cache is kept.
 */
object PdfPageRenderer {

    fun render(file: File, pageNumber: Int, targetWidth: Int = 1440): Bitmap {
        if (!file.isFile || file.length() == 0L) throw PdfRenderException("原 PDF 私有副本不存在")
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            if (pageNumber < 1 || pageNumber > renderer.pageCount) {
                throw PdfRenderException("原 PDF 中没有第 $pageNumber 页")
            }
            page = renderer.openPage(pageNumber - 1)
            val scale = min(
                targetWidth.toFloat() / page.width.coerceAtLeast(1),
                MAX_HEIGHT.toFloat() / page.height.coerceAtLeast(1)
            )
            val width = (page.width * scale).roundToInt().coerceAtLeast(1)
            val height = (page.height * scale).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        } catch (e: PdfRenderException) {
            throw e
        } catch (e: Exception) {
            throw PdfRenderException("无法预览原页，该 PDF 可能不支持系统渲染", e)
        } finally {
            runCatching { page?.close() }
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }
    }

    private const val MAX_HEIGHT = 2400f
}
