package com.javaram.gallery.search

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Size
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.javaram.gallery.data.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds faces in photos and groups them into people, entirely on the phone.
 * Detection: ML Kit. Recognition: a FaceNet model turning each face into 128 numbers;
 * faces whose numbers are close enough belong to the same person.
 */
object FaceIndexer {
    private const val MODEL_URL =
        "https://raw.githubusercontent.com/javarammali-crypto/moto-gallery/main/models/facenet.tflite"
    private const val MODEL_SHA256 = "d7c1f7f130376982c7004920ddc41925ac2e5aecf6522f476c8bbb3669db7013"
    private const val INPUT = 160

    /** Similarity needed to join an existing person. */
    private const val MATCH = 0.62f
    /** Two people this similar are treated as one. */
    private const val MERGE = 0.74f

    private val mutex = Mutex()
    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    private fun modelFile(context: Context) = File(context.filesDir, "models/facenet.tflite")

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var r: Int
            while (input.read(buf).also { r = it } >= 0) md.update(buf, 0, r)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Downloads the face model once (about 24 MB). Returns null if offline. */
    private fun ensureModel(context: Context): File? {
        val f = modelFile(context)
        if (f.exists() && f.length() > 1_000_000) return f
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "facenet.part")
        return runCatching {
            var url = MODEL_URL
            var conn: HttpURLConnection
            var hops = 0
            while (true) {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "Gallery")
                }
                if (conn.responseCode in 300..399 && hops < 5) {
                    url = conn.getHeaderField("Location"); conn.disconnect(); hops++; continue
                }
                break
            }
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
            conn.disconnect()
            if (sha256(tmp) != MODEL_SHA256) { tmp.delete(); error("Model checksum mismatch") }
            tmp.renameTo(f)
            f
        }.getOrNull()
    }

    suspend fun run(context: Context, items: List<Media>, deadlineMillis: Long = Long.MAX_VALUE) {
        if (!mutex.tryLock()) return
        val app = context.applicationContext
        try {
            withContext(Dispatchers.Default) {
                val index = SearchIndex.get(app)
                val photos = items.filter { !it.isVideo }
                index.deleteMissingFaces(photos.mapTo(HashSet()) { it.id })
                val done = index.faceDoneVersions()
                val todo = photos.filter { done[it.id] != it.dateModified }
                var count = photos.size - todo.size
                _progress.value = IndexProgress(count, photos.size, todo.isNotEmpty())
                if (todo.isEmpty()) return@withContext

                val model = withContext(Dispatchers.IO) { ensureModel(app) } ?: return@withContext
                val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
                val detector = FaceDetection.getClient(
                    FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                        .setMinFaceSize(0.06f)
                        .build()
                )
                val people = index.loadPersonCentroids()
                val input = ByteBuffer.allocateDirect(INPUT * INPUT * 3 * 4).order(ByteOrder.nativeOrder())
                val output = Array(1) { FloatArray(128) }
                try {
                    for (m in todo) {
                        if (System.currentTimeMillis() > deadlineMillis) break
                        val bitmap = withContext(Dispatchers.IO) {
                            runCatching { app.contentResolver.loadThumbnail(m.uri, Size(1024, 1024), null) }.getOrNull()
                        }
                        val stored = ArrayList<StoredFace>()
                        if (bitmap != null) {
                            val faces = try {
                                detector.process(InputImage.fromBitmap(bitmap, 0)).await()
                            } catch (e: MlKitException) {
                                if (e.errorCode == MlKitException.UNAVAILABLE) { bitmap.recycle(); break }
                                emptyList()
                            } catch (e: Exception) {
                                emptyList()
                            }
                            for (face in faces) {
                                val box = face.boundingBox
                                if (box.width() < 40 || box.height() < 40) continue
                                if (abs(face.headEulerAngleY) > 45f) continue
                                val crop = cropFace(bitmap, face) ?: continue
                                val emb = embed(interpreter, crop, input, output)
                                crop.recycle()
                                val w = bitmap.width.toFloat()
                                val h = bitmap.height.toFloat()
                                val area = (box.width() * box.height()) / (w * h)
                                // Assign to the closest person, or start a new one.
                                var best: PersonCentroid? = null
                                var bestSim = -1f
                                for (p in people) {
                                    val s = Embeddings.cosine(emb, p.centroid)
                                    if (s > bestSim) { bestSim = s; best = p }
                                }
                                val person = if (best != null && bestSim >= MATCH) best else {
                                    val id = index.createPerson(emb)
                                    PersonCentroid(id, emb, 0, 0f, null).also { people += it }
                                }
                                person.centroid = Embeddings.blend(person.centroid, person.count, emb, 1)
                                person.count += 1
                                stored += StoredFace(
                                    left = (box.left / w).coerceIn(0f, 1f),
                                    top = (box.top / h).coerceIn(0f, 1f),
                                    right = (box.right / w).coerceIn(0f, 1f),
                                    bottom = (box.bottom / h).coerceIn(0f, 1f),
                                    area = area,
                                    embedding = emb,
                                    person = person.id,
                                )
                            }
                            bitmap.recycle()
                        }
                        val ids = index.putFaces(m.id, m.dateModified, stored)
                        // Keep each person's cover as their clearest (largest) face.
                        stored.forEachIndexed { i, f ->
                            val p = people.first { it.id == f.person }
                            val cover = if (f.area > p.coverArea) { p.coverArea = f.area; ids.getOrNull(i) } else null
                            index.savePerson(p, cover)
                        }
                        count++
                        _progress.value = IndexProgress(count, photos.size, true)
                        if (count % 50 == 0) _generation.value++
                    }
                    mergeSimilar(index)
                } finally {
                    interpreter.close()
                    detector.close()
                }
                _generation.value++
            }
        } finally {
            _progress.value = _progress.value.copy(running = false)
            mutex.unlock()
        }
    }

    /** Joins people whose average face is nearly identical (same person split early on). */
    private fun mergeSimilar(index: SearchIndex) {
        var merged = true
        var rounds = 0
        while (merged && rounds < 20) {
            merged = false
            rounds++
            val people = index.loadPersonCentroids().sortedByDescending { it.count }
            outer@ for (i in people.indices) {
                for (j in i + 1 until people.size) {
                    val a = people[i]
                    val b = people[j]
                    if (a.name != null && b.name != null && a.name != b.name) continue
                    if (Embeddings.cosine(a.centroid, b.centroid) >= MERGE) {
                        index.mergePersons(from = b.id, into = a.id)
                        merged = true
                        break@outer
                    }
                }
            }
        }
    }

    /** Square crop around the face with some margin, rotated so the eyes are level. */
    private fun cropFace(src: Bitmap, face: Face): Bitmap? {
        val box = face.boundingBox
        val size = max(box.width(), box.height()) * 1.25f
        val cx = box.exactCenterX()
        val cy = box.exactCenterY()
        if (size < 20f) return null
        val out = Bitmap.createBitmap(INPUT, INPUT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val scale = INPUT / size
        val m = Matrix().apply {
            postTranslate(-cx, -cy)
            postRotate(-face.headEulerAngleZ)
            postScale(scale, scale)
            postTranslate(INPUT / 2f, INPUT / 2f)
        }
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Runs FaceNet: per-image standardisation in, L2-normalised 128-d fingerprint out. */
    private fun embed(interpreter: Interpreter, face: Bitmap, input: ByteBuffer, output: Array<FloatArray>): FloatArray {
        val px = IntArray(INPUT * INPUT)
        face.getPixels(px, 0, INPUT, 0, 0, INPUT, INPUT)
        val vals = FloatArray(px.size * 3)
        var k = 0
        for (p in px) {
            vals[k++] = ((p shr 16) and 0xFF).toFloat()
            vals[k++] = ((p shr 8) and 0xFF).toFloat()
            vals[k++] = (p and 0xFF).toFloat()
        }
        val mean = vals.average().toFloat()
        var varSum = 0f
        for (v in vals) varSum += (v - mean) * (v - mean)
        val std = max(sqrt(varSum / vals.size), 1f / sqrt(vals.size.toFloat()))
        input.rewind()
        for (v in vals) input.putFloat((v - mean) / std)
        input.rewind()
        interpreter.run(input, output)
        return Embeddings.normalize(output[0].copyOf())
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
