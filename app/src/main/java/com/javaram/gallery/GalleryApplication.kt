package com.javaram.gallery

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.javaram.gallery.data.ThumbFetcher
import com.javaram.gallery.data.ThumbKeyer

class GalleryApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        runCatching { com.javaram.gallery.search.IndexWorker.schedule(this) }
        runCatching {
            org.osmdroid.config.Configuration.getInstance().apply {
                userAgentValue = packageName
                osmdroidBasePath = java.io.File(cacheDir, "osmdroid")
                osmdroidTileCache = java.io.File(cacheDir, "osmdroid/tiles")
            }
        }
    }

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
