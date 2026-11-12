package com.javaram.gallery

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.javaram.gallery.data.ThumbFetcher
import com.javaram.gallery.data.ThumbKeyer

class GalleryApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(ThumbKeyer())
                add(ThumbFetcher.Factory())
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(180)
            .respectCacheHeaders(false)
            .build()
}
