package com.offlinequiz.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.IOException
import pdfprobe.Model
import pdfprobe.QuestionParser

class PdfImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class ImportedPdf(
    val fileName: String,
    val pageCount: Int,
    val glyphCount: Int,
    val underlineCount: Int,
    val matchedUnderlineCount: Int,
    val result: Model.ParseResult,
    val sourceText: String? = null
)

object PdfBoxAndroidParser {

    fun parse(context: Context, uri: Uri, defaultType: String = "unknown"): ImportedPdf {
        val fileName = displayName(context, uri)
        val temp = File.createTempFile("offlinequiz-import-", ".pdf", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw PdfImportException("无法打开所选文件")
            input.use { source ->
                temp.outputStream().use { target -> source.copyTo(target) }
            }
            if (temp.length() <= 0L) throw PdfImportException("所选文件为空")

            val document = try {
                PDDocument.load(temp)
            } catch (e: InvalidPasswordException) {
                throw PdfImportException("PDF 已加密，当前版本无法读取", e)
            } catch (e: IOException) {
                throw PdfImportException("PDF 读取失败，文件可能损坏或格式不支持", e)
            }

            try {
                if (document.isEncrypted) {
                    throw PdfImportException("PDF 已加密，当前版本无法读取")
                }
                val geometry = AndroidPdfExtractor.extract(document)
                if (geometry.glyphCount == 0) {
                    throw PdfImportException("PDF 没有可提取的文字层，暂不支持扫描件")
                }
                val result = QuestionParser.parse(geometry, defaultType)
                if (result.questions.isEmpty()) {
                    throw PdfImportException("没有识别到题目，请确认文件包含文字型题库")
                }
                val matched = geometry.underlines.count { it.matched }
                return ImportedPdf(
                    fileName = fileName,
                    pageCount = document.numberOfPages,
                    glyphCount = geometry.glyphCount,
                    underlineCount = geometry.underlines.size,
                    matchedUnderlineCount = matched,
                    result = result
                )
            } finally {
                document.close()
            }
        } catch (e: PdfImportException) {
            throw e
        } catch (e: IOException) {
            throw PdfImportException("导入失败：${e.message ?: "读取错误"}", e)
        } finally {
            temp.delete()
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment ?: "所选 PDF"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "所选 PDF"
        }
    }
}
