package com.javaram.gallery.data

import android.net.Uri
import androidx.compose.runtime.Immutable

@Immutable
data class Media(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mimeType: String,
    /** Epoch millis when the photo was taken (falls back to modified time). */
    val dateTaken: Long,
    /** Epoch seconds of last modification. Used to bust thumbnail caches after edits. */
    val dateModified: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val bucketId: Long,
    val bucketName: String,
    val relativePath: String,
    val isFavorite: Boolean,
    /** Epoch seconds when a trashed item will be removed for good (0 when not trashed). */
    val dateExpires: Long = 0L,
    /** Clockwise rotation stored in EXIF (0, 90, 180, 270). */
    val orientation: Int = 0,
) {
    /** Width as the photo is displayed, after rotation. */
    val displayWidth: Int get() = if (orientation == 90 || orientation == 270) height else width
    val displayHeight: Int get() = if (orientation == 90 || orientation == 270) width else height

    val isVideo: Boolean get() = mimeType.startsWith("video")
    val isGif: Boolean get() = mimeType == "image/gif"
}

@Immutable
data class Album(
    val key: String,
    val name: String,
    val cover: Media?,
    val count: Int,
    val latest: Long,
    val pinned: Boolean = false,
)

/** Thumbnail request for Coil, served from the system MediaStore thumbnail cache. */
data class MediaThumb(val uri: Uri, val version: Long)

fun Media.thumb() = MediaThumb(uri, dateModified)

object AlbumKeys {
    const val FAVORITES = "favorites"
    const val VIDEOS = "videos"
    const val ALL = "all"
}
