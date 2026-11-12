package com.javaram.gallery.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.javaram.gallery.data.Media
import com.javaram.gallery.data.thumb
import com.javaram.gallery.ui.theme.Accent
import com.javaram.gallery.ui.theme.GalleryTheme
import com.javaram.gallery.util.dayLabel
import com.javaram.gallery.util.duration
import com.javaram.gallery.util.monthLabel
import com.javaram.gallery.util.toLocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class GroupMode { None, Day, Month }

sealed interface GridEntry {
    val key: Any
    val label: String
}

data class HeaderEntry(override val label: String, val ids: List<Long>) : GridEntry {
    override val key: Any get() = "h:$label"
}

data class MediaEntry(val media: Media, override val label: String) : GridEntry {
    override val key: Any get() = media.id
}

fun buildEntries(items: List<Media>, mode: GroupMode): List<GridEntry> {
    if (mode == GroupMode.None) {
        return items.map { MediaEntry(it, monthLabel(it.dateTaken.toLocalDate())) }
    }
    val today = LocalDate.now()
    val out = ArrayList<GridEntry>(items.size + 64)
    var currentLabel: String? = null
    var headerIndex = -1
    val pendingIds = ArrayList<Long>()
    fun flush() {
        if (headerIndex >= 0) {
            out[headerIndex] = HeaderEntry(currentLabel!!, ArrayList(pendingIds))
        }
        pendingIds.clear()
    }
    for (m in items) {
        val date = m.dateTaken.toLocalDate()
        val label = if (mode == GroupMode.Day) dayLabel(date, today) else monthLabel(date)
        if (label != currentLabel) {
            flush()
            currentLabel = label
            headerIndex = out.size
            out += HeaderEntry(label, emptyList())
        }
        pendingIds += m.id
        out += MediaEntry(m, label)
    }
    flush()
    return out
}

@Composable
fun MediaGrid(
    items: List<Media>,
    columns: Int,
    group: GroupMode,
    state: LazyGridState,
    selection: Set<Long>,
    selectionMode: Boolean,
    onSelectionChange: (Set<Long>) -> Unit,
    onEnterSelection: () -> Unit,
    onOpen: (Media) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onColumnsChange: ((Int) -> Unit)? = null,
    badge: ((Media) -> String?)? = null,
    header: (@Composable () -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val entries = remember(items, group) { buildEntries(items, group) }
    val indexOf = remember(items) { HashMap<Long, Int>(items.size * 2).apply { items.forEachIndexed { i, m -> put(m.id, i) } } }
    val cells = remember { HashMap<Long, LayoutCoordinates>() }
    var gridCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val currentSelection by rememberUpdatedState(selection)
    val currentMode by rememberUpdatedState(selectionMode)
    val currentColumns by rememberUpdatedState(columns)
    val currentItems by rememberUpdatedState(items)
    val onSelect by rememberUpdatedState(onSelectionChange)
    val onEnter by rememberUpdatedState(onEnterSelection)
    val onCols by rememberUpdatedState(onColumnsChange)

    // Drag-to-select state
    var anchor by remember { mutableStateOf(-1) }
    var removing by remember { mutableStateOf(false) }
    var base by remember { mutableStateOf(emptySet<Long>()) }
    var lastPointer by remember { mutableStateOf(Offset.Unspecified) }
    var autoScroll by remember { mutableFloatStateOf(0f) }

    fun hit(window: Offset): Long? {
        for ((id, c) in cells) {
            if (c.isAttached && c.boundsInWindow().contains(window)) return id
        }
        return null
    }

    fun dragTo(window: Offset) {
        if (anchor < 0) return
        val id = hit(window) ?: return
        val idx = indexOf[id] ?: return
        val list = currentItems
        val range = (min(anchor, idx)..max(anchor, idx)).mapNotNull { list.getOrNull(it)?.id }
        onSelect(if (removing) base - range.toSet() else base + range)
    }

    LaunchedEffect(autoScroll != 0f) {
        while (autoScroll != 0f) {
            state.scrollBy(autoScroll)
            if (lastPointer.isSpecified) dragTo(lastPointer)
            withFrameNanos { }
        }
    }

    Box(modifier) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { gridCoords = it }
                // Pinch with two fingers to change the number of columns.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        var acc = 1f
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val cb = onCols
                            if (cb != null && ev.changes.count { it.pressed } >= 2) {
                                acc *= ev.calculateZoom()
                                if (acc > 1.3f && currentColumns > 2) {
                                    cb(currentColumns - 1); acc = 1f
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                } else if (acc < 0.77f && currentColumns < 7) {
                                    cb(currentColumns + 1); acc = 1f
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                ev.changes.forEach { it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                }
                // Long-press to start selecting, then drag across photos to select a range.
                .pointerInput(Unit) {
                    val edge = with(density) { 90.dp.toPx() }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                if (ev.changes.size > 1) return@withTimeoutOrNull true
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                                if (!c.pressed || c.isConsumed) return@withTimeoutOrNull true
                                if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                    return@withTimeoutOrNull true
                                }
                            }
                            @Suppress("UNREACHABLE_CODE")
                            true
                        }
                        if (ended != null) return@awaitEachGesture
                        val coords = gridCoords ?: return@awaitEachGesture
                        val startWindow = coords.localToWindow(down.position)
                        val id = hit(startWindow) ?: return@awaitEachGesture
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (!currentMode) onEnter()
                        anchor = indexOf[id] ?: -1
                        removing = id in currentSelection
                        base = currentSelection
                        lastPointer = startWindow
                        dragTo(startWindow)
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            ev.changes.forEach { it.consume() }
                            if (!c.pressed) break
                            val w = coords.localToWindow(c.position)
                            lastPointer = w
                            dragTo(w)
                            val h = coords.size.height
                            autoScroll = when {
                                c.position.y < edge -> -((edge - c.position.y) / edge) * 28f
                                c.position.y > h - edge -> ((c.position.y - (h - edge)) / edge) * 28f
                                else -> 0f
                            }
                        }
                        autoScroll = 0f
                        anchor = -1
                        lastPointer = Offset.Unspecified
                    }
                },
        ) {
            if (header != null) {
                item(key = "grid-header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    header()
                }
            }
            items(
                count = entries.size,
                key = { entries[it].key },
                span = { if (entries[it] is HeaderEntry) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                contentType = { if (entries[it] is HeaderEntry) "section" else "media" },
            ) { i ->
                when (val e = entries[i]) {
                    is HeaderEntry -> SectionHeader(
                        entry = e,
                        selectionMode = selectionMode,
                        allSelected = e.ids.isNotEmpty() && selection.containsAll(e.ids),
                        onToggleAll = { all ->
                            onSelectionChange(if (all) selection + e.ids else selection - e.ids.toSet())
                        },
                        modifier = Modifier.animateItem(),
                    )
                    is MediaEntry -> {
                        val m = e.media
                        MediaCell(
                            media = m,
                            selected = m.id in selection,
                            selectionMode = selectionMode,
                            badge = badge?.invoke(m),
                            modifier = Modifier
                                .animateItem(fadeInSpec = null, fadeOutSpec = null)
                                .onGloballyPositioned { cells[m.id] = it },
                            onClick = {
                                if (selectionMode) {
                                    onSelectionChange(if (m.id in selection) selection - m.id else selection + m.id)
                                } else onOpen(m)
                            },
                        )
                        DisposableEffect(m.id) { onDispose { cells.remove(m.id) } }
                    }
                }
            }
        }

        if (entries.size > 80) {
            FastScroller(
                state = state,
                labelFor = { idx ->
                    val offset = if (header != null) 1 else 0
                    entries.getOrNull((idx - offset).coerceAtLeast(0))?.label
                },
                total = entries.size + (if (header != null) 1 else 0),
                topPadding = contentPadding.calculateTopPadding(),
                bottomPadding = contentPadding.calculateBottomPadding(),
                onScrollTo = { idx -> scope.launch { state.scrollToItem(idx) } },
            )
        }
    }
}

@Composable
private fun SectionHeader(
    entry: HeaderEntry,
    selectionMode: Boolean,
    allSelected: Boolean,
    onToggleAll: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = GalleryTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            entry.label,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = c.content,
            modifier = Modifier.weight(1f),
        )
        AnimatedVisibility(selectionMode, enter = fadeIn(), exit = fadeOut()) {
            Text(
                if (allSelected) "Deselect" else "Select all",
                color = Accent,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.noRippleClickable { onToggleAll(!allSelected) },
            )
        }
    }
}

@Composable
fun MediaCell(
    media: Media,
    selected: Boolean,
    selectionMode: Boolean,
    badge: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = GalleryTheme.colors
    val scale by animateFloatAsState(
        if (selected) 0.84f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "sel",
    )
    val corner by animateFloatAsState(if (selected) 12f else 0f, tween(180), label = "corner")
    Box(
        modifier
            .aspectRatio(1f)
            .noRippleClickable(onClick),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(if (corner > 0.5f) RoundedCornerShape(corner.dp) else RectangleShape)
                .background(if (c.dark) Color(0xFF161618) else Color(0xFFE9E9EC))
        ) {
            AsyncImage(
                model = media.thumb(),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .sharedMedia(media.id)
                    .fillMaxSize(),
            )
            if (media.isVideo) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(28.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f))))
                )
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 5.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
                    Text(duration(media.durationMs), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (media.isFavorite) {
                Icon(
                    Icons.Filled.Favorite, null, tint = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).size(13.dp),
                )
            }
            if (badge != null) {
                Text(
                    badge,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = selectionMode,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle, null, tint = Accent,
                    modifier = Modifier
                        .size(22.dp)
                        .background(Color.White, CircleShape),
                )
            } else {
                Box(
                    Modifier
                        .size(20.dp)
                        .background(Color.Black.copy(alpha = 0.15f), CircleShape)
                        .border(1.5.dp, Color.White, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun FastScroller(
    state: LazyGridState,
    labelFor: (Int) -> String?,
    total: Int,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onScrollTo: (Int) -> Unit,
) {
    val c = GalleryTheme.colors
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val scrolling = state.isScrollInProgress

    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) visible = true
        else {
            delay(1400)
            visible = false
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(top = topPadding + 8.dp, bottom = bottomPadding + 8.dp)
    ) {
        val density = LocalDensity.current
        val thumbH = 44.dp
        val trackPx = with(density) { (maxHeight - thumbH).toPx() }.coerceAtLeast(1f)
        val fraction = if (dragging) dragFraction else {
            val first = state.firstVisibleItemIndex
            val visibleCount = state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
            (first.toFloat() / (total - visibleCount).coerceAtLeast(1)).coerceIn(0f, 1f)
        }
        val y = (fraction * trackPx).roundToInt()

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (dragging) {
                    val label = labelFor(state.firstVisibleItemIndex)
                    if (label != null) {
                        Text(
                            label,
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset { IntOffset(0, y) }
                                .padding(end = 46.dp, top = 4.dp)
                                .background(Accent, RoundedCornerShape(50))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .width(40.dp)
                        .fillMaxHeight()
                        .pointerInput(total) {
                            val thumbPx = thumbH.toPx()
                            fun jump(py: Float) {
                                val f = ((py - thumbPx / 2) / trackPx).coerceIn(0f, 1f)
                                dragFraction = f
                                onScrollTo((f * (total - 1)).roundToInt())
                            }
                            detectVerticalDragGestures(
                                onDragStart = { dragging = true; jump(it.y) },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                                onVerticalDrag = { change, _ -> change.consume(); jump(change.position.y) },
                            )
                        },
                ) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset { IntOffset(0, y) }
                            .padding(end = 4.dp)
                            .size(width = 30.dp, height = thumbH)
                            .background(c.card.copy(alpha = 0.95f), RoundedCornerShape(50))
                            .border(0.6.dp, c.divider, RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.UnfoldMore, null, tint = c.secondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
