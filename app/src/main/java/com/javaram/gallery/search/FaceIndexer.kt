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
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.javaram.gallery.data.Media
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds faces in photos and groups them into people, entirely on the phone.
 * Detection and face landmarks: ML Kit. Recognition: an ArcFace model (InsightFace
 * MobileFaceNet, bundled in the app) that turns each lined-up face into 512 numbers;
 * faces whose numbers are close belong to the same person.
 *
 * Switching face recognition off never cancels work. A scan in progress stops at its next
 * checkpoint, and each write is checked under [FaceScanGate] (see its guarantees).
 */
object FaceIndexer {
    private const val MODEL_ASSET = "face_arcface.tflite"
    private const val INPUT = 112
    private const val DIM = 512

    /** Bump when the face model changes: every photo is scanned again and people regrouped. */
    private const val MODEL_VERSION = 2
    private const val MIN_FACE_PX = 40
    private const val THUMB_PX = 1600
    private const val ENABLED_KEY = "enabled"

    /** Where the eyes and mouth corners sit in the 112×112 face the model was trained on. */
    private val TEMPLATE = floatArrayOf(
        38.2946f, 51.6963f, // eye on the left of the picture
        73.5318f, 51.5014f, // eye on the right
        41.5493f, 92.3655f, // mouth corner, left
        70.7299f, 92.2041f, // mouth corner, right
    )

    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    @Volatile private var gateInstance: FaceScanGate? = null

    /** One switch per process. The saved choice is read the first time anything needs it. */
    private fun gateFor(context: Context): FaceScanGate = synchronized(this) {
        gateInstance ?: prefs(context.applicationContext).let { store ->
            FaceScanGate(store.getBoolean(ENABLED_KEY, true)) { on ->
                store.edit().putBoolean(ENABLED_KEY, on).apply()
            }
        }.also { gateInstance = it }
    }

    private fun loadModel(context: Context): java.nio.MappedByteBuffer =
        context.assets.openFd(MODEL_ASSET).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).channel.use { ch ->
                ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }

    /** Whether face recognition is on. Saved on the phone; on by default. */
    fun isEnabled(context: Context): Boolean = gateFor(context).isEnabled

    /**
     * Switches face recognition on or off and saves the choice. Returns false when it was already in
     * that state. Saved faces and people are never deleted by a switch.
     */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        val changed = gateFor(context).setEnabled(enabled)
        if (changed) _progress.value = _progress.value.copy(running = false)
        return changed
    }

    /**
     * Scans [items] for faces, unless face recognition is off. Scans run one at a time, and a scan
     * that was queued before a switch does nothing. Photos a scan didn't finish are scanned on a later run.
     */
    suspend fun run(context: Context, items: List<Media>, deadlineMillis: Long = Long.MAX_VALUE) {
        val app = context.applicationContext
        val gate = gateFor(app)
        gate.scan { scan ->
            try {
                withContext(Dispatchers.Default) { scanAll(app, gate, scan, items, deadlineMillis) }
            } finally {
                _progress.value = _progress.value.copy(running = false)
            }
        }
    }

    private suspend fun scanAll(
        app: Context,
        gate: FaceScanGate,
        scan: FaceScanGate.Scan,
        items: List<Media>,
        deadlineMillis: Long,
    ) {
        if (!canContinue(gate, scan)) return
        val index = SearchIndex.get(app)
        val prefs = prefs(app)
        if (prefs.getInt("model", 1) < MODEL_VERSION) {
            // New face model: remember names by position, forget the old faces, scan everything again.
            gate.commit(scan) {
                index.startFaceRescan()
                File(app.filesDir, "models/facenet.tflite").delete()
                File(app.filesDir, "models/facenet.part").delete()
                prefs.edit().putInt("model", MODEL_VERSION).putBoolean("rescanning", true).apply()
                _generation.value++
            } ?: return
        }
        val photos = items.filter { !it.isVideo }
        gate.commit(scan) { index.deleteMissingFaces(photos.mapTo(HashSet()) { it.id }) } ?: return
        val done = index.faceDoneVersions()
        // Newest photos first, so recent people show up soonest.
        val todo = photos.filter { done[it.id] != it.dateModified }.sortedByDescending { it.dateTaken }
        var count = photos.size - todo.size
        report(gate, scan, IndexProgress(count, photos.size, todo.isNotEmpty()))
        if (todo.isEmpty()) {
            if (prefs.getBoolean("rescanning", false)) {
                if (!regroupAll(gate, scan, index, prefs)) return
                gate.commit(scan) {
                    index.clearAnchors()
                    prefs.edit().putBoolean("rescanning", false).apply()
                } ?: return
            }
            return
        }

        val rescanning = prefs.getBoolean("rescanning", false)
        // Checkpoint before anything is loaded for the face models.
        if (!canContinue(gate, scan)) return
        val groups = loadGroups(index)
        val input = ByteBuffer.allocateDirect(INPUT * INPUT * 3 * 4).order(ByteOrder.nativeOrder())
        val output = Array(1) { FloatArray(DIM) }
        var newFaces = 0
        var sinceRegroup = 0
        var finished = true
        val interpreter = Interpreter(loadModel(app), Interpreter.Options().setNumThreads(4))
        try {
            val detector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setMinFaceSize(0.04f)
                    .build()
            )
            try {
                for (m in todo) {
                    if (!canContinue(gate, scan) || System.currentTimeMillis() > deadlineMillis) { finished = false; break }
                    val bitmap = withContext(Dispatchers.IO) { thumbnail(app, m) }
                    val stored: List<StoredFace> = if (bitmap == null) emptyList() else {
                        val found = try {
                            scanFaces(scan, gate, bitmap, detector, interpreter, input, output, groups)
                        } finally {
                            // Released only after ML Kit has finished with it (see awaitTask).
                            bitmap.recycle()
                        }
                        // Stopped by a switch, or ML Kit unavailable: this photo stays unscanned for next time.
                        if (found == null) { finished = false; break }
                        found
                    }
                    // Checked and written under the switch lock: nothing commits once a switch-off has returned.
                    if (gate.commit(scan) { index.putFaces(m.id, m.dateModified, stored) } == null) {
                        finished = false
                        break
                    }
                    newFaces += stored.size
                    sinceRegroup++
                    count++
                    report(gate, scan, IndexProgress(count, photos.size, true))
                    // While rescanning, regroup now and then so people appear as the scan goes.
                    if (rescanning && sinceRegroup >= 400) {
                        if (!regroupAll(gate, scan, index, prefs)) { finished = false; break }
                        groups.clear(); groups += loadGroups(index)
                        sinceRegroup = 0
                    }
                    if (count % 50 == 0) gate.commit(scan) { _generation.value++ }
                }
            } finally {
                detector.close()
            }
        } finally {
            interpreter.close()
        }
        // Switched off during the scan: no final regroup; the rescan flags stay for the next run.
        if (!canContinue(gate, scan)) return
        when {
            rescanning -> {
                if (!regroupAll(gate, scan, index, prefs)) return
                if (finished) {
                    gate.commit(scan) {
                        index.clearAnchors()
                        prefs.edit().putBoolean("rescanning", false).apply()
                    }
                }
            }
            // Enough new faces that weren't recognised: regroup everyone so new people form.
            newFaces >= 150 || index.unassignedFaceCount() - prefs.getInt("unassigned", 0) >= 60 ->
                regroupAll(gate, scan, index, prefs)
        }
        gate.commit(scan) { _generation.value++ }
    }

    /** A scan goes on only while its coroutine is active and the switch hasn't changed. */
    private suspend fun canContinue(gate: FaceScanGate, scan: FaceScanGate.Scan): Boolean =
        currentCoroutineContext().isActive && gate.isLive(scan)

    /** Sets progress only while the scan is still live, so a stopped scan can't overwrite it. */
    private fun report(gate: FaceScanGate, scan: FaceScanGate.Scan, value: IndexProgress) {
        gate.commit(scan) { _progress.value = value }
    }

    private fun thumbnail(app: Context, m: Media): Bitmap? =
        try {
            app.contentResolver.loadThumbnail(m.uri, Size(THUMB_PX, THUMB_PX), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /**
     * Groups every face again from scratch, keeping the names and hidden choices. Returns false when the
     * switch stopped it. Clustering runs without the switch lock; only the write is checked.
     */
    private suspend fun regroupAll(gate: FaceScanGate, scan: FaceScanGate.Scan, index: SearchIndex, prefs: android.content.SharedPreferences): Boolean {
        if (!canContinue(gate, scan)) return false
        val result = FaceCluster.cluster(index.facesForCluster(), index.anchors())
        return gate.commit(scan) {
            index.rewritePeople(result.people)
            // Remember how many faces nobody claimed, so only new ones trigger the next full regroup.
            prefs.edit().putInt("unassigned", index.unassignedFaceCount()).apply()
            _generation.value++
        } != null
    }

    private fun prefs(context: Context) = context.getSharedPreferences("faces", Context.MODE_PRIVATE)

    private fun loadGroups(index: SearchIndex): MutableList<FaceMatch.Group> {
        val ex = index.exemplars()
        return index.loadPersonCentroids().map {
            FaceMatch.Group(it.id, it.centroid, it.count, ex[it.id]?.toMutableList() ?: ArrayList(), it.name)
        }.toMutableList()
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

    /**
     * Finds, lines up and fingerprints the faces in one photo. Returns null when the scan must stop:
     * the switch changed, or ML Kit is unavailable.
     */
    private suspend fun scanFaces(
        scan: FaceScanGate.Scan,
        gate: FaceScanGate,
        bitmap: Bitmap,
        detector: FaceDetector,
        interpreter: Interpreter,
        input: ByteBuffer,
        output: Array<FloatArray>,
        groups: MutableList<FaceMatch.Group>,
    ): List<StoredFace>? {
        val faces = try {
            detector.process(InputImage.fromBitmap(bitmap, 0)).awaitTask()
        } catch (e: CancellationException) {
            throw e
        } catch (e: MlKitException) {
            if (e.errorCode == MlKitException.UNAVAILABLE) return null
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        // The detector's answer may arrive after a switch: drop it before any embedding.
        if (!canContinue(gate, scan)) return null
        val stored = ArrayList<StoredFace>()
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        for (face in faces) {
            if (!canContinue(gate, scan)) return null
            val box = face.boundingBox
            if (box.width() < MIN_FACE_PX || box.height() < MIN_FACE_PX) continue
            if (abs(face.headEulerAngleY) > 50f || abs(face.headEulerAngleX) > 40f) continue
            val aligned = alignFace(bitmap, face) ?: continue
            // Average the face with its mirror image: steadier fingerprints.
            val e1 = embed(interpreter, aligned, input, output)
            val flipped = mirror(aligned)
            val e2 = embed(interpreter, flipped, input, output)
            flipped.recycle()
            val q = quality(face, aligned)
            aligned.recycle()
            val emb = Embeddings.normalize(FloatArray(DIM) { e1[it] + e2[it] })
            // Join an existing person only on a clear match; otherwise wait for the next regroup.
            val g = FaceMatch.best(emb, groups)
            val person = if (g != null && q >= 0.3f && stored.none { it.person == g.id }) {
                FaceMatch.add(g, emb); g.id
            } else -1L
            stored += StoredFace(
                left = (box.left / w).coerceIn(0f, 1f),
                top = (box.top / h).coerceIn(0f, 1f),
                right = (box.right / w).coerceIn(0f, 1f),
                bottom = (box.bottom / h).coerceIn(0f, 1f),
                area = (box.width() * box.height()) / (w * h),
                embedding = emb,
                person = person,
                quality = q,
            )
        }
        return stored
    }

    private fun mirror(src: Bitmap): Bitmap {
        val m = Matrix().apply { preScale(-1f, 1f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /**
     * Lines the face up the way the model expects: eyes and mouth corners moved onto fixed
     * spots in a 112×112 picture (a best-fit turn, zoom and shift). Falls back to the eyes
     * alone when the mouth isn't found.
     */
    private fun alignFace(src: Bitmap, face: Face): Bitmap? {
        fun pt(type: Int) = face.getLandmark(type)?.position
        val e1 = pt(FaceLandmark.LEFT_EYE)
        val e2 = pt(FaceLandmark.RIGHT_EYE)
        if (e1 == null || e2 == null) return null
        val (eyeL, eyeR) = if (e1.x <= e2.x) e1 to e2 else e2 to e1
        val m1 = pt(FaceLandmark.MOUTH_LEFT)
        val m2 = pt(FaceLandmark.MOUTH_RIGHT)
        val srcPts = ArrayList<Float>()
        val dstPts = ArrayList<Float>()
        srcPts += listOf(eyeL.x, eyeL.y, eyeR.x, eyeR.y)
        dstPts += TEMPLATE.slice(0..3)
        if (m1 != null && m2 != null) {
            val (mL, mR) = if (m1.x <= m2.x) m1 to m2 else m2 to m1
            srcPts += listOf(mL.x, mL.y, mR.x, mR.y)
            dstPts += TEMPLATE.slice(4..7)
        }
        val m = similarity(srcPts.toFloatArray(), dstPts.toFloatArray()) ?: return null
        val out = Bitmap.createBitmap(INPUT, INPUT, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Least-squares turn + zoom + shift mapping [src] points onto [dst] points (x, y pairs). */
    private fun similarity(src: FloatArray, dst: FloatArray): Matrix? {
        val n = src.size / 2
        var sx = 0f; var sy = 0f; var dx = 0f; var dy = 0f
        for (i in 0 until n) { sx += src[2 * i]; sy += src[2 * i + 1]; dx += dst[2 * i]; dy += dst[2 * i + 1] }
        sx /= n; sy /= n; dx /= n; dy /= n
        var a = 0f; var b = 0f; var norm = 0f
        for (i in 0 until n) {
            val px = src[2 * i] - sx; val py = src[2 * i + 1] - sy
            val qx = dst[2 * i] - dx; val qy = dst[2 * i + 1] - dy
            a += px * qx + py * qy
            b += px * qy - py * qx
            norm += px * px + py * py
        }
        if (norm < 1e-3f) return null
        val c = a / norm
        val s = b / norm
        val tx = dx - (c * sx - s * sy)
        val ty = dy - (s * sx + c * sy)
        return Matrix().apply { setValues(floatArrayOf(c, -s, tx, s, c, ty, 0f, 0f, 1f)) }
    }

    /**
     * 0..1 score for how useful a face is for recognition: big enough, facing the camera,
     * and sharp. Poor faces still count, but can't start a new person on their own.
     */
    private fun quality(face: Face, aligned: Bitmap): Float {
        val side = min(face.boundingBox.width(), face.boundingBox.height()).toFloat()
        val size = ((side - 40f) / 80f).coerceIn(0f, 1f)
        val yaw = (1f - (abs(face.headEulerAngleY) - 15f).coerceAtLeast(0f) / 30f).coerceIn(0f, 1f)
        val pitch = (1f - (abs(face.headEulerAngleX) - 12f).coerceAtLeast(0f) / 25f).coerceIn(0f, 1f)
        val sharp = (sharpness(aligned) / 120f).coerceIn(0f, 1f)
        return (0.35f * size + 0.3f * yaw + 0.15f * pitch + 0.2f * sharp).coerceIn(0f, 1f)
    }

    /** Variance of the Laplacian over the face: low for blurry faces. */
    private fun sharpness(b: Bitmap): Float {
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val g = FloatArray(w * h) { i -> val p = px[i]; (((p shr 16) and 0xFF) * 0.299f + ((p shr 8) and 0xFF) * 0.587f + (p and 0xFF) * 0.114f) }
        var sum = 0.0; var sq = 0.0; var cnt = 0
        for (y in 20 until h - 20) for (x in 20 until w - 20) {
            val i = y * w + x
            val l = 4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w]
            sum += l; sq += l * l; cnt++
        }
        if (cnt == 0) return 0f
        val mean = sum / cnt
        return (sq / cnt - mean * mean).toFloat()
    }

    /** Runs the face model: pixels scaled to -1..1 in, L2-normalised 512-d fingerprint out. */
    private fun embed(interpreter: Interpreter, face: Bitmap, input: ByteBuffer, output: Array<FloatArray>): FloatArray {
        val px = IntArray(INPUT * INPUT)
        face.getPixels(px, 0, INPUT, 0, 0, INPUT, INPUT)
        input.rewind()
        for (p in px) {
            input.putFloat((((p shr 16) and 0xFF) - 127.5f) / 127.5f)
            input.putFloat((((p shr 8) and 0xFF) - 127.5f) / 127.5f)
            input.putFloat(((p and 0xFF) - 127.5f) / 127.5f)
        }
        input.rewind()
        interpreter.run(input, output)
        return Embeddings.normalize(output[0].copyOf())
    }
}

/**
 * The face switch, and the rule for when a face scan may write.
 *
 * Every scan records the switch state and epoch it began in. Changing the switch bumps the epoch,
 * so any scan that began earlier is stale and stops at its next checkpoint.
 *
 * Guarantees:
 * - [setEnabled] saves the choice and bumps the epoch inside one critical section. A stale scan
 *   can't commit a write once a switch-off has returned, because [commit] checks liveness and writes
 *   under the same monitor.
 * - Nothing is cancelled by a switch. A call already waiting on ML Kit finishes first, so the bitmap it
 *   was given stays valid. The scan then stops at its next checkpoint.
 * - [scan] runs one scan at a time. A scan that is queued while the switch changes doesn't run; a scan
 *   queued after a re-enable runs as soon as the stale one releases the lock.
 * - The monitor is held only for short writes (one photo's faces, a regroup write). Clustering runs
 *   outside it, so the UI's setter waits at most for one of those writes.
 */
internal class FaceScanGate(initialEnabled: Boolean, private val persist: (Boolean) -> Unit) {

    /** Where a scan began: the switch state and epoch at the moment it was asked to run. */
    data class Scan(val epoch: Long, val enabled: Boolean)

    private val monitor = Any()
    private val scanLock = Mutex()
    private var epoch = 0L
    private var enabled = initialEnabled

    val isEnabled: Boolean
        get() = synchronized(monitor) { enabled }

    /** Saves and applies the switch. Returns false when it was already in that state. */
    fun setEnabled(value: Boolean): Boolean {
        synchronized(monitor) {
            if (enabled == value) return false
            persist(value)
            enabled = value
            epoch++
            return true
        }
    }

    fun isLive(scan: Scan): Boolean = synchronized(monitor) { scan.enabled && scan.epoch == epoch }

    /** Runs [write] only if [scan] is still live. Returns null when it was skipped. Never suspends. */
    fun <T> commit(scan: Scan, write: () -> T): T? = synchronized(monitor) {
        if (scan.enabled && scan.epoch == epoch) write() else null
    }

    /**
     * Runs [block] once the scan lock is free, unless the switch has changed since the call. Returns
     * null when nothing ran. Waiting is a cancellable suspension, not a spin.
     */
    suspend fun <T> scan(block: suspend (Scan) -> T): T? {
        val scan = synchronized(monitor) { Scan(epoch, enabled) }
        if (!scan.enabled) return null
        return scanLock.withLock {
            if (isLive(scan)) block(scan) else null
        }
    }
}

/**
 * Runs [work] on the newest request only. Requests that arrive while one is running replace any
 * queued request, so repeated refreshes never queue up. At most one drain runs at a time.
 */
internal class LatestRequestRunner<T : Any>(
    private val scope: CoroutineScope,
    private val work: suspend (T) -> Unit,
) {
    private val monitor = Any()
    private var pending: T? = null
    private var job: Job? = null

    /** Queues [request] (replacing any queued one) and starts a drain if none is running. */
    fun submit(request: T) {
        synchronized(monitor) {
            pending = request
            if (job?.isActive == true) return
            job = scope.launch { drain() }
        }
    }

    private suspend fun drain() {
        while (true) {
            val next = takePending() ?: return
            work(next)
        }
    }

    /** Takes the queued request, or clears the drain's job when there is none. Both happen under one lock. */
    private fun takePending(): T? = synchronized(monitor) {
        val next = pending
        pending = null
        if (next == null) job = null
        next
    }

    /** Waits until nothing is queued or running. */
    suspend fun awaitIdle() {
        while (true) {
            val active = synchronized(monitor) { job?.takeIf { it.isActive } } ?: return
            active.join()
        }
    }
}

/**
 * Waits until the native call behind [register] reports a result. Cancelling the caller does not
 * return early: the native side may still be reading buffers the caller owns, so those must be released
 * only after this returns. The caller then checks whether it is still active before using the result.
 */
internal suspend fun <T> awaitNative(register: ((Result<T>) -> Unit) -> Unit): T = suspendCoroutine { cont ->
    register { result ->
        result.fold(onSuccess = { cont.resume(it) }, onFailure = { cont.resumeWithException(it) })
    }
}

private suspend fun <T> Task<T>.awaitTask(): T = awaitNative<T> { done ->
    addOnCompleteListener { task ->
        done(
            if (task.isSuccessful) Result.success(task.result)
            else Result.failure(task.exception ?: IllegalStateException("ML Kit task did not complete"))
        )
    }
}
