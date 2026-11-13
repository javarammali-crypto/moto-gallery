package com.javaram.gallery.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.javaram.gallery.data.Media
import com.javaram.gallery.lens.LensData
import com.javaram.gallery.lens.LensEngine
import com.javaram.gallery.lens.LensSubject
import com.javaram.gallery.ui.components.GlassSurface
import com.javaram.gallery.ui.components.glassStyle
import com.javaram.gallery.ui.components.noRippleClickable
import com.javaram.gallery.ui.components.toast
import com.javaram.gallery.ui.home.BarAction
import com.javaram.gallery.ui.theme.Accent
import com.javaram.gallery.ui.theme.GalleryTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Live text selection on a photo. [sel] is a range of word indices, or null when nothing is selected yet. */
class TextLens(val media: Media, val data: LensData, initial: IntRange?) {
    var sel by mutableStateOf(initial)
}

/** A subject lifted off the photo, with its glowing outline. */
class LiftLens(val media: Media, val data: LensData, val subject: LensSubject, val glow: Bitmap, val pad: Int)

@Stable
class LensState {
    var text by mutableStateOf<TextLens?>(null)
    var lift by mutableStateOf<LiftLens?>(null)
    /** Where the press is being analysed (screen coordinates). */
    var busyAt by mutableStateOf<Offset?>(null)
    internal var job: Job? = null

    val active: Boolean get() = text != null || lift != null || busyAt != null

    fun close() {
        job?.cancel()
        job = null
        text = null
        lift = null
        busyAt = null
    }

    /**
     * Long-press handling: text under the finger starts a text selection, a person, pet or
     * object under the finger is lifted off the background. [at] is null when the "Live text"
     * button was used instead.
     */
    fun begin(
        scope: CoroutineScope,
        context: Context,
        media: Media,
        at: Offset?,
        bounds: () -> Rect?,
        onLift: () -> Unit,
    ) {
        if (media.isVideo) return
        close()
        val b0 = bounds() ?: return
        busyAt = at ?: b0.center
        job = scope.launch {
            try {
                val data = LensEngine.analyse(context, media)
                val b = bounds() ?: b0
                if (at == null) {
                    if (data.words.isEmpty()) toast(context, "No text found in this photo")
                    else text = TextLens(media, data, null)
                    return@launch
                }
                val sx = b.width / data.width
                val px = (at.x - b.left) / sx
                val py = (at.y - b.top) / (b.height / data.height)
                val slack = with(android.content.res.Resources.getSystem().displayMetrics) { 10 * density } / sx
                val word = data.wordAt(px, py, slack)
                if (word >= 0) {
                    text = TextLens(media, data, word..word)
                    return@launch
                }
                val subjects = runCatching { LensEngine.subjects(data) }.getOrDefault(emptyList())
                val reach = max(data.width, data.height) * 0.04f
                val subject = subjects.firstOrNull { it.contains(px, py) }
                    ?: subjects.minByOrNull { it.distanceTo(px, py) }?.takeIf { it.distanceTo(px, py) < reach }
                when {
                    subject != null -> {
                        val pad = LensEngine.borderFor(subject.bitmap)
                        val glow = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            LensEngine.outline(subject.bitmap, pad)
                        }
                        lift = LiftLens(media, data, subject, glow, pad)
                        onLift()
                    }
                    data.words.isNotEmpty() -> text = TextLens(media, data, null)
                    subjects.isEmpty() -> toast(context, "Nothing to lift here. Long-press a person, pet, object or text.")
                    else -> toast(context, "Long-press right on a person, pet or object to lift it")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                toast(context, "Couldn't read this photo")
            } finally {
                busyAt = null
            }
        }
    }
}

@Composable
fun LensOverlay(state: LensState, haze: HazeState, bounds: () -> Rect?) {
    val busy = state.busyAt
    if (busy != null) BusyRing(busy) { state.close() }
    state.text?.let { t -> bounds()?.let { b -> TextLayer(t, b, haze) { state.close() } } }
    state.lift?.let { l -> bounds()?.let { b -> LiftLayer(l, b, haze) { state.close() } } }
}

// ---------------- Busy ----------------

@Composable
private fun BusyRing(at: Offset, onCancel: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "lens").animateFloat(
        0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Restart), label = "pulse",
    )
    val density = LocalDensity.current
    Canvas(Modifier.fillMaxSize().noRippleClickable(onCancel)) {
        val t = pulse.value
        val r = with(density) { (22 + 26 * t).dp.toPx() }
        drawCircle(Color.White.copy(alpha = 0.55f * (1 - t)), r, at, style = Stroke(with(density) { 2.5.dp.toPx() }))
        drawCircle(Color.White.copy(alpha = 0.22f), with(density) { 20.dp.toPx() }, at)
    }
}

// ---------------- Text selection ----------------

@Composable
private fun TextLayer(t: TextLens, bounds: Rect, haze: HazeState, onExit: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val data = t.data
    val sx = bounds.width / data.width
    val sy = bounds.height / data.height
    val rects = remember(t, bounds) {
        data.words.map { w ->
            Rect(bounds.left + w.box.left * sx, bounds.top + w.box.top * sy, bounds.left + w.box.right * sx, bounds.top + w.box.bottom * sy)
        }
    }
    val knob = with(density) { 8.dp.toPx() }
    val grab = with(density) { 30.dp.toPx() }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(t) { appear.animateTo(1f, tween(220)) }

    fun toBitmap(p: Offset) = Offset((p.x - bounds.left) / sx, (p.y - bounds.top) / sy)
    fun startKnob(i: Int) = Offset(rects[i].left, rects[i].bottom + knob)
    fun endKnob(i: Int) = Offset(rects[i].right, rects[i].bottom + knob)

    var dragging by remember { mutableIntStateOf(0) } // 0 none, 1 start handle, 2 end handle

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(t, rects) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val sel = t.sel
                    val which = when {
                        sel != null && (down.position - startKnob(sel.first)).getDistance() < grab -> 1
                        sel != null && (down.position - endKnob(sel.last)).getDistance() < grab -> 2
                        else -> 0
                    }
                    if (which == 0) {
                        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                        val p = toBitmap(up.position)
                        val i = data.wordAt(p.x, p.y, with(density) { 8.dp.toPx() } / sx)
                        if (i >= 0) {
                            t.sel = i..i
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        } else onExit()
                        return@awaitEachGesture
                    }
                    down.consume()
                    dragging = which
                    drag(down.id) { change ->
                        change.consume()
                        // The knob hangs below the text, so aim a little above the finger.
                        val p = toBitmap(change.position - Offset(0f, knob * 2.5f))
                        val i = data.nearestWord(p.x, p.y)
                        val cur = t.sel ?: return@drag
                        val next = if (dragging == 1) {
                            if (i <= cur.last) i..cur.last else { dragging = 2; cur.last..i }
                        } else {
                            if (i >= cur.first) cur.first..i else { dragging = 1; i..cur.first }
                        }
                        if (next != cur) {
                            t.sel = next
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                    dragging = 0
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = appear.value }) {
            drawRect(Color.Black.copy(alpha = 0.28f))
            val pad = 2.dp.toPx()
            val corner = CornerRadius(4.dp.toPx())
            val sel = t.sel
            rects.forEachIndexed { i, r ->
                val on = sel != null && i in sel
                drawRoundRect(
                    if (on) Accent.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.16f),
                    topLeft = Offset(r.left - pad, r.top - pad),
                    size = Size(r.width + pad * 2, r.height + pad * 2),
                    cornerRadius = corner,
                )
            }
            if (sel != null) {
                val a = rects[sel.first]
                val z = rects[sel.last]
                val line = 2.dp.toPx()
                drawLine(Accent, Offset(a.left - pad, a.top - pad), Offset(a.left - pad, a.bottom + pad), line)
                drawCircle(Accent, knob, Offset(a.left - pad, a.bottom + knob))
                drawLine(Accent, Offset(z.right + pad, z.top - pad), Offset(z.right + pad, z.bottom + pad), line)
                drawCircle(Accent, knob, Offset(z.right + pad, z.bottom + knob))
            }
        }

        // Toolbar above (or below) the selection
        val sel = t.sel
        val area = if (sel == null) null else rects.subList(sel.first, sel.last + 1).reduce { acc, r ->
            Rect(min(acc.left, r.left), min(acc.top, r.top), max(acc.right, r.right), max(acc.bottom, r.bottom))
        }
        val gap = with(density) { 22.dp.roundToPx() }
        val margin = with(density) { 12.dp.roundToPx() }
        GlassSurface(
            haze,
            Modifier
                .layout { measurable, constraints ->
                    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    val w = constraints.maxWidth
                    val h = constraints.maxHeight
                    val (x, y) = if (area == null) {
                        (w - p.width) / 2 to (h - p.height - with(density) { 120.dp.roundToPx() })
                    } else {
                        val above = area.top.roundToInt() - gap - p.height
                        val below = area.bottom.roundToInt() + gap + with(density) { 12.dp.roundToPx() }
                        val y = if (above > with(density) { 90.dp.roundToPx() }) above else min(below, h - p.height - margin)
                        (area.center.x - p.width / 2f).roundToInt().coerceIn(margin, max(margin, w - p.width - margin)) to y
                    }
                    layout(w, h) { p.place(x, y) }
                }
                .height(46.dp),
            shape = CircleShape,
        ) {
            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val all = 0..data.words.lastIndex
                if (sel != null) {
                    ToolbarButton("Copy") {
                        LensEngine.copyText(context, data.textOf(sel))
                        if (Build.VERSION.SDK_INT < 33) toast(context, "Copied")
                    }
                }
                if (sel != all) ToolbarButton("Select all") { t.sel = all }
                if (sel == all) {
                    ToolbarButton("Share") {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, data.textOf(sel))
                        }
                        context.startActivity(android.content.Intent.createChooser(send, null))
                    }
                }
            }
        }

        if (sel == null) {
            Text(
                "Tap a word to select it",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 18.dp),
            )
        }
    }
}

@Composable
private fun ToolbarButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        color = GalleryTheme.colors.content,
        modifier = Modifier
            .noRippleClickable(onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

// ---------------- Lifted subject ----------------

@Composable
private fun LiftLayer(l: LiftLens, bounds: Rect, haze: HazeState, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data = l.data
    val sx = bounds.width / data.width
    val sy = bounds.height / data.height
    val subjectImg: ImageBitmap = remember(l) { l.subject.bitmap.asImageBitmap() }
    val glowImg: ImageBitmap = remember(l) { l.glow.asImageBitmap() }
    val appear = remember(l) { Animatable(0f) }
    val sweep = remember(l) { Animatable(0f) }
    LaunchedEffect(l) {
        launch { sweep.animateTo(1f, tween(700)) }
        appear.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 320f))
    }
    val dst = Rect(
        bounds.left + l.subject.x * sx,
        bounds.top + l.subject.y * sy,
        bounds.left + (l.subject.x + l.subject.width) * sx,
        bounds.top + (l.subject.y + l.subject.height) * sy,
    )
    val glowDst = Rect(dst.left - l.pad * sx, dst.top - l.pad * sy, dst.right + l.pad * sx, dst.bottom + l.pad * sy)
    var busy by remember { mutableStateOf(false) }

    fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() } catch (_: Exception) { toast(context, "Something went wrong") }
            busy = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .hazeChild(haze, glassStyle(blur = 26.dp, tint = Color.Black.copy(alpha = 0.5f))) { alpha = appear.value.coerceIn(0f, 1f) }
                .noRippleClickable(onExit)
        )
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = 1f + 0.05f * appear.value
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(
                        (dst.center.x / size.width).coerceIn(0f, 1f),
                        (dst.center.y / size.height).coerceIn(0f, 1f),
                    )
                    shadowElevation = 0f
                }
        ) {
            // Glow that sweeps in from top to bottom, like the outline tracing the subject.
            val reveal = sweep.value
            clipRect(bottom = glowDst.top + glowDst.height * reveal) {
                drawImage(
                    glowImg,
                    dstOffset = IntOffset(glowDst.left.roundToInt(), glowDst.top.roundToInt()),
                    dstSize = IntSize(glowDst.width.roundToInt().coerceAtLeast(1), glowDst.height.roundToInt().coerceAtLeast(1)),
                    alpha = 0.9f * appear.value.coerceIn(0f, 1f),
                )
            }
            drawImage(
                subjectImg,
                dstOffset = IntOffset(dst.left.roundToInt(), dst.top.roundToInt()),
                dstSize = IntSize(dst.width.roundToInt().coerceAtLeast(1), dst.height.roundToInt().coerceAtLeast(1)),
            )
        }

        GlassSurface(
            haze,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 22.dp)
                .height(66.dp)
                .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f); translationY = (1f - appear.value) * 60f },
            shape = CircleShape,
        ) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BarAction(Icons.Outlined.ContentCopy, "Copy", !busy, {
                    act {
                        LensEngine.copyImage(context, l.subject.bitmap)
                        toast(context, "Copied. Paste it in any chat or app")
                    }
                }, width = 76.dp)
                BarAction(Icons.Outlined.SaveAlt, "Save", !busy, {
                    act {
                        val ok = LensEngine.save(context, l.subject.bitmap, sticker = false, takenMillis = System.currentTimeMillis()) != null
                        toast(context, if (ok) "Saved to Pictures › Cutouts" else "Couldn't save")
                    }
                }, width = 76.dp)
                BarAction(Icons.Outlined.EmojiEmotions, "Sticker", !busy, {
                    act {
                        val st = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { LensEngine.sticker(l.subject.bitmap) }
                        val ok = LensEngine.save(context, st, sticker = true, takenMillis = System.currentTimeMillis()) != null
                        toast(context, if (ok) "Sticker saved to Pictures › Stickers" else "Couldn't save the sticker")
                        LensEngine.share(context, st, sticker = true)
                    }
                }, width = 76.dp)
                BarAction(Icons.Outlined.Share, "Share", !busy, {
                    act { LensEngine.share(context, l.subject.bitmap, sticker = false) }
                }, width = 76.dp)
            }
        }
    }
}
