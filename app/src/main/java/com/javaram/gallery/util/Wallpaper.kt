package com.javaram.gallery.util

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.view.WindowManager
import com.javaram.gallery.data.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class WallpaperTarget(val label: String, val flags: Int) {
    Home("Home screen", WallpaperManager.FLAG_SYSTEM),
    Lock("Lock screen", WallpaperManager.FLAG_LOCK),
    Both("Home and lock screens", WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK),
}

object Wallpaper {
    /** Sets the photo as wallpaper, centre-cropped to fill the screen exactly. */
    suspend fun set(context: Context, media: Media, target: WallpaperTarget): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            val sw = bounds.width()
            val sh = bounds.height()
            val source = ImageDecoder.createSource(context.contentResolver, media.uri)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // Shrink huge photos while decoding, keeping enough pixels to cover the screen.
                val shortSide = min(info.size.width, info.size.height)
                val sample = (shortSide / min(sw, sh)).coerceAtLeast(1)
                if (sample > 1) decoder.setTargetSampleSize(sample)
            }
            val scale = max(sw.toFloat() / decoded.width, sh.toFloat() / decoded.height)
            val scaled = Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).roundToInt().coerceAtLeast(sw),
                (decoded.height * scale).roundToInt().coerceAtLeast(sh),
                true,
            )
            val x = ((scaled.width - sw) / 2).coerceAtLeast(0)
            val y = ((scaled.height - sh) / 2).coerceAtLeast(0)
            val cropped = Bitmap.createBitmap(scaled, x, y, sw, sh)
            WallpaperManager.getInstance(context).setBitmap(cropped, null, true, target.flags)
            true
        }.getOrDefault(false)
    }
}
