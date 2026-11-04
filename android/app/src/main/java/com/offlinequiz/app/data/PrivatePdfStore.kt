package com.offlinequiz.app.data

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class PdfCopyException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Copying/cleanup operations the repository needs; implemented by [UriPdfArchive] in the app. */
interface PdfArchive {
    /** Copies the selected document into the app-private library folder and returns a relative path. */
    fun copy(libraryId: String): String
    fun delete(relativePath: String)
    fun resolve(relativePath: String): File?
    fun cleanupOrphans(keepLibraryIds: Set<String>)
}

/**
 * Keeps the imported PDF in the app's private files directory. The file is never placed in the APK,
 * shared through a provider, or written to public storage.
 */
class PrivatePdfStore(private val appFilesDir: File) {

    private val root = File(appFilesDir, ROOT_NAME)

    fun copyFromResolver(resolver: ContentResolver, uri: Uri, libraryId: String): String {
        requireSafeId(libraryId)
        val directory = File(root, libraryId)
        val target = File(directory, PDF_NAME)
        val temporary = File(directory, "$PDF_NAME.part")
        try {
            if (!directory.exists() && !directory.mkdirs()) {
                throw PdfCopyException("无法创建应用私有题库目录")
            }
            if (temporary.exists()) temporary.delete()
            val input = resolver.openInputStream(uri)
                ?: throw PdfCopyException("无法读取所选 PDF，请重新选择文件")
            input.use { source ->
                FileOutputStream(temporary).use { output ->
                    source.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            if (temporary.length() == 0L) throw PdfCopyException("所选 PDF 为空")
            if (!hasPdfHeader(temporary)) throw PdfCopyException("所选文件不是有效的 PDF")
            if (target.exists()) target.delete()
            if (!temporary.renameTo(target)) throw PdfCopyException("无法保存 PDF 私有副本")
            return relativePath(libraryId)
        } catch (e: PdfCopyException) {
            deleteDirectory(directory)
            throw e
        } catch (e: IOException) {
            deleteDirectory(directory)
            throw PdfCopyException("保存 PDF 私有副本失败：${e.message ?: "读写错误"}", e)
        } catch (e: SecurityException) {
            deleteDirectory(directory)
            throw PdfCopyException("没有权限读取所选 PDF，请重新选择文件", e)
        } catch (e: Exception) {
            deleteDirectory(directory)
            throw PdfCopyException("保存 PDF 私有副本失败", e)
        }
    }

    fun resolve(relativePath: String?): File? {
        if (relativePath.isNullOrBlank()) return null
        val rootCanonical = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val file = runCatching { File(appFilesDir, relativePath).canonicalFile }.getOrNull() ?: return null
        val rootPrefix = rootCanonical.path + File.separator
        if (!file.path.startsWith(rootPrefix)) return null
        return if (file.isFile && file.length() > 0L) file else null
    }

    fun delete(relativePath: String) {
        val file = resolve(relativePath) ?: return
        val parent = file.parentFile ?: return
        deleteDirectory(parent)
    }

    /**
     * Removes folders with no library row. Only old folders are removed so an import that is in
     * progress (copy finished, transaction not yet committed) is never deleted underneath the app.
     */
    fun cleanupOrphans(keepLibraryIds: Set<String>) {
        val directories = root.listFiles()?.filter { it.isDirectory } ?: return
        val cutoff = System.currentTimeMillis() - ORPHAN_AGE_MS
        for (directory in directories) {
            if (directory.name in keepLibraryIds) continue
            if (directory.lastModified() > cutoff) continue
            deleteDirectory(directory)
        }
    }

    private fun hasPdfHeader(file: File): Boolean {
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(5)
                val read = input.read(header)
                read == 5 && String(header, Charsets.US_ASCII) == "%PDF-"
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun deleteDirectory(directory: File) {
        val rootCanonical = runCatching { root.canonicalFile }.getOrNull() ?: return
        val directoryCanonical = runCatching { directory.canonicalFile }.getOrNull() ?: return
        if (directoryCanonical == rootCanonical) return
        if (!directoryCanonical.path.startsWith(rootCanonical.path + File.separator)) return
        directoryCanonical.deleteRecursively()
    }

    private fun requireSafeId(libraryId: String) {
        if (!libraryId.matches(SAFE_ID)) throw PdfCopyException("题库标识无效，无法保存 PDF")
    }

    private fun relativePath(libraryId: String): String = "$ROOT_NAME/$libraryId/$PDF_NAME"

    companion object {
        private const val ROOT_NAME = "libraries"
        private const val PDF_NAME = "source.pdf"
        private const val ORPHAN_AGE_MS = 60 * 60 * 1000L
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,80}")
    }
}

class UriPdfArchive(
    private val store: PrivatePdfStore,
    private val resolver: ContentResolver,
    private val uri: Uri
) : PdfArchive {
    override fun copy(libraryId: String): String = store.copyFromResolver(resolver, uri, libraryId)
    override fun delete(relativePath: String) = store.delete(relativePath)
    override fun resolve(relativePath: String): File? = store.resolve(relativePath)
    override fun cleanupOrphans(keepLibraryIds: Set<String>) = store.cleanupOrphans(keepLibraryIds)
}
