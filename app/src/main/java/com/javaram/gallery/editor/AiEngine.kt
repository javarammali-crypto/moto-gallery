package com.javaram.gallery.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One thing the subject finder separated from the background: its pixels and position. */
class FoundSubject(val bitmap: Bitmap, val x: Int, val y: Int) {
    /** Visible pixels, used to tell the main subject from people in the background. */
    val area: Int by lazy {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        px.count { (it ushr 24) > 110 }
    }

    fun contains(px: Float, py: Float): Boolean {
        val lx = (px - x).roundToInt()
        val ly = (py - y).roundToInt()
        if (lx !in 0 until bitmap.width || ly !in 0 until bitmap.height) return false
        return (bitmap.getPixel(lx, ly) ushr 24) > 90
    }
}

/**
 * On-device AI tools for the editor.
 *
 * Erasing uses MI-GAN (Picsart AI Research, MIT licence), a small inpainting model that
 * fills a masked area with what should be behind it. It works on a 512×512 window around
 * the area, so it is fast and the rest of the photo keeps its full resolution.
 */
object AiEngine {
    private const val MODEL = "inpaint_migan.tflite"
    private const val S = 512

    private val lock = Mutex()
    private var interpreter: Interpreter? = null

    private fun model(context: Context): Interpreter =
        interpreter ?: Interpreter(
            context.assets.openFd(MODEL).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use {
                    it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            },
            Interpreter.Options().setNumThreads(4),
        ).also { interpreter = it }

    /** Frees the model when the editor closes. */
    fun release() {
        interpreter?.close()
        interpreter = null
    }

    /**
     * Removes what [mask] covers (any alpha > 0; scaled to [src]'s size) and fills it in.
     * Returns a new bitmap; [src] is not changed.
     */
    suspend fun erase(context: Context, src: Bitmap, mask: Bitmap): Bitmap = lock.withLock {
        withContext(Dispatchers.Default) {
            val w = src.width
            val h = src.height
            // Mask at picture size, grown a little to cover edges, shadows and halos.
            val m = maskBytes(mask, w, h)
            val grow = max(3, (max(w, h) * 0.006f).roundToInt())
            dilate(m, w, h, grow)
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            for (y in 0 until h) for (x in 0 until w) if (m[y * w + x] != 0.toByte()) {
                if (x < x0) x0 = x; if (x > x1) x1 = x
                if (y < y0) y0 = y; if (y > y1) y1 = y
            }
            if (x1 < 0) return@withContext src

            var out = src.copy(Bitmap.Config.ARGB_8888, true)
            // Big areas are filled in pieces so each piece keeps enough detail.
            val tile = max(S, (min(w, h) * 0.6f).roundToInt())
            val parts = ArrayList<Rect>()
            var ty = y0
            while (ty <= y1) {
                var tx = x0
                val th = min(tile, y1 - ty + 1)
                while (tx <= x1) {
                    val tw = min(tile, x1 - tx + 1)
                    parts += Rect(tx, ty, tx + tw, ty + th)
                    tx += tile
                }
                ty += tile
            }
            for (part in parts) {
                val local = ByteArray(w * h)
                var any = false
                for (y in part.top until part.bottom) for (x in part.left until part.right) {
                    val i = y * w + x
                    if (m[i] != 0.toByte()) { local[i] = 1; any = true }
                }
                if (any) out = fill(context, out, local, part)
            }
            out
        }
    }

    /** Fills the [hole] pixels inside [area] using a context window around it. */
    private fun fill(context: Context, img: Bitmap, hole: ByteArray, area: Rect): Bitmap {
        val w = img.width
        val h = img.height
        // Square window around the area with plenty of surroundings for context.
        val side = min(max(w, h), max((max(area.width(), area.height()) * 2.2f).roundToInt(), min(S, min(w, h))))
        val cx = area.centerX()
        val cy = area.centerY()
        val win = Rect(cx - side / 2, cy - side / 2, cx - side / 2 + side, cy - side / 2 + side)
        if (win.left < 0) win.offset(-win.left, 0)
        if (win.top < 0) win.offset(0, -win.top)
        if (win.right > w) win.offset(w - win.right, 0)
        if (win.bottom > h) win.offset(0, h - win.bottom)
        win.intersect(0, 0, w, h)
        val ww = win.width()
        val wh = win.height()

        val crop = Bitmap.createBitmap(img, win.left, win.top, ww, wh)
        val small = Bitmap.createScaledBitmap(crop, S, S, true)
        val px = IntArray(S * S)
        small.getPixels(px, 0, S, 0, 0, S, S)

        // Hole, scaled to the model's size (nearest pixel).
        val holeSmall = BooleanArray(S * S)
        for (y in 0 until S) {
            val sy = win.top + (y * wh / S)
            for (x in 0 until S) {
                val sx = win.left + (x * ww / S)
                holeSmall[y * S + x] = hole[sy * w + sx] != 0.toByte()
            }
        }

        val input = ByteBuffer.allocateDirect(S * S * 4 * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until S * S) {
            val known = if (holeSmall[i]) 0f else 1f
            val p = px[i]
            input.putFloat(known - 0.5f)
            input.putFloat((((p shr 16) and 0xFF) / 127.5f - 1f) * known)
            input.putFloat((((p shr 8) and 0xFF) / 127.5f - 1f) * known)
            input.putFloat(((p and 0xFF) / 127.5f - 1f) * known)
        }
        input.rewind()
        val output = ByteBuffer.allocateDirect(3 * S * S * 4).order(ByteOrder.nativeOrder())
        model(context).run(input, output)
        output.rewind()
        val f = output.asFloatBuffer()
        // Output is channels-first: all red values, then green, then blue.
        val res = IntArray(S * S)
        for (i in 0 until S * S) {
            val r = ((f.get(i) + 1f) * 127.5f).roundToInt().coerceIn(0, 255)
            val g = ((f.get(S * S + i) + 1f) * 127.5f).roundToInt().coerceIn(0, 255)
            val b = ((f.get(2 * S * S + i) + 1f) * 127.5f).roundToInt().coerceIn(0, 255)
            res[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val filledSmall = Bitmap.createBitmap(res, S, S, Bitmap.Config.ARGB_8888)
        val filled = Bitmap.createScaledBitmap(filledSmall, ww, wh, true)

        // Blend the filled pixels in with a soft edge.
        val soft = FloatArray(ww * wh)
        for (y in 0 until wh) for (x in 0 until ww) {
            soft[y * ww + x] = if (hole[(win.top + y) * w + win.left + x] != 0.toByte()) 1f else 0f
        }
        boxBlur(soft, ww, wh, max(1, (side / 400f).roundToInt()))
        val orig = IntArray(ww * wh)
        img.getPixels(orig, 0, w, win.left, win.top, ww, wh)
        val fill = IntArray(ww * wh)
        filled.getPixels(fill, 0, ww, 0, 0, ww, wh)
        for (i in orig.indices) {
            val a = soft[i]
            if (a <= 0f) continue
            val o = orig[i]
            val n = fill[i]
            val r = (((o shr 16) and 0xFF) * (1 - a) + ((n shr 16) and 0xFF) * a).roundToInt()
            val g = (((o shr 8) and 0xFF) * (1 - a) + ((n shr 8) and 0xFF) * a).roundToInt()
            val b = ((o and 0xFF) * (1 - a) + (n and 0xFF) * a).roundToInt()
            orig[i] = (o and -0x1000000) or (r shl 16) or (g shl 8) or b
        }
        img.setPixels(orig, 0, w, win.left, win.top, ww, wh)
        return img
    }

    private fun maskBytes(mask: Bitmap, w: Int, h: Int): ByteArray {
        val scaled = if (mask.width == w && mask.height == h) mask else {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            Canvas(out).drawBitmap(mask, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            out
        }
        val a = ByteArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            scaled.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) if ((row[x] ushr 24) > 40) a[y * w + x] = 1
        }
        return a
    }

    /** Grows the marked area by [r] pixels in every direction (square brush). */
    private fun dilate(m: ByteArray, w: Int, h: Int, r: Int) {
        val tmp = ByteArray(w * h)
        for (y in 0 until h) {
            var last = -r - 1
            for (x in 0 until w) {
                if (m[y * w + x] != 0.toByte()) last = x
                if (x - last <= r) tmp[y * w + x] = 1
            }
            last = w + r + 1
            for (x in w - 1 downTo 0) {
                if (m[y * w + x] != 0.toByte()) last = x
                if (last - x <= r) tmp[y * w + x] = 1
            }
        }
        for (x in 0 until w) {
            var last = -r - 1
            for (y in 0 until h) {
                if (tmp[y * w + x] != 0.toByte()) last = y
                m[y * w + x] = if (y - last <= r) 1 else 0
            }
            last = h + r + 1
            for (y in h - 1 downTo 0) {
                if (tmp[y * w + x] != 0.toByte()) last = y
                if (last - y <= r) m[y * w + x] = 1
            }
        }
    }

    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int) {
        val tmp = FloatArray(a.size)
        for (y in 0 until h) {
            var sum = 0f
            for (x in -r..r) sum += a[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = sum / (2 * r + 1)
                sum += a[y * w + (x + r + 1).coerceIn(0, w - 1)] - a[y * w + (x - r).coerceIn(0, w - 1)]
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            for (y in -r..r) sum += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                a[y * w + x] = sum / (2 * r + 1)
                sum += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x] - tmp[(y - r).coerceIn(0, h - 1) * w + x]
            }
        }
    }

    // ---------------- Finding people and objects ----------------

    /** People, pets and objects in the photo, biggest first. */
    suspend fun subjects(bmp: Bitmap): List<FoundSubject> = withContext(Dispatchers.Default) {
        val client = SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder()
                .enableMultipleSubjects(SubjectSegmenterOptions.SubjectResultOptions.Builder().enableSubjectBitmap().build())
                .build()
        )
        try {
            client.process(InputImage.fromBitmap(bmp, 0)).await().subjects
                .mapNotNull { s -> s.bitmap?.let { FoundSubject(it, s.startX, s.startY) } }
                .sortedByDescending { it.area }
        } finally {
            client.close()
        }
    }

    /**
     * Which subjects look like the main subject: the biggest one, plus anything nearly as
     * big (a couple posing together). Everything else counts as background.
     */
    fun mainSubjects(all: List<FoundSubject>): Set<Int> {
        if (all.isEmpty()) return emptySet()
        val biggest = all.maxOf { it.area }.toFloat()
        return all.indices.filter { all[it].area >= biggest * 0.45f }.toSet()
    }

    /** Mask (alpha) covering the chosen subjects, at [w]×[h]. */
    fun subjectMask(all: List<FoundSubject>, chosen: Set<Int>, w: Int, h: Int): Bitmap {
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val c = Canvas(mask)
        for (i in chosen) {
            val s = all.getOrNull(i) ?: continue
            c.drawBitmap(s.bitmap, s.x.toFloat(), s.y.toFloat(), null)
        }
        return mask
    }

    // ---------------- Auto enhance ----------------

    /** Looks at the photo's light and colour and suggests adjustments that bring it to life. */
    suspend fun enhance(bmp: Bitmap): Adjust = withContext(Dispatchers.Default) {
        val s = 256f / max(bmp.width, bmp.height)
        val small = Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * s).roundToInt()), max(1, (bmp.height * s).roundToInt()), true)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        val hist = IntArray(256)
        var satSum = 0.0
        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            hist[(r * 0.299 + g * 0.587 + b * 0.114).roundToInt().coerceIn(0, 255)]++
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            satSum += if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
        }
        fun pct(q: Float): Float {
            val target = px.size * q
            var acc = 0
            for (i in 0 until 256) { acc += hist[i]; if (acc >= target) return i / 255f }
            return 1f
        }
        val p2 = pct(0.02f)
        val p10 = pct(0.10f)
        val mid = pct(0.5f)
        val p98 = pct(0.98f)
        val sat = (satSum / px.size).toFloat()

        val exposure = (ln(0.48f / mid.coerceIn(0.05f, 0.95f)) / ln(2f) * 0.55f).coerceIn(-0.5f, 0.6f)
        val spread = p98 - p2
        val contrast = ((0.88f - spread) * 0.9f).coerceIn(0f, 0.3f)
        val shadows = if (p10 < 0.10f) ((0.12f - p10) * 3f).coerceIn(0f, 0.35f) else 0f
        val highlights = if (p98 > 0.96f) -0.2f else 0f
        val saturation = ((0.32f - sat) * 1.2f).coerceIn(0f, 0.3f)
        Adjust(
            exposure = exposure,
            contrast = contrast,
            shadows = shadows,
            highlights = highlights,
            saturation = saturation,
        )
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
