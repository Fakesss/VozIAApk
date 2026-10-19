package com.voicestudio.mobile.data.library

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.voicestudio.mobile.local.isNoSpace
import com.voicestudio.mobile.network.ClientFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer

/** Where an export landed, in words a person can find in a file manager. */
data class ExportResult(val uri: Uri, val name: String, val location: String, val size: Long)

/** Removes characters file systems reject, trims, bounds length and keeps the extension. */
fun safeFileName(title: String, extension: String, maxLength: Int = 80): String {
    val ext = extension.lowercase().filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }
    val base = Normalizer.normalize(title, Normalizer.Form.NFC)
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
        .replace(Regex("\\s+"), " ").trim().trim('.', ' ')
        .take(maxLength).trim().trim('.', ' ')
    val stem = base.ifEmpty { "VoiceStudio" }
    // Reserved device names on FAT/exFAT SD cards.
    val reserved = setOf("con", "prn", "aux", "nul") + (1..9).flatMap { listOf("com$it", "lpt$it") }
    return (if (stem.lowercase() in reserved) "_$stem" else stem) + "." + ext
}
/** "name.wav" → "name (2).wav" for the first [taken] collision-free candidate. */
fun uniqueName(name: String, taken: (String) -> Boolean): String {
    if (!taken(name)) return name
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    val stem = name.substring(0, dot); val ext = name.substring(dot)
    for (n in 2..9999) { val candidate = "$stem ($n)$ext"; if (!taken(candidate)) return candidate }
    throw ClientFailure("vozia.export.name_exhausted")
}

/**
 * Copies private library files to places other apps can open: the public
 * Downloads collection (MediaStore, no permission on Android 10+), a folder the
 * person picked once with the Storage Access Framework, or a single document from
 * "Save as". Existing files are never overwritten; every copy is read back and
 * compared by SHA-256 before it is reported as saved.
 */
class Exporter(context: Context, private val library: Library) {
    private val app = context.applicationContext
    private val resolver: ContentResolver = app.contentResolver

    suspend fun toDownloads(item: LibraryItem, folder: String?, extension: String = extensionOf(item)): ExportResult = io {
        val source = sourceOf(item)
        requireSpace(publicFreeBytes(), source.length())
        val relative = Environment.DIRECTORY_DOWNLOADS + "/VoiceStudio" + (folder?.let { "/" + safeFolder(it) } ?: "")
        val name = uniqueName(safeFileName(item.title, extension)) { candidate -> downloadsContain(relative, candidate) }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mime ?: "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw ClientFailure("vozia.export.failed")
        try {
            copyVerified(source, uri)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val (finalName, finalPath) = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use {
                if (it.moveToFirst()) (it.getString(0) ?: name) to (it.getString(1) ?: relative) else name to relative
            } ?: (name to relative)
            val location = finalPath.trimEnd('/') + "/" + finalName
            library.recordExport(item.id, uri.toString(), location, finalName, source.length())
            ExportResult(uri, finalName, location, source.length())
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw mapFailure(e)
        }
    }

    /** [tree] comes from ACTION_OPEN_DOCUMENT_TREE with a persisted grant. */
    suspend fun toFolder(item: LibraryItem, tree: Uri, extension: String = extensionOf(item)): ExportResult = io {
        val source = sourceOf(item)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val existing = childNames(tree)
        val name = uniqueName(safeFileName(item.title, extension)) { it in existing }
        val uri = try { DocumentsContract.createDocument(resolver, parent, item.mime ?: "application/octet-stream", name) } catch (e: Exception) { throw mapFailure(e) }
            ?: throw ClientFailure("vozia.export.folder_unavailable")
        try {
            copyVerified(source, uri)
            val finalName = displayName(uri) ?: name
            val location = folderLabel(tree) + "/" + finalName
            library.recordExport(item.id, uri.toString(), location, finalName, source.length())
            ExportResult(uri, finalName, location, source.length())
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw mapFailure(e)
        }
    }

    /** "Save as": the system picker already asked about names and replacement. */
    suspend fun toDocument(item: LibraryItem, uri: Uri): ExportResult = io {
        val source = sourceOf(item)
        try { copyVerified(source, uri) } catch (e: Exception) { throw mapFailure(e) }
        val name = displayName(uri) ?: safeFileName(item.title, extensionOf(item))
        library.recordExport(item.id, uri.toString(), name, name, source.length())
        ExportResult(uri, name, name, source.length())
    }

    fun persistFolder(tree: Uri) {
        resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    fun folderGranted(tree: Uri): Boolean = resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
    fun folderLabel(tree: Uri): String = runCatching {
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        displayName(document)
    }.getOrNull() ?: DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':')

    private fun sourceOf(item: LibraryItem): File {
        val file = library.file(item) ?: throw ClientFailure("vozia.export.missing")
        if (!file.isFile || file.length() != item.size) throw ClientFailure("vozia.export.missing")
        if (item.sha256 != null && Library.sha256(file) != item.sha256) throw ClientFailure("vozia.storage.verify_failed")
        return file
    }
    private suspend fun copyVerified(source: File, uri: Uri) {
        val expected = MessageDigest.getInstance("SHA-256")
        (resolver.openOutputStream(uri, "wt") ?: throw ClientFailure("vozia.export.failed")).use { output ->
            source.inputStream().use { input ->
                val bytes = ByteArray(1 shl 16)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(bytes); if (n < 0) break
                    expected.update(bytes, 0, n); output.write(bytes, 0, n)
                }
                output.flush()
            }
        }
        val actual = MessageDigest.getInstance("SHA-256")
        var size = 0L
        (resolver.openInputStream(uri) ?: throw ClientFailure("vozia.storage.verify_failed")).use { input ->
            val bytes = ByteArray(1 shl 16)
            while (true) { val n = input.read(bytes); if (n < 0) break; actual.update(bytes, 0, n); size += n }
        }
        if (size != source.length() || !actual.digest().contentEquals(expected.digest())) throw ClientFailure("vozia.storage.verify_failed")
    }
    private fun downloadsContain(relative: String, name: String): Boolean = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
        arrayOf("$relative/", name), null)?.use { it.count > 0 } ?: false
    private fun childNames(tree: Uri): Set<String> = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }.orEmpty()
    }.getOrDefault(emptySet())
    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()
    @Suppress("DEPRECATION")
    private fun publicFreeBytes(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)
    private fun requireSpace(free: Long, needed: Long) { if (free < needed + 4L * 1024 * 1024) throw ClientFailure("vozia.export.no_space") }
    private fun mapFailure(e: Exception): Exception = when {
        e is ClientFailure -> e
        isNoSpace(e) -> ClientFailure("vozia.export.no_space")
        e is SecurityException -> ClientFailure("vozia.export.folder_unavailable")
        else -> ClientFailure("vozia.export.failed")
    }
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        fun extensionOf(item: LibraryItem): String = item.file?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() } ?: when (item.mime) {
            "audio/wav" -> "wav"; "audio/mp4" -> "m4a"; "video/mp4" -> "mp4"; "application/x-subrip" -> "srt"; "text/vtt" -> "vtt"; else -> "txt"
        }
        fun safeFolder(name: String): String = safeFileName(name, "x").removeSuffix(".x")
    }
}
