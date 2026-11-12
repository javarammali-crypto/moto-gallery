package com.javaram.gallery.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.javaram.gallery.data.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

data class Adjust(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val tint: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val vignette: Float = 0f,
    val fade: Float = 0f,
)

data class Geometry(
    val rotation: Int = 0,          // 0, 90, 180, 270
    val flipH: Boolean = false,
    val straighten: Float = 0f,     // degrees, -45..45
    /** Crop as fractions of the rotated/straightened image. */
    val crop: RectF = RectF(0f, 0f, 1f, 1f),
)

sealed interface Background {
    data object Keep : Background
    data object Transparent : Background
    data object Blur : Background
    data class Solid(val color: Int) : Background
}

data class EditParams(
    val geometry: Geometry = Geometry(),
    val adjust: Adjust = Adjust(),
    val filter: String = "Original",
    val filterAmount: Float = 1f,
    val background: Background = Background.Keep,
)

object EditEngine {

    /** Decodes the photo (EXIF rotation applied) with its long side at most [maxSide] pixels. */
    suspend fun load(context: Context, uri: Uri, maxSide: Int): Bitmap = withContext(Dispatchers.IO) {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            val long = max(info.size.width, info.size.height)
            if (long > maxSide) {
                val s = maxSide.toFloat() / long
                decoder.setTargetSize((info.size.width * s).roundToInt().coerceAtLeast(1), (info.size.height * s).roundToInt().coerceAtLeast(1))
            }
        }
    }

    /** Rotation/flip/straighten without the crop (what the crop tool shows). */
    fun orient(src: Bitmap, g: Geometry): Bitmap {
        val m = Matrix().apply {
            if (g.flipH) postScale(-1f, 1f)
            postRotate(g.rotation.toFloat())
        }
        val turned = if (g.rotation == 0 && !g.flipH) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (abs(g.straighten) < 0.05f) return turned
        val w = turned.width
        val h = turned.height
        val rad = Math.toRadians(abs(g.straighten).toDouble())
        // Zoom just enough that the tilted photo still fills the frame.
        val scale = (cos(rad) + sin(rad) * max(w.toFloat() / h, h.toFloat() / w)).toFloat()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            rotate(g.straighten, w / 2f, h / 2f)
            scale(scale, scale, w / 2f, h / 2f)
            drawBitmap(turned, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }

    fun crop(src: Bitmap, c: RectF): Bitmap {
        if (c.left <= 0.001f && c.top <= 0.001f && c.right >= 0.999f && c.bottom >= 0.999f) return src
        val x = (c.left * src.width).roundToInt().coerceIn(0, src.width - 1)
        val y = (c.top * src.height).roundToInt().coerceIn(0, src.height - 1)
        val w = (c.width() * src.width).roundToInt().coerceIn(1, src.width - x)
        val h = (c.height() * src.height).roundToInt().coerceIn(1, src.height - y)
        return Bitmap.createBitmap(src, x, y, w, h)
    }

    fun geometry(src: Bitmap, g: Geometry): Bitmap = crop(orient(src, g), g.crop)

    // ---------------- Colour ----------------

    private fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)

    private fun sat(s: Float) = ColorMatrix().apply { setSaturation(s) }

    private fun contrast(k: Float, lift: Float = 0f) = ColorMatrix(
        floatArrayOf(k, 0f, 0f, 0f, 128f * (1 - k) + lift, 0f, k, 0f, 0f, 128f * (1 - k) + lift, 0f, 0f, k, 0f, 128f * (1 - k) + lift, 0f, 0f, 0f, 1f, 0f)
    )

    private fun scaleRgb(r: Float, g: Float, b: Float, add: Float = 0f) = ColorMatrix(
        floatArrayOf(r, 0f, 0f, 0f, add, 0f, g, 0f, 0f, add, 0f, 0f, b, 0f, add, 0f, 0f, 0f, 1f, 0f)
    )

    private val sepia = ColorMatrix(
        floatArrayOf(0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
    )

    private fun chain(vararg ms: ColorMatrix) = ColorMatrix().apply { ms.forEach { postConcat(it) } }

    val filterNames = listOf("Original", "Vivid", "Warm", "Cool", "Film", "Fade", "Teal", "Drama", "Vintage", "Sepia", "Mono", "Noir")

    fun filterMatrix(name: String): ColorMatrix = when (name) {
        "Vivid" -> chain(sat(1.35f), contrast(1.08f))
        "Warm" -> chain(scaleRgb(1.08f, 1.0f, 0.88f), sat(1.08f))
        "Cool" -> chain(scaleRgb(0.92f, 1.0f, 1.1f), sat(0.98f))
        "Film" -> chain(scaleRgb(1.05f, 1.0f, 0.92f, 6f), sat(0.9f), contrast(0.95f, 4f))
        "Fade" -> chain(contrast(0.82f, 18f), sat(0.85f))
        "Teal" -> ColorMatrix(
            floatArrayOf(1.08f, 0.05f, -0.06f, 0f, -4f, -0.03f, 1.03f, 0.04f, 0f, 0f, -0.1f, 0.06f, 1.16f, 0f, 6f, 0f, 0f, 0f, 1f, 0f)
        ).apply { postConcat(contrast(1.06f)) }
        "Drama" -> chain(contrast(1.3f, -8f), sat(1.1f))
        "Vintage" -> chain(sat(0.7f), blendMatrix(sepia, 0.45f), contrast(0.9f, 10f))
        "Sepia" -> sepia
        "Mono" -> sat(0f)
        "Noir" -> chain(sat(0f), contrast(1.4f, -10f))
        else -> ColorMatrix()
    }

    private fun blendMatrix(m: ColorMatrix, t: Float): ColorMatrix {
        val a = identity()
        val b = m.array
        return ColorMatrix(FloatArray(20) { a[it] + (b[it] - a[it]) * t })
    }

    /** The full colour matrix: filter (at its strength) then the manual adjustments. */
    fun colorMatrix(p: EditParams): ColorMatrix {
        val a = p.adjust
        val m = ColorMatrix()
        if (p.filter != "Original") m.postConcat(blendMatrix(filterMatrix(p.filter), p.filterAmount))
        if (a.saturation != 0f) m.postConcat(sat(1f + a.saturation))
        if (a.contrast != 0f) m.postConcat(contrast(if (a.contrast > 0) 1f + a.contrast * 0.6f else 1f + a.contrast * 0.5f))
        if (a.brightness != 0f) m.postConcat(scaleRgb(1f, 1f, 1f, a.brightness * 60f))
        if (a.warmth != 0f) m.postConcat(scaleRgb(1f + a.warmth * 0.1f, 1f, 1f - a.warmth * 0.12f))
        if (a.tint != 0f) m.postConcat(scaleRgb(1f + a.tint * 0.04f, 1f - a.tint * 0.08f, 1f + a.tint * 0.04f))
        return m
    }

    private fun applyMatrix(src: Bitmap, m: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        return out
    }

    /** Highlights, shadows and fade through a tone curve. */
    private fun applyTone(bmp: Bitmap, a: Adjust): Bitmap {
        if (a.highlights == 0f && a.shadows == 0f && a.fade == 0f) return bmp
        val lut = IntArray(256) { v ->
            var x = v / 255f
            x += a.shadows * 0.35f * (1 - x) * (1 - x) * x * 2.2f
            x += a.highlights * 0.3f * x * x * (1 - x) * 2.2f
            x = a.fade * 0.14f + x * (1 - a.fade * 0.2f)
            (x.coerceIn(0f, 1f) * 255f).roundToInt()
        }
        val out = if (bmp.isMutable) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width
        val row = IntArray(w)
        for (y in 0 until out.height) {
            out.getPixels(row, 0, w, 0, y, w, 1)
            for (i in 0 until w) {
                val p = row[i]
                row[i] = (p and -0x1000000) or (lut[(p shr 16) and 0xFF] shl 16) or (lut[(p shr 8) and 0xFF] shl 8) or lut[p and 0xFF]
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }

    private fun applyVignette(bmp: Bitmap, amount: Float): Bitmap {
        if (amount <= 0f) return bmp
        val out = if (bmp.isMutable) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val r = max(w, h) * 0.75f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w / 2, h / 2, r,
                intArrayOf(0x00000000, 0x00000000, ((amount * 200).toInt() shl 24)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        Canvas(out).drawRect(0f, 0f, w, h, paint)
        return out
    }

    // ---------------- Background ----------------

    /** Cuts out the main subject (people, pets, objects) as a bitmap with a transparent background. */
    suspend fun subject(bmp: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        val client = SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        )
        try {
            client.process(InputImage.fromBitmap(bmp, 0)).await().foregroundBitmap
        } finally {
            client.close()
        }
    }

    private fun cheapBlur(src: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(src, max(1, src.width / 24), max(1, src.height / 24), true)
        val mid = Bitmap.createScaledBitmap(small, max(1, src.width / 8), max(1, src.height / 8), true)
        return Bitmap.createScaledBitmap(mid, src.width, src.height, true)
    }

    private fun composite(base: Bitmap, fg: Bitmap?, bg: Background): Bitmap {
        if (bg == Background.Keep || fg == null) return base
        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        when (bg) {
            Background.Transparent -> Unit
            Background.Blur -> c.drawBitmap(cheapBlur(base), 0f, 0f, null)
            is Background.Solid -> c.drawColor(bg.color)
            Background.Keep -> Unit
        }
        val dst = RectF(0f, 0f, base.width.toFloat(), base.height.toFloat())
        c.drawBitmap(fg, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * Everything except the colour matrix (which the preview applies live on the GPU):
     * geometry → background → tone → vignette.
     */
    fun renderBase(src: Bitmap, p: EditParams, fg: Bitmap?): Bitmap {
        var b = geometry(src, p.geometry)
        b = composite(b, fg, p.background)
        if (!b.isMutable) b = b.copy(Bitmap.Config.ARGB_8888, true)
        b = applyTone(b, p.adjust)
        b = applyVignette(b, p.adjust.vignette)
        return b
    }

    fun renderFull(src: Bitmap, p: EditParams, fg: Bitmap?): Bitmap =
        applyMatrix(renderBase(src, p, fg), colorMatrix(p))

    // ---------------- Saving ----------------

    private val exifTags = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    )

    private fun readExif(context: Context, uri: Uri): Map<String, String> = runCatching {
        val original = runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
        context.contentResolver.openInputStream(original)?.use { input ->
            val e = ExifInterface(input)
            exifTags.mapNotNull { t -> e.getAttribute(t)?.let { t to it } }.toMap()
        } ?: emptyMap()
    }.getOrDefault(emptyMap())

    private fun writeExif(context: Context, uri: Uri, tags: Map<String, String>) {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val e = ExifInterface(pfd.fileDescriptor)
                tags.forEach { (k, v) -> e.setAttribute(k, v) }
                e.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                e.setAttribute(ExifInterface.TAG_SOFTWARE, "Gallery editor")
                e.saveAttributes()
            }
        }
    }

    /** Saves a new photo next to the original (keeps its date and location). */
    suspend fun saveCopy(context: Context, original: Media, out: Bitmap, png: Boolean): Uri? = withContext(Dispatchers.IO) {
        val tags = if (png) emptyMap() else readExif(context, original.uri)
        val base = original.name.substringBeforeLast('.')
        val name = "${base}_edited_${System.currentTimeMillis() / 1000}.${if (png) "png" else "jpg"}"
        val mime = if (png) "image/png" else "image/jpeg"
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        fun insert(path: String): Uri? = runCatching {
            context.contentResolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.Images.Media.DATE_TAKEN, original.dateTaken)
            })
        }.getOrNull()
        val path = original.relativePath.takeIf {
            it.startsWith("DCIM/", true) || it.startsWith("Pictures/", true)
        } ?: "Pictures/Edited/"
        val uri = insert(path) ?: insert("Pictures/Edited/") ?: return@withContext null
        context.contentResolver.openOutputStream(uri)?.use { os ->
            out.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, os)
        }
        if (!png) writeExif(context, uri, tags)
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        uri
    }

    /** Overwrites the original JPEG (after the user granted write access). */
    suspend fun overwrite(context: Context, original: Media, out: Bitmap): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val tags = readExif(context, original.uri)
            context.contentResolver.openOutputStream(original.uri, "wt")?.use { os ->
                out.compress(Bitmap.CompressFormat.JPEG, 95, os)
            } ?: return@runCatching false
            writeExif(context, original.uri, tags)
            true
        }.getOrDefault(false)
    }

    fun fitInside(w: Int, h: Int, maxW: Float, maxH: Float): Pair<Float, Float> {
        val s = min(maxW / w, maxH / h)
        return w * s to h * s
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
