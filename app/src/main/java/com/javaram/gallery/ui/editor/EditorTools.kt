package com.javaram.gallery.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.javaram.gallery.editor.BrushStroke
import com.javaram.gallery.editor.FoundSubject
import com.javaram.gallery.editor.Mark
import com.javaram.gallery.editor.Markup
import com.javaram.gallery.editor.PenStroke
import com.javaram.gallery.editor.TextMark
import com.javaram.gallery.ui.components.pressable
import com.javaram.gallery.ui.theme.Accent
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** What a finger on the picture does. */
internal enum class StageMode { View, Markup, Erase, People }

internal enum class MarkMode(val label: String) { Pen("Pen"), Marker("Marker"), Text("Text") }

internal enum class AiMode { Erase, People }

internal val markColors = listOf(
    0xFFFFFFFF.toInt(), 0xFF111111.toInt(), 0xFFFF3B30.toInt(), 0xFFFF9500.toInt(),
    0xFFFFCC00.toInt(), 0xFF34C759.toInt(), 0xFF0A84FF.toInt(), 0xFFBF5AF2.toInt(),
)

/** Pen thickness and text height (fractions of the picture's width) for a 0..1 slider. */
internal fun penWidth(s: Float) = 0.003f + s * 0.03f
internal fun markerWidth(s: Float) = 0.015f + s * 0.06f
internal fun textSize(s: Float) = 0.03f + s * 0.12f
internal fun brushWidth(s: Float) = 0.02f + s * 0.1f

/**
 * The picture in the editor, with everything drawn over it: before/after split,
 * drawings and text, the AI eraser brush and the people/object picker. Long-press shows
 * the original while held.
 */
@Composable
internal fun EditorStage(
    shown: Bitmap,
    original: Bitmap?,
    matrix: ColorMatrix,
    transparent: Boolean,
    marks: List<Mark>,
    split: Float?,
    onSplit: (Float) -> Unit,
    mode: StageMode,
    markMode: MarkMode,
    markColor: Int,
    markSize: Float,
    selectedText: Int?,
    onMarks: (List<Mark>) -> Unit,
    onSelectText: (Int?) -> Unit,
    onNewText: (Offset) -> Unit,
    brush: List<BrushStroke>,
    brushSize: Float,
    onBrush: (List<BrushStroke>) -> Unit,
    subjects: List<FoundSubject>,
    chosen: Set<Int>,
    onToggle: (Int) -> Unit,
    checker: @Composable (Float) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val maxW = with(density) { maxWidth.toPx() }
        val maxH = with(density) { maxHeight.toPx() }
        val s = min(maxW / shown.width, maxH / shown.height)
        val bw = shown.width * s
        val bh = shown.height * s
        var holding by remember { mutableStateOf(false) }
        var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
        val curMarks by rememberUpdatedState(marks)
        val curBrush by rememberUpdatedState(brush)
        val curSel by rememberUpdatedState(selectedText)
        val curSubjects by rememberUpdatedState(subjects)
        val curToggle by rememberUpdatedState(onToggle)
        val shownImg = remember(shown) { shown.asImageBitmap() }
        val origImg = remember(original) { original?.asImageBitmap() }
        val subjectImgs = remember(subjects) { subjects.map { it.bitmap.asImageBitmap() } }

        fun norm(p: Offset) = Offset((p.x / bw).coerceIn(0f, 1f), (p.y / bh).coerceIn(0f, 1f))
        fun textAt(p: Offset): Int {
            val w = bw.roundToInt(); val h = bh.roundToInt()
            for (i in curMarks.indices.reversed()) {
                val m = curMarks[i] as? TextMark ?: continue
                if (Markup.textBounds(m, w, h).contains(p.x, p.y)) return i
            }
            return -1
        }

        Box(
            Modifier
                .size(with(density) { bw.toDp() }, with(density) { bh.toDp() })
                .pointerInput(mode, markMode, markColor, markSize, brushSize, split != null, bw, bh) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        when {
                            mode == StageMode.View && split != null -> {
                                down.consume()
                                onSplit((down.position.x / bw).coerceIn(0f, 1f))
                                drag(down.id) { ch -> ch.consume(); onSplit((ch.position.x / bw).coerceIn(0f, 1f)) }
                            }
                            mode == StageMode.View -> {
                                // Hold to see the original.
                                val up = withTimeoutOrNull(280) {
                                    var moved = false
                                    while (true) {
                                        val e = awaitPointerEvent()
                                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!c.pressed) break
                                        if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) { moved = true; break }
                                    }
                                    moved
                                }
                                if (up == null) {
                                    holding = true
                                    do {
                                        val e = awaitPointerEvent()
                                    } while (e.changes.any { it.pressed })
                                    holding = false
                                }
                            }
                            mode == StageMode.People -> {
                                val up = waitForUp(down.id)
                                if (up != null) {
                                    val px = up.x / s
                                    val py = up.y / s
                                    // Smallest subject under the finger wins (people in front of bigger things).
                                    val subs = curSubjects
                                    val hit = subs.indices.filter { subs[it].contains(px, py) }.minByOrNull { subs[it].area }
                                    if (hit != null) curToggle(hit)
                                }
                            }
                            mode == StageMode.Markup && markMode == MarkMode.Text -> {
                                val start = down.position
                                val i = textAt(start)
                                if (i >= 0) {
                                    onSelectText(i)
                                    val t0 = curMarks[i] as TextMark
                                    var movedAny = false
                                    drag(down.id) { ch ->
                                        ch.consume()
                                        movedAny = true
                                        val d = ch.position - start
                                        val list = curMarks.toMutableList()
                                        list[i] = t0.copy(center = Offset((t0.center.x + d.x / bw).coerceIn(0f, 1f), (t0.center.y + d.y / bh).coerceIn(0f, 1f)))
                                        onMarks(list)
                                    }
                                } else {
                                    val up = waitForUp(down.id)
                                    if (up != null) {
                                        if (curSel != null) onSelectText(null) else onNewText(norm(up))
                                    }
                                }
                            }
                            mode == StageMode.Markup || mode == StageMode.Erase -> {
                                down.consume()
                                val pts = arrayListOf(norm(down.position))
                                live = pts.toList()
                                drag(down.id) { ch ->
                                    ch.consume()
                                    val n = norm(ch.position)
                                    val last = pts.last()
                                    if (abs(n.x - last.x) + abs(n.y - last.y) > 0.002f) {
                                        pts += n
                                        live = pts.toList()
                                    }
                                }
                                live = emptyList()
                                if (mode == StageMode.Erase) {
                                    onBrush(curBrush + BrushStroke(pts.toList(), brushWidth(brushSize)))
                                } else {
                                    val hl = markMode == MarkMode.Marker
                                    onMarks(curMarks + PenStroke(pts.toList(), markColor, if (hl) markerWidth(markSize) else penWidth(markSize), hl))
                                }
                            }
                        }
                    }
                }
        ) {
            if (transparent && !holding) checker(shown.width.toFloat() / shown.height)
            val showOriginal = holding && origImg != null
            Image(
                bitmap = if (showOriginal) origImg!! else shownImg,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                colorFilter = if (showOriginal) null else ColorFilter.colorMatrix(matrix),
                modifier = Modifier.fillMaxSize(),
            )
            // Before / after: the original on the left of the divider.
            if (split != null && origImg != null && !holding) {
                Image(
                    bitmap = origImg,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize().drawWithContent {
                        clipRect(right = size.width * split) { this@drawWithContent.drawContent() }
                    },
                )
            }

            // People / objects picker
            if (mode == StageMode.People) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color.Black.copy(alpha = 0.25f))
                    subjects.forEachIndexed { i, sub ->
                        val on = i in chosen
                        drawImage(
                            subjectImgs[i],
                            dstOffset = IntOffset((sub.x * s).roundToInt(), (sub.y * s).roundToInt()),
                            dstSize = IntSize((sub.bitmap.width * s).roundToInt().coerceAtLeast(1), (sub.bitmap.height * s).roundToInt().coerceAtLeast(1)),
                            alpha = if (on) 0.6f else 0.28f,
                            colorFilter = ColorFilter.tint(if (on) Color(0xFFFF3B30) else Color.White),
                        )
                    }
                }
            }

            // Drawings and text (drawn exactly as they'll be saved)
            if (!holding) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width.roundToInt()
                    val h = size.height.roundToInt()
                    drawIntoCanvas { c ->
                        val extra = if (mode == StageMode.Markup && live.isNotEmpty()) {
                            val hl = markMode == MarkMode.Marker
                            listOf(PenStroke(live, markColor, if (hl) markerWidth(markSize) else penWidth(markSize), hl))
                        } else emptyList()
                        Markup.draw(c.nativeCanvas, w, h, marks + extra)
                    }
                    val sel = selectedText?.let { marks.getOrNull(it) as? TextMark }
                    if (sel != null && mode == StageMode.Markup) {
                        val r = Markup.textBounds(sel, w, h)
                        drawRoundRect(
                            Accent,
                            topLeft = Offset(r.left - 6f, r.top - 6f),
                            size = Size(r.width() + 12f, r.height() + 12f),
                            cornerRadius = CornerRadius(12f),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                    // Eraser brush: red paint over what will be removed.
                    if (mode == StageMode.Erase) {
                        val all = brush + if (live.isNotEmpty()) listOf(BrushStroke(live, brushWidth(brushSize))) else emptyList()
                        for (st in all) {
                            drawPath(
                                Markup.path(st.points, w, h).asComposePath(),
                                Color(0xFFFF3B30).copy(alpha = 0.5f),
                                style = Stroke(width = st.width * w, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }
                    }
                }
            }

            // Divider for before / after
            if (split != null && !holding) {
                Canvas(Modifier.fillMaxSize()) {
                    val x = size.width * split
                    drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                    drawCircle(Color.White, 15.dp.toPx(), Offset(x, size.height / 2))
                    drawCircle(Color.Black.copy(alpha = 0.55f), 15.dp.toPx(), Offset(x, size.height / 2), style = Stroke(1.dp.toPx()))
                    val a = 5.dp.toPx()
                    val cy = size.height / 2
                    drawLine(Color.Black, Offset(x - a * 1.6f, cy), Offset(x - a * 0.6f, cy - a), 2.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(Color.Black, Offset(x - a * 1.6f, cy), Offset(x - a * 0.6f, cy + a), 2.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(Color.Black, Offset(x + a * 1.6f, cy), Offset(x + a * 0.6f, cy - a), 2.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(Color.Black, Offset(x + a * 1.6f, cy), Offset(x + a * 0.6f, cy + a), 2.dp.toPx(), cap = StrokeCap.Round)
                }
                Row(Modifier.fillMaxWidth().padding(10.dp)) {
                    StageLabel("Before")
                    Spacer(Modifier.weight(1f))
                    StageLabel("After")
                }
            }
            if (holding) {
                Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.TopCenter) { StageLabel("Original") }
            }
        }
    }
}

@Composable
private fun StageLabel(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Waits for the finger to lift without dragging; returns where it lifted, or null for a drag. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitForUp(
    id: androidx.compose.ui.input.pointer.PointerId,
): Offset? {
    var start: Offset? = null
    while (true) {
        val e = awaitPointerEvent()
        val c = e.changes.firstOrNull { it.id == id } ?: return null
        if (start == null) start = c.previousPosition
        if (!c.pressed) return c.position
        if ((c.position - start).getDistance() > viewConfiguration.touchSlop) return null
    }
}

// ---------------- Markup panel ----------------

@Composable
internal fun MarkupPanel(
    mode: MarkMode,
    onMode: (MarkMode) -> Unit,
    color: Int,
    onColor: (Int) -> Unit,
    size: Float,
    onSize: (Float) -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    selected: TextMark?,
    onEditText: () -> Unit,
    onDeleteText: () -> Unit,
    onToggleBox: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selected != null) {
                Chip("Edit", Icons.Outlined.Edit, false, onEditText)
                Chip(if (selected.boxed) "No label" else "Label", Icons.Outlined.TextFields, selected.boxed, onToggleBox)
                Chip("Delete", Icons.Outlined.DeleteOutline, false, onDeleteText)
            } else {
                MarkMode.entries.forEach { m ->
                    val icon = when (m) {
                        MarkMode.Pen -> Icons.Outlined.Brush
                        MarkMode.Marker -> Icons.Outlined.Highlight
                        MarkMode.Text -> Icons.Outlined.TextFields
                    }
                    Chip(m.label, icon, m == mode) { onMode(m) }
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).pressable(enabled = canUndo, onClick = onUndo),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.Undo, "Undo", tint = Color.White.copy(alpha = if (canUndo) 1f else 0.35f), modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            markColors.forEach { c ->
                val on = c == (selected?.color ?: color)
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .border(if (on) 3.dp else 1.dp, if (on) Accent else Color.White.copy(alpha = 0.35f), CircleShape)
                        .pressable { onColor(c) }
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selected != null || mode == MarkMode.Text) "Text size" else "Size", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.width(66.dp))
            Slider(
                value = size,
                onValueChange = onSize,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent, inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Chip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(if (on) Color.White else Color.White.copy(alpha = 0.12f))
            .pressable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (on) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ---------------- AI panel ----------------

@Composable
internal fun AiPanel(
    mode: AiMode?,
    busy: String?,
    brushSize: Float,
    onBrushSize: (Float) -> Unit,
    hasBrush: Boolean,
    chosenCount: Int,
    canUndo: Boolean,
    onErase: () -> Unit,
    onPeople: () -> Unit,
    onEnhance: () -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    onUndo: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        when {
            busy != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(busy, color = Color.White, fontSize = 14.sp)
                }
            }
            mode == AiMode.Erase -> {
                Text("Paint over what you want to remove", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Brush", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.width(50.dp))
                    Slider(
                        value = brushSize,
                        onValueChange = onBrushSize,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent, inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
                        modifier = Modifier.weight(1f),
                    )
                }
                ActionRow(
                    left = "Cancel" to onCancel,
                    middle = if (hasBrush) "Clear" to onClear else null,
                    right = "Remove",
                    rightEnabled = hasBrush,
                    onRight = onApply,
                )
            }
            mode == AiMode.People -> {
                Text(
                    if (chosenCount == 0) "Tap people or objects to remove them" else "Tap to add or skip people and objects",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(14.dp))
                ActionRow(
                    left = "Cancel" to onCancel,
                    middle = null,
                    right = if (chosenCount <= 1) "Remove" else "Remove $chosenCount",
                    rightEnabled = chosenCount > 0,
                    onRight = onApply,
                )
            }
            else -> {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        listOf(
                            Triple("Erase", Icons.Outlined.AutoFixHigh, onErase),
                            Triple("Remove people", Icons.Outlined.PersonRemove, onPeople),
                            Triple("Enhance", Icons.Outlined.AutoAwesome, onEnhance),
                        )
                    ) { (label, icon, action) ->
                        Column(
                            Modifier
                                .width(104.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.White.copy(alpha = 0.1f))
                                .pressable(onClick = action)
                                .padding(vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(icon, label, tint = Color.White, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(label, color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
                if (canUndo) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).pressable(onClick = onUndo).padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Undo last removal", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    left: Pair<String, () -> Unit>,
    middle: Pair<String, () -> Unit>?,
    right: String,
    rightEnabled: Boolean,
    onRight: () -> Unit,
) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.height(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).pressable(onClick = left.second).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Close, null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(left.first, color = Color.White, fontSize = 13.sp)
        }
        if (middle != null) {
            Row(
                Modifier.height(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).pressable(onClick = middle.second).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { Text(middle.first, color = Color.White, fontSize = 13.sp) }
        }
        Row(
            Modifier
                .height(40.dp)
                .clip(CircleShape)
                .background(if (rightEnabled) Accent else Accent.copy(alpha = 0.35f))
                .pressable(enabled = rightEnabled, onClick = onRight)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(right, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
