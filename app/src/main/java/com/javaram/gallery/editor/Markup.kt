package com.javaram.gallery.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset

/**
 * Drawings and text on a photo. Positions are fractions of the edited picture (0..1), and
 * sizes fractions of its width, so the same marks draw correctly on the small preview and
 * on the full-resolution save.
 */
sealed interface Mark

data class PenStroke(
    val points: List<Offset>,
    val color: Int,
    /** Line thickness as a fraction of the picture's width. */
    val width: Float,
    /** Highlighter: see-through, flat-ended, drawn under later ink. */
    val highlighter: Boolean,
) : Mark

data class TextMark(
    val text: String,
    /** Centre of the text, as fractions of the picture. */
    val center: Offset,
    /** Letter height as a fraction of the picture's width. */
    val size: Float,
    val color: Int,
    /** Draw the text on a rounded label so it reads on busy photos. */
    val boxed: Boolean,
) : Mark

/** A painted-over area to remove with AI, plus how thick the brush was. */
data class BrushStroke(val points: List<Offset>, val width: Float)

object Markup {

    private fun strokePaint(s: PenStroke, w: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = if (s.highlighter) Paint.Cap.SQUARE else Paint.Cap.ROUND
        strokeWidth = s.width * w
        color = if (s.highlighter) (s.color and 0x00FFFFFF) or (0x66 shl 24) else s.color
    }

    fun path(points: List<Offset>, w: Int, h: Int): Path = Path().apply {
        if (points.isEmpty()) return@apply
        moveTo(points[0].x * w, points[0].y * h)
        if (points.size == 1) {
            lineTo(points[0].x * w + 0.1f, points[0].y * h)
            return@apply
        }
        // Smooth the line through midpoints so fast finger moves don't look jagged.
        for (i in 1 until points.size) {
            val p = points[i - 1]
            val q = points[i]
            quadTo(p.x * w, p.y * h, (p.x + q.x) / 2f * w, (p.y + q.y) / 2f * h)
        }
        lineTo(points.last().x * w, points.last().y * h)
    }

    /** Where a text mark sits, in picture pixels (used for drawing and for tapping on it). */
    fun textBounds(t: TextMark, w: Int, h: Int): RectF {
        val p = textPaint(t, w)
        val tw = p.measureText(t.text)
        val fm = p.fontMetrics
        val th = fm.descent - fm.ascent
        val pad = if (t.boxed) p.textSize * 0.45f else p.textSize * 0.1f
        val cx = t.center.x * w
        val cy = t.center.y * h
        return RectF(cx - tw / 2 - pad, cy - th / 2 - pad * 0.7f, cx + tw / 2 + pad, cy + th / 2 + pad * 0.7f)
    }

    private fun textPaint(t: TextMark, w: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = t.size * w
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = t.color
        textAlign = Paint.Align.CENTER
    }

    /** True when the colour is light, so a boxed label uses a dark background. */
    private fun isLight(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r * 0.299 + g * 0.587 + b * 0.114 > 150
    }

    fun draw(canvas: Canvas, w: Int, h: Int, marks: List<Mark>) {
        for (m in marks) when (m) {
            is PenStroke -> canvas.drawPath(path(m.points, w, h), strokePaint(m, w))
            is TextMark -> {
                if (m.text.isBlank()) continue
                val p = textPaint(m, w)
                val r = textBounds(m, w, h)
                if (m.boxed) {
                    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = if (isLight(m.color)) 0xCC000000.toInt() else 0xE6FFFFFF.toInt()
                    }
                    val radius = r.height() * 0.3f
                    canvas.drawRoundRect(r, radius, radius, bg)
                } else {
                    // Soft shadow keeps plain text readable on any photo.
                    p.setShadowLayer(p.textSize * 0.12f, 0f, p.textSize * 0.04f, 0x99000000.toInt())
                }
                val fm = p.fontMetrics
                val baseline = m.center.y * h - (fm.ascent + fm.descent) / 2f
                canvas.drawText(m.text, m.center.x * w, baseline, p)
            }
        }
    }

    /** Draws marks onto a copy of [src] at its own resolution. */
    fun apply(src: Bitmap, marks: List<Mark>): Bitmap {
        if (marks.isEmpty()) return src
        val out = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        draw(Canvas(out), out.width, out.height, marks)
        return out
    }

    /** The painted area as a mask the size of the picture: 255 = remove. */
    fun brushMask(strokes: List<BrushStroke>, w: Int, h: Int): Bitmap {
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val c = Canvas(mask)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0xFFFFFFFF.toInt()
        }
        for (s in strokes) {
            p.strokeWidth = s.width * w
            c.drawPath(path(s.points, w, h), p)
        }
        return mask
    }
}
