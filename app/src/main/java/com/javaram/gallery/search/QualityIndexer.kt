package com.javaram.gallery.search

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import com.javaram.gallery.data.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Quick per-photo checks used by Cleanup: a 64-bit "looks like" fingerprint (to spot near-identical
 * shots) and a sharpness score (to spot blurry ones). Uses small system thumbnails, so it's fast.
 */
object QualityIndexer {
    private val mutex = Mutex()
    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    suspend fun run(context: Context, items: List<Media>, deadlineMillis: Long = Long.MAX_VALUE) {
        if (!mutex.tryLock()) return
        try {
            withContext(Dispatchers.Default) {
                val index = SearchIndex.get(context.applicationContext)
                val photos = items.filter { !it.isVideo }
                val known = index.qualityVersions()
                val todo = photos.filter { known[it.id] != it.dateModified }
                var done = photos.size - todo.size
                _progress.value = IndexProgress(done, photos.size, todo.isNotEmpty())
                val batch = ArrayList<Triple<Long, Long, SearchIndex.Quality>>()
                for (m in todo) {
                    if (System.currentTimeMillis() > deadlineMillis) break
                    val bmp = runCatching {
                        context.contentResolver.loadThumbnail(m.uri, Size(256, 256), null)
                    }.getOrNull()
                    val q = if (bmp != null) measure(bmp).also { bmp.recycle() } else SearchIndex.Quality(0L, -1f)
                    batch += Triple(m.id, m.dateModified, q)
                    done++
                    if (batch.size >= 100) {
                        index.putQuality(batch); batch.clear()
                        _progress.value = IndexProgress(done, photos.size, true)
                        _generation.value++
                    }
                }
                index.putQuality(batch)
                _progress.value = IndexProgress(done, photos.size, false)
                _generation.value++
            }
        } finally {
            _progress.value = _progress.value.copy(running = false)
            mutex.unlock()
        }
    }

    private fun gray(p: Int): Int = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

    fun measure(src: Bitmap): SearchIndex.Quality {
        // dHash: compare neighbouring pixels of a 9x8 grey image.
        val small = Bitmap.createScaledBitmap(src, 9, 8, true)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (gray(small.getPixel(x, y)) > gray(small.getPixel(x + 1, y))) hash = hash or (1L shl bit)
            bit++
        }
        small.recycle()
        // Sharpness: variance of the Laplacian on a ~256px grey image.
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val g = IntArray(px.size) { gray(px[it]) }
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val lap = (g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - 4 * g[i]).toDouble()
            sum += lap; sumSq += lap * lap; n++
        }
        val variance = if (n > 0) (sumSq / n - (sum / n) * (sum / n)).toFloat() else 0f
        return SearchIndex.Quality(hash, variance)
    }
}
