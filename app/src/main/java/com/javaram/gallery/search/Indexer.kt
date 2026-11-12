package com.javaram.gallery.search

import android.content.Context
import android.graphics.Bitmap
import android.location.Address
import android.location.Geocoder
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.javaram.gallery.data.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class IndexProgress(val done: Int = 0, val total: Int = 0, val running: Boolean = false) {
    val complete: Boolean get() = total > 0 && done >= total
}

/**
 * Looks at each photo once: what's in it (labels), any text in it, and where it was taken.
 * Runs while the app is open and, through [IndexWorker], while the phone charges.
 */
object Indexer {
    private val mutex = Mutex()
    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()

    /** Bumped whenever a batch is saved, so search can reload fresh results. */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    suspend fun run(context: Context, items: List<Media>, deadlineMillis: Long = Long.MAX_VALUE) {
        if (!mutex.tryLock()) return
        val app = context.applicationContext
        val index = SearchIndex.get(app)
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.62f).build())
        val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            withContext(Dispatchers.Default) {
                index.deleteMissing(items.mapTo(HashSet()) { it.id })
                val known = index.versions()
                val todo = items.filter { known[it.id] != it.dateModified }
                val alreadyDone = items.size - todo.size
                _progress.value = IndexProgress(alreadyDone, items.size, todo.isNotEmpty())
                if (todo.isEmpty()) return@withContext
                var done = alreadyDone
                for (m in todo) {
                    if (System.currentTimeMillis() > deadlineMillis) break
                    val entry = try {
                        analyse(app, index, m, labeler, ocr)
                    } catch (e: MlKitException) {
                        // Models are still downloading through Play services: try again later.
                        if (e.errorCode == MlKitException.UNAVAILABLE) break else null
                    } catch (e: Exception) {
                        null
                    }
                    index.put(
                        entry ?: IndexEntry(m.id, m.dateModified, emptyList(), "", null, null, null)
                    )
                    done++
                    _progress.value = IndexProgress(done, items.size, true)
                    if (done % 40 == 0) _generation.value++
                }
                _generation.value++
            }
        } finally {
            labeler.close()
            ocr.close()
            _progress.value = _progress.value.copy(running = false)
            mutex.unlock()
        }
    }

    private suspend fun analyse(
        context: Context,
        index: SearchIndex,
        m: Media,
        labeler: com.google.mlkit.vision.label.ImageLabeler,
        ocr: com.google.mlkit.vision.text.TextRecognizer,
    ): IndexEntry {
        val bitmap: Bitmap? = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(m.uri, Size(1024, 1024), null) }.getOrNull()
        }
        var labels = emptyList<String>()
        var text = ""
        if (bitmap != null) {
            val image = InputImage.fromBitmap(bitmap, 0)
            labels = labeler.process(image).await()
                .sortedByDescending { it.confidence }
                .map { it.text }
                .take(10)
            if (!m.isVideo) {
                text = runCatching { ocr.process(image).await().text }
                    .getOrDefault("")
                    .replace('\n', ' ')
                    .take(1500)
            }
            bitmap.recycle()
        }
        val loc = withContext(Dispatchers.IO) { readLocation(context, m) }
        val place = loc?.let { (lat, lng) -> geocode(context, index, lat, lng) }
        return IndexEntry(m.id, m.dateModified, labels, text, loc?.first, loc?.second, place)
    }

    private fun readLocation(context: Context, m: Media): Pair<Double, Double>? {
        val original = runCatching { MediaStore.setRequireOriginal(m.uri) }.getOrDefault(m.uri)
        val ll: Pair<Double, Double>? = if (m.isVideo) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, original)
                val raw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                raw?.let { s ->
                    Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(s)?.let { match ->
                        val lat = match.groupValues[1].toDoubleOrNull()
                        val lng = match.groupValues[2].toDoubleOrNull()
                        if (lat != null && lng != null) lat to lng else null
                    }
                }
            } catch (e: Exception) {
                null
            } finally {
                runCatching { r.release() }
            }
        } else {
            runCatching {
                context.contentResolver.openInputStream(original)?.use { input ->
                    ExifInterface(input).latLong?.let { it[0] to it[1] }
                }
            }.getOrNull()
        }
        return ll?.takeIf { (lat, lng) -> !(lat == 0.0 && lng == 0.0) && lat in -90.0..90.0 && lng in -180.0..180.0 }
    }

    private suspend fun geocode(context: Context, index: SearchIndex, lat: Double, lng: Double): GeoPlace? {
        val key = String.format(Locale.US, "%.2f,%.2f", lat, lng)
        index.geo(key)?.let { return it }
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val address: Address? = withTimeoutOrNull(8_000) {
            if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    runCatching { geocoder.getFromLocation(lat, lng, 1)?.firstOrNull() }.getOrNull()
                }
            }
        }
        val place = address?.let {
            GeoPlace(
                area = it.subLocality,
                city = it.locality ?: it.subAdminArea,
                state = it.adminArea,
                country = it.countryName,
            )
        } ?: return null
        index.putGeo(key, place)
        return place
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
