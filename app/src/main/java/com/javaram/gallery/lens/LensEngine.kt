package com.javaram.gallery.lens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import androidx.core.content.FileProvider
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.javaram.gallery.data.Media
import com.javaram.gallery.editor.EditEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** One recognised word, with its box in the analysed bitmap's pixels. */
data class LensWord(val text: String, val box: RectF, val line: Int)

/** A cut-out subject (person, pet, object): its pixels and where it sits in the analysed bitmap. */
class LensSubject(val bitmap: Bitmap, val x: Int, val y: Int) {
    val width get() = bitmap.width
    val height get() = bitmap.height

    fun contains(px: Float, py: Float): Boolean {
        val lx = (px - x).roundToInt()
        val ly = (py - y).roundToInt()
        if (lx !in 0 until width || ly !in 0 until height) return false
        // Look at a small neighbourhood so a press on a thin edge still counts.
        for (dy in -6..6 step 3) for (dx in -6..6 step 3) {
            val sx = (lx + dx).coerceIn(0, width - 1)
            val sy = (ly + dy).coerceIn(0, height - 1)
            if ((bitmap.getPixel(sx, sy) ushr 24) > 110) return true
        }
        return false
    }

    fun distanceTo(px: Float, py: Float): Float {
        val cx = px.coerceIn(x.toFloat(), (x + width).toFloat())
        val cy = py.coerceIn(y.toFloat(), (y + height).toFloat())
        return hypot(px - cx, py - cy)
    }
}

/** What the lens found in one photo. */
class LensData(val mediaKey: String, val bitmap: Bitmap, val words: List<LensWord>) {
    val width get() = bitmap.width
    val height get() = bitmap.height
    @Volatile var subjects: List<LensSubject>? = null

    /** Index of the word under a point (in bitmap pixels), with a little slack. */
    fun wordAt(px: Float, py: Float, slack: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        words.forEachIndexed { i, w ->
            val b = w.box
            if (px >= b.left - slack && px <= b.right + slack && py >= b.top - slack && py <= b.bottom + slack) {
                val d = hypot(px - b.centerX(), py - b.centerY())
                if (d < bestD) { bestD = d; best = i }
            }
        }
        return best
    }

    /** The word closest to a point, preferring words on the same line height. */
    fun nearestWord(px: Float, py: Float): Int {
        if (words.isEmpty()) return -1
        var best = 0
        var bestD = Float.MAX_VALUE
        words.forEachIndexed { i, w ->
            val b = w.box
            val dx = when {
                px < b.left -> b.left - px
                px > b.right -> px - b.right
                else -> 0f
            }
            val dy = when {
                py < b.top -> b.top - py
                py > b.bottom -> py - b.bottom
                else -> 0f
            }
            val d = dx + dy * 3f // vertical distance matters more: stay on the line being dragged over
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    fun textOf(range: IntRange): String {
        val sb = StringBuilder()
        var lastLine = -1
        for (i in range) {
            val w = words.getOrNull(i) ?: continue
            if (sb.isNotEmpty()) sb.append(if (w.line != lastLine) '\n' else ' ')
            sb.append(w.text)
            lastLine = w.line
        }
        return sb.toString()
    }
}

object LensEngine {

    private const val MAX_SIDE = 2048
    private val cache = LruCache<String, LensData>(3)

    private fun key(m: Media) = "${m.id}:${m.dateModified}"

    /** Decodes the photo and reads the text in it (cached for the last few photos). */
    suspend fun analyse(context: Context, m: Media): LensData {
        cache.get(key(m))?.let { return it }
        val bmp = EditEngine.load(context, m.uri, MAX_SIDE)
        val words = withContext(Dispatchers.Default) {
            val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val text = client.process(InputImage.fromBitmap(bmp, 0)).await()
                val out = ArrayList<LensWord>()
                var line = 0
                for (block in text.textBlocks) for (l in block.lines) {
                    for (e in l.elements) {
                        val r = e.boundingBox ?: continue
                        out += LensWord(e.text, RectF(r), line)
                    }
                    line++
                }
                out
            } catch (_: Exception) {
                emptyList()
            } finally {
                client.close()
            }
        }
        return LensData(key(m), bmp, words).also { cache.put(key(m), it) }
    }

    /** Separates people, pets and objects from the background (cached on [data]). */
    suspend fun subjects(data: LensData): List<LensSubject> {
        data.subjects?.let { return it }
        val found = withContext(Dispatchers.Default) {
            val client = SubjectSegmentation.getClient(
                SubjectSegmenterOptions.Builder()
                    .enableMultipleSubjects(
                        SubjectSegmenterOptions.SubjectResultOptions.Builder().enableSubjectBitmap().build()
                    )
                    .build()
            )
            try {
                client.process(InputImage.fromBitmap(data.bitmap, 0)).await().subjects.mapNotNull { s ->
                    val b = s.bitmap ?: return@mapNotNull null
                    LensSubject(b, s.startX, s.startY)
                }
            } finally {
                client.close()
            }
        }
        data.subjects = found
        return found
    }

    /** White sticker-style border around the subject's outline. [pad] pixels are added on every side. */
    fun outline(subject: Bitmap, pad: Int, color: Int = 0xFFFFFFFF.toInt()): Bitmap {
        val out = Bitmap.createBitmap(subject.width + pad * 2, subject.height + pad * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        val r = pad.toFloat()
        val steps = 24
        for (ring in listOf(r * 0.5f, r)) for (i in 0 until steps) {
            val a = 2 * Math.PI * i / steps
            c.drawBitmap(subject, pad + (cos(a) * ring).toFloat(), pad + (sin(a) * ring).toFloat(), paint)
        }
        c.drawBitmap(subject, pad.toFloat(), pad.toFloat(), paint) // fill any holes inside the outline
        return out
    }

    /** Thickness of the sticker border for a subject of this size. */
    fun borderFor(subject: Bitmap): Int = max(4, (max(subject.width, subject.height) / 48f).roundToInt())

    /** 512×512 sticker: subject with a white border, centred on a transparent square. */
    fun sticker(subject: Bitmap): Bitmap {
        val pad = borderFor(subject)
        val bordered = outline(subject, pad)
        Canvas(bordered).drawBitmap(subject, pad.toFloat(), pad.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        val size = 512
        val margin = 16f
        val s = min((size - margin * 2) / bordered.width, (size - margin * 2) / bordered.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val w = bordered.width * s
        val h = bordered.height * s
        Canvas(out).drawBitmap(
            bordered, null,
            RectF((size - w) / 2f, (size - h) / 2f, (size + w) / 2f, (size + h) / 2f),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        return out
    }

    // ---------------- Sharing, copying, saving ----------------

    fun copyText(context: Context, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Text from photo", text))
    }

    private suspend fun cacheFile(context: Context, bmp: Bitmap, name: String, webp: Boolean): Uri =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "lens").apply { mkdirs() }
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
            val f = File(dir, name)
            f.outputStream().use {
                bmp.compress(if (webp) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG, 100, it)
            }
            FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    suspend fun copyImage(context: Context, bmp: Bitmap) {
        val uri = cacheFile(context, bmp, "cutout_${stamp()}.png", webp = false)
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newUri(context.contentResolver, "Cut-out", uri))
    }

    suspend fun share(context: Context, bmp: Bitmap, sticker: Boolean) {
        val uri = cacheFile(context, bmp, if (sticker) "sticker_${stamp()}.webp" else "cutout_${stamp()}.png", webp = sticker)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (sticker) "image/webp" else "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, if (sticker) "Send sticker" else "Share cut-out").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Saves a transparent PNG (or a WebP sticker) to Pictures/Cutouts or Pictures/Stickers. */
    suspend fun save(context: Context, bmp: Bitmap, sticker: Boolean, takenMillis: Long): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, (if (sticker) "Sticker_" else "Cutout_") + stamp() + if (sticker) ".webp" else ".png")
            put(MediaStore.MediaColumns.MIME_TYPE, if (sticker) "image/webp" else "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (sticker) "Pictures/Stickers/" else "Pictures/Cutouts/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.Images.Media.DATE_TAKEN, takenMillis)
        }
        val uri = runCatching {
            resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        }.getOrNull() ?: return@withContext null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use {
                bmp.compress(if (sticker) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG, 100, it)
            } == true
        }.getOrDefault(false)
        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            return@withContext null
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        uri
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
