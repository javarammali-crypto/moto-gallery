package com.javaram.gallery.data

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** A square crop around one face, for round person avatars. Box values are fractions of the photo. */
data class FaceThumb(val uri: Uri, val l: Float, val t: Float, val r: Float, val b: Float)

class FaceThumbFetcher(private val data: FaceThumb, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val src = options.context.contentResolver.loadThumbnail(data.uri, Size(1024, 1024), null)
        val w = src.width
        val h = src.height
        val cx = (data.l + data.r) / 2f * w
        val cy = (data.t + data.b) / 2f * h
        val side = (max((data.r - data.l) * w, (data.b - data.t) * h) * 1.6f).toInt()
            .coerceAtLeast(16).coerceAtMost(minOf(w, h))
        val left = (cx - side / 2f).toInt().coerceIn(0, w - side)
        val top = (cy - side / 2f).toInt().coerceIn(0, h - side)
        val crop = Bitmap.createBitmap(src, left, top, side, side)
        DrawableResult(BitmapDrawable(options.context.resources, crop), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<FaceThumb> {
        override fun create(data: FaceThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            FaceThumbFetcher(data, options)
    }
}

class FaceThumbKeyer : Keyer<FaceThumb> {
    override fun key(data: FaceThumb, options: Options) = "face:${data.uri}:${data.l}:${data.t}:${data.r}:${data.b}"
}
