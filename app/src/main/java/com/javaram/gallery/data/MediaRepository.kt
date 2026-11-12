package com.javaram.gallery.data

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface

class MediaRepository(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    private val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
        MediaStore.MediaColumns.DURATION,
        MediaStore.MediaColumns.BUCKET_ID,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.IS_FAVORITE,
        MediaStore.MediaColumns.DATE_EXPIRES,
        MediaStore.MediaColumns.ORIENTATION,
    )

    /** Loads every photo and video. When [trashed] is true, only items in the trash are returned. */
    fun load(trashed: Boolean = false): List<Media> {
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                )
            )
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )
            if (trashed) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        val out = ArrayList<Media>(4096)
        runCatching {
            resolver.query(collection, projection, args, null)?.use { c -> readAll(c, out) }
        }
        // Sort by capture time; MediaStore can't sort on the computed fallback.
        out.sortByDescending { it.dateTaken }
        return out
    }

    private fun readAll(c: Cursor, out: MutableList<Media>) {
        val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val takenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        val modCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val wCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        val hCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        val durCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
        val bucketCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
        val bucketNameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        val pathCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
        val favCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_FAVORITE)
        val expCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_EXPIRES)
        val oriCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.ORIENTATION)

        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            val isVideo = c.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val modified = c.getLong(modCol)
            val added = c.getLong(addedCol)
            val taken = c.getLong(takenCol).takeIf { it > 0 }
                ?: (if (modified > 0) modified else added) * 1000L
            out += Media(
                id = id,
                uri = ContentUris.withAppendedId(base, id),
                name = c.getString(nameCol) ?: "",
                mimeType = c.getString(mimeCol) ?: if (isVideo) "video/*" else "image/*",
                dateTaken = taken,
                dateModified = modified,
                size = c.getLong(sizeCol),
                width = c.getInt(wCol),
                height = c.getInt(hCol),
                durationMs = c.getLong(durCol),
                bucketId = c.getLong(bucketCol),
                bucketName = c.getString(bucketNameCol) ?: "Other",
                relativePath = c.getString(pathCol) ?: "",
                isFavorite = c.getInt(favCol) == 1,
                dateExpires = c.getLong(expCol),
                orientation = c.getInt(oriCol),
            )
        }
    }

    fun trashRequest(items: Collection<Media>, trash: Boolean): PendingIntent =
        MediaStore.createTrashRequest(resolver, items.map { it.uri }, trash)

    fun deleteRequest(items: Collection<Media>): PendingIntent =
        MediaStore.createDeleteRequest(resolver, items.map { it.uri })

    fun favoriteRequest(items: Collection<Media>, favorite: Boolean): PendingIntent =
        MediaStore.createFavoriteRequest(resolver, items.map { it.uri }, favorite)

    fun canManageMedia(): Boolean = MediaStore.canManageMedia(context)

    fun readExif(media: Media): ExifDetails? {
        if (media.isVideo) return null
        return runCatching {
            val uri = runCatching { MediaStore.setRequireOriginal(media.uri) }.getOrDefault(media.uri)
            val stream = runCatching { resolver.openInputStream(uri) }.getOrNull()
                ?: resolver.openInputStream(media.uri)
            stream?.use { input ->
                val exif = ExifInterface(input)
                ExifDetails(
                    make = exif.getAttribute(ExifInterface.TAG_MAKE),
                    model = exif.getAttribute(ExifInterface.TAG_MODEL),
                    fNumber = exif.getAttribute(ExifInterface.TAG_F_NUMBER),
                    exposure = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME),
                    iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
                    focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                        .takeIf { it > 0 },
                    latLong = exif.latLong?.let { it[0] to it[1] },
                )
            }
        }.getOrNull()
    }
}

data class ExifDetails(
    val make: String?,
    val model: String?,
    val fNumber: String?,
    val exposure: String?,
    val iso: String?,
    val focalLength: Double?,
    val latLong: Pair<Double, Double>?,
)
