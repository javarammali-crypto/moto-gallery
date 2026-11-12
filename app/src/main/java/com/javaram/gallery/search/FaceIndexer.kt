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

    /** Bump to regroup everyone already found when the matching rules change. */
    private const val GROUPING_VERSION = 2
    private const val MIN_FACE_PX = 60

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
                if (prefs(app).getInt("grouping", 0) < GROUPING_VERSION) {
                    regroup(index)
                    prefs(app).edit().putInt("grouping", GROUPING_VERSION).apply()
                    _generation.value++
                }
                if (todo.isEmpty()) return@withContext

                val model = withContext(Dispatchers.IO) { ensureModel(app) } ?: return@withContext
                val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
                val detector = FaceDetection.getClient(
                    FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                        .setMinFaceSize(0.06f)
                        .build()
                )
                val ex = index.exemplars()
                val groups = index.loadPersonCentroids().map {
                    FaceMatch.Group(it.id, it.centroid, it.count, ex[it.id]?.toMutableList() ?: ArrayList(), it.name)
                }.toMutableList()
                val coverAreas = index.loadPersonCentroids().associate { it.id to it.coverArea }.toMutableMap()
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
                                if (box.width() < MIN_FACE_PX || box.height() < MIN_FACE_PX) continue
                                if (abs(face.headEulerAngleY) > 40f) continue
                                val crop = cropFace(bitmap, face) ?: continue
                                // Average the face with its mirror image: steadier fingerprints.
                                val e1 = embed(interpreter, crop, input, output)
                                val flipped = mirror(crop)
                                val e2 = embed(interpreter, flipped, input, output)
                                flipped.recycle()
                                crop.recycle()
                                val emb = Embeddings.normalize(FloatArray(e1.size) { e1[it] + e2[it] })
                                val w = bitmap.width.toFloat()
                                val h = bitmap.height.toFloat()
                                val area = (box.width() * box.height()) / (w * h)
                                // Assign to the best-fitting person, or start a new one.
                                val person = FaceMatch.best(emb, groups) ?: run {
                                    val id = index.createPerson(emb)
                                    FaceMatch.Group(id, emb, 0, ArrayList(), null).also { groups += it }
                                }
                                FaceMatch.add(person, emb)
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
                            val g = groups.first { it.id == f.person }
                            val prev = coverAreas[g.id] ?: 0f
                            val cover = if (f.area > prev) { coverAreas[g.id] = f.area; ids.getOrNull(i) } else null
                            index.savePerson(PersonCentroid(g.id, g.centroid, g.count, coverAreas[g.id] ?: 0f, g.name), cover)
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

    private fun prefs(context: Context) = context.getSharedPreferences("faces", Context.MODE_PRIVATE)

    private fun loadGroups(index: SearchIndex): MutableList<FaceMatch.Group> {
        val ex = index.exemplars()
        return index.loadPersonCentroids().map {
            FaceMatch.Group(it.id, it.centroid, it.count, ex[it.id]?.toMutableList() ?: ArrayList(), it.name)
        }.toMutableList()
    }

    /** Joins people that turn out to be the same person (split early on). */
    private fun mergeSimilar(index: SearchIndex) {
        val notSame = index.notSamePairs()
        var rounds = 0
        while (rounds++ < 200) {
            val groups = loadGroups(index).sortedByDescending { it.count }
            var pair: Pair<FaceMatch.Group, FaceMatch.Group>? = null
            outer@ for (i in groups.indices) for (j in i + 1 until groups.size) {
                val a = groups[i]
                val b = groups[j]
                if ((minOf(a.id, b.id) to maxOf(a.id, b.id)) in notSame) continue
                if (FaceMatch.shouldMerge(a, b)) { pair = a to b; break@outer }
            }
            val (a, b) = pair ?: break
            index.mergePersons(from = b.id, into = a.id)
        }
    }

    /**
     * Regroups every face already found using the current rules, keeping the names and
     * hidden choices the user already made.
     */
    private fun regroup(index: SearchIndex) {
        val faces = index.facesForRegroup()
        if (faces.isEmpty()) return
        val old = index.oldPersons().associateBy { it.id }
        val groups = ArrayList<FaceMatch.Group>()
        val members = ArrayList<MutableList<SearchIndex.FaceRow>>()
        for (f in faces) {
            val g = FaceMatch.best(f.emb, groups)
            if (g == null) {
                groups += FaceMatch.Group(groups.size.toLong(), f.emb, 1, mutableListOf(f.emb), null)
                members += mutableListOf(f)
            } else {
                FaceMatch.add(g, f.emb)
                members[g.id.toInt()] += f
            }
        }
        // Carry names over: each new group takes the most common named old person among its faces.
        groups.forEachIndexed { i, g ->
            val tally = members[i].mapNotNull { old[it.person] }.groupingBy { it }.eachCount()
            g.name = tally.entries.filter { it.key.name != null }.maxByOrNull { it.value }?.key?.name
        }
        // Merge groups that are clearly the same person, or that share a name.
        val alive = BooleanArray(groups.size) { true }
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in groups.indices) {
                if (!alive[i]) continue
                for (j in i + 1 until groups.size) {
                    if (!alive[j]) continue
                    val a = groups[i]
                    val b = groups[j]
                    val sameName = a.name != null && a.name.equals(b.name, true)
                    if (sameName || FaceMatch.shouldMerge(a, b)) {
                        FaceMatch.absorb(a, b)
                        members[i].addAll(members[j])
                        alive[j] = false
                        changed = true
                        continue@loop
                    }
                }
            }
        }
        val result = groups.indices.filter { alive[it] }.map { i ->
            val g = groups[i]
            val m = members[i]
            val cover = m.maxBy { it.area }
            val hiddenVotes = m.mapNotNull { old[it.person] }.count { it.hidden }
            SearchIndex.NewPerson(
                name = g.name,
                hidden = hiddenVotes * 2 > m.size,
                centroid = g.centroid,
                count = m.size,
                coverFace = cover.id,
                coverArea = cover.area,
                faceIds = m.map { it.id },
            )
        }
        index.rewritePeople(result)
    }

    /** Pairs of people who might be the same, for the "Same person?" cards. */
    suspend fun suggestions(context: Context, visible: Set<Long>): List<Pair<Long, Long>> =
        withContext(Dispatchers.Default) {
            val index = SearchIndex.get(context.applicationContext)
            val notSame = index.notSamePairs()
            val groups = loadGroups(index).filter { it.id in visible }
            val out = ArrayList<Triple<Long, Long, Float>>()
            for (i in groups.indices) for (j in i + 1 until groups.size) {
                val a = groups[i]
                val b = groups[j]
                if ((minOf(a.id, b.id) to maxOf(a.id, b.id)) in notSame) continue
                if (a.name != null && b.name != null) continue
                val c = FaceMatch.closeness(a, b)
                if (c >= FaceMatch.SUGGEST) out += Triple(a.id, b.id, c)
            }
            out.sortedByDescending { it.third }.take(12).map { it.first to it.second }
        }

    private fun mirror(src: Bitmap): Bitmap {
        val m = Matrix().apply { preScale(-1f, 1f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Square crop around the face with some margin, rotated so the eyes are level. */
    private fun cropFace(src: Bitmap, face: Face): Bitmap? {
        val box = face.boundingBox
        val size = max(box.width(), box.height()) * 1.1f
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
