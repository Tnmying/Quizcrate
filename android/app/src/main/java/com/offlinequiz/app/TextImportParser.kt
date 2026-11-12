package com.offlinequiz.app

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import pdfprobe.Model
import pdfprobe.QuestionParser

/** Text input uses logical lines (page 0), without manufacturing PDF geometry. */
object TextImportParser {
    const val MAX_BYTES = 512 * 1024
    const val MAX_LINES = 20_000

    fun decode(input: InputStream, encoding: String = "自动（UTF-8）"): String {
        val bytes = input.readBytesLimited()
        val (charset, offset) = when {
            bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte() -> Charsets.UTF_8 to 3
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE to 2
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE to 2
            encoding == "GB18030" -> Charset.forName("GB18030") to 0
            else -> Charsets.UTF_8 to 0
        }
        val text = try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("文字编码无法读取，请切换 GB18030 编码后重新选择文件")
        }
        require(!text.contains('\u0000')) { "文件包含非文字内容，请选择 TXT 或 PDF" }
        return validate(text)
    }

    fun validate(text: String): String {
        require(text.isNotBlank()) { "文字内容不能为空" }
        require(!text.contains('\u0000')) { "内容包含非文字字符，请检查文件或文字编码" }
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "文字内容超过 512 KB，请分批导入" }
        require(Regex("\r\n|\n|\r").findAll(text).take(MAX_LINES).count() < MAX_LINES) { "文字行数超过 20000 行，请分批导入" }
        return text
    }

    fun parse(text: String, fileName: String = "粘贴文本.txt", defaultType: String = "unknown"): ImportedPdf {
        validate(text)
        val geometry = Model.Geometry()
        text.split(Regex("\r\n|\n|\r")).forEachIndexed { index, value ->
            geometry.lines.add(Model.Line().apply {
                page = 0
                lineNo = index + 1
                globalIndex = index
                this.text = value
            })
        }
        val result = QuestionParser.parse(geometry, defaultType)
        result.questions.forEach { it.sourcePages.clear() }
        require(result.questions.isNotEmpty()) { "未找到题目，请检查题号（如 1.、1、或（1）），并选择题型" }
        return ImportedPdf(fileName, 0, text.length, 0, 0, result, text)
    }

    private fun InputStream.readBytesLimited(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "文件超过 512 KB，请分批导入" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
