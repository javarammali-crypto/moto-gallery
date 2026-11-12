package com.javaram.gallery.data

import android.graphics.drawable.BitmapDrawable
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import coil.size.pxOrElse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads grid thumbnails through ContentResolver.loadThumbnail, which reuses the
 * system's cached thumbnails. Much faster than decoding full-size photos.
 */
class ThumbFetcher(
    private val data: MediaThumb,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val w = options.size.width.pxOrElse { 320 }.coerceIn(96, 720)
        val h = options.size.height.pxOrElse { 320 }.coerceIn(96, 720)
        val bitmap = options.context.contentResolver.loadThumbnail(data.uri, Size(w, h), null)
        DrawableResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<MediaThumb> {
        override fun create(data: MediaThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            ThumbFetcher(data, options)
    }
}

class ThumbKeyer : Keyer<MediaThumb> {
    override fun key(data: MediaThumb, options: Options): String = thumbKey(data)
}

fun thumbKey(data: MediaThumb) = "thumb:${data.uri}:${data.version}"
