package com.javaram.gallery.data

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Albums on Android are folders. Creating an album means putting photos into a new
 * folder under Pictures/, either as copies or by moving the originals there.
 */
object AlbumOps {

    /** Folder path for a new album, e.g. "Pictures/Work/". */
    fun newAlbumPath(name: String): String = "Pictures/${sanitize(name)}/"

    fun sanitize(name: String): String =
        name.trim().replace(Regex("""[\\/:*?"<>|\n\r\t]"""), " ").replace(Regex("""\s+"""), " ").trim().take(60)
            .ifEmpty { "New album" }

    /** Android only lets apps place photos under DCIM/ and Pictures/ (videos also under Movies/). */
    fun canHold(path: String): Boolean =
        path.startsWith("DCIM/", true) || path.startsWith("Pictures/", true)

    fun writeRequest(context: Context, items: Collection<Media>): PendingIntent =
        MediaStore.createWriteRequest(context.contentResolver, items.map { it.uri })

    /** Copies items into [path]. Returns how many were copied. */
    suspend fun copy(context: Context, items: List<Media>, path: String, onProgress: (Int) -> Unit = {}): Int =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            var ok = 0
            items.forEachIndexed { i, m ->
                onProgress(i)
                val collection = if (m.isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, m.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, m.mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    put(MediaStore.MediaColumns.DATE_TAKEN, m.dateTaken)
                }
                val target = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return@forEachIndexed
                val copied = runCatching {
                    val src = runCatching { MediaStore.setRequireOriginal(m.uri) }.getOrDefault(m.uri)
                    val input = runCatching { resolver.openInputStream(src) }.getOrNull() ?: resolver.openInputStream(m.uri)
                    input?.use { inp -> resolver.openOutputStream(target)?.use { out -> inp.copyTo(out, 256 * 1024) } } != null
                }.getOrDefault(false)
                if (copied) {
                    resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    ok++
                } else {
                    runCatching { resolver.delete(target, null, null) }
                }
            }
            ok
        }

    /** Moves items into [path] (needs write access granted first). Returns how many moved. */
    suspend fun move(context: Context, items: List<Media>, path: String, onProgress: (Int) -> Unit = {}): Int =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            var ok = 0
            items.forEachIndexed { i, m ->
                onProgress(i)
                if (m.relativePath.equals(path, ignoreCase = true)) { ok++; return@forEachIndexed }
                val moved = runCatching {
                    resolver.update(m.uri, ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, path) }, null, null) > 0
                }.getOrDefault(false)
                if (moved) ok++
            }
            ok
        }
}
