package com.javaram.gallery.ui.viewer

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.javaram.gallery.GalleryViewModel
import com.javaram.gallery.data.Media
import com.javaram.gallery.data.thumb
import com.javaram.gallery.data.thumbKey
import com.javaram.gallery.ui.components.ConfirmRequest
import com.javaram.gallery.ui.components.GlassDialog
import com.javaram.gallery.ui.components.GlassIconButton
import com.javaram.gallery.ui.components.GlassPopupMenu
import com.javaram.gallery.ui.components.GlassSurface
import com.javaram.gallery.ui.components.GridHandle
import com.javaram.gallery.ui.components.PopupAnchor
import com.javaram.gallery.ui.components.SheetOption
import com.javaram.gallery.ui.components.openWith
import com.javaram.gallery.ui.components.rememberMediaRequest
import com.javaram.gallery.ui.components.setAs
import com.javaram.gallery.ui.components.shareMedia
import com.javaram.gallery.ui.components.toast
import com.javaram.gallery.ui.home.BarAction
import com.javaram.gallery.ui.theme.GalleryTheme
import com.javaram.gallery.util.Wallpaper
import com.javaram.gallery.util.WallpaperTarget
import com.javaram.gallery.util.viewerTitle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** What the viewer was opened with: the ordered photo ids and the one tapped. */
data class ViewerSession(val ids: List<Long>, val startId: Long, val trash: Boolean = false)

private enum class Phase { Opening, Open, Closing }

/** Interpolation between two on-screen states of the photo during open/close. */
private data class Morph(
    val clipFrom: Rect, val imageFrom: Rect,
    val clipTo: Rect, val imageTo: Rect,
    val bgFrom: Float, val bgTo: Float,
    val alphaFrom: Float = 1f, val alphaTo: Float = 1f,
)

/** Places the content at an absolute rect read during layout (no recomposition per frame). */
private fun Modifier.frame(rect: () -> Rect): Modifier = layout { measurable, constraints ->
    val r = rect()
    val p = measurable.measure(
        Constraints.fixed(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1))
    )
    layout(constraints.maxWidth, constraints.maxHeight) { p.place(r.left.roundToInt(), r.top.roundToInt()) }
}

@Composable
fun ViewerOverlay(
    session: ViewerSession,
    items: List<Media>,
    grid: GridHandle,
    vm: GalleryViewModel,
    onCurrentChanged: (Long?) -> Unit,
    onClosed: () -> Unit,
) {
    val context = LocalContext.current
    val c = GalleryTheme.colors
    val density = LocalDensity.current
    val haze = remember { HazeState() }
    val scope = rememberCoroutineScope()
    val canManage by vm.canManageMedia.collectAsStateWithLifecycle()
    val topInset = with(density) { (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp).toPx() }
    val bottomInset = with(density) { (WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 100.dp).toPx() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val screen = Rect(0f, 0f, w, h)

        val startIndex = remember { items.indexOfFirst { it.id == session.startId }.coerceAtLeast(0) }
        val pager = rememberPagerState(initialPage = startIndex) { items.size }
        val current: Media? = items.getOrNull(pager.currentPage.coerceAtMost(items.lastIndex))
        val currentRef by rememberUpdatedState(current)

        var phase by remember { mutableStateOf(Phase.Opening) }
        val progress = remember { Animatable(0f) }
        var morph by remember { mutableStateOf<Morph?>(null) }
        var morphMedia by remember { mutableStateOf<Media?>(null) }
        val drag = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
        var chrome by remember { mutableStateOf(true) }
        var showInfo by remember { mutableStateOf(false) }
        var moreAnchor by remember { mutableStateOf<Rect?>(null) }
        var showMore by remember { mutableStateOf(false) }
        var wallpaperFor by remember { mutableStateOf<Media?>(null) }
        var editing by remember { mutableStateOf<Media?>(null) }
        val hideInSafe = com.javaram.gallery.ui.safe.rememberHideInSafe(vm)
        var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
        val zoomStates = remember { mutableStateMapOf<Long, ZoomableImageState>() }
        val lens = remember { LensState() }
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        val index by vm.index.collectAsStateWithLifecycle()

        LaunchedEffect(current?.id) {
            onCurrentChanged(current?.id)
            lens.close()
        }

        fun aspect(m: Media): Float =
            if (m.displayWidth > 0 && m.displayHeight > 0) m.displayWidth.toFloat() / m.displayHeight else 1f

        fun fitRect(m: Media): Rect {
            val zs = zoomStates[m.id]?.zoomableState
            val b = zs?.transformedContentBounds
            if (b != null && b.width > 2f && b.height > 2f && (zs.zoomFraction ?: 0f) < 0.02f) return b
            if (m.displayWidth <= 0 || m.displayHeight <= 0) return screen
            val s = min(w / m.displayWidth, h / m.displayHeight)
            val fw = m.displayWidth * s
            val fh = m.displayHeight * s
            return Rect(Offset((w - fw) / 2f, (h - fh) / 2f), Size(fw, fh))
        }

        /** The full photo scaled so it covers [cell] (the grid shows a centre crop). */
        fun coverRect(cell: Rect, m: Media): Rect {
            val a = aspect(m)
            return if (a > cell.width / cell.height) {
                val iw = cell.height * a
                Rect(Offset(cell.center.x - iw / 2f, cell.top), Size(iw, cell.height))
            } else {
                val ih = cell.width / a
                Rect(Offset(cell.left, cell.center.y - ih / 2f), Size(cell.width, ih))
            }
        }

        // Only dragging down shrinks the photo; dragging up reveals the details.
        fun dragScale(): Float = (1f - drag.value.y.coerceAtLeast(0f) / h * 0.9f).coerceIn(0.4f, 1f)
        fun dragBg(): Float = (1f - drag.value.y.coerceAtLeast(0f) / (h * 0.4f)).coerceIn(0f, 1f)

        /** Where the current photo is drawn on screen (follows zoom and pan). */
        fun contentBounds(m: Media): Rect {
            val zs = zoomStates[m.id]?.zoomableState
            val b = zs?.transformedContentBounds
            return if (b != null && b.width > 2f && b.height > 2f) b else fitRect(m)
        }

        fun draggedRect(m: Media): Rect {
            val f = fitRect(m)
            val s = dragScale()
            val cx = w / 2f + (f.center.x - w / 2f) * s + drag.value.x
            val cy = h / 2f + (f.center.y - h / 2f) * s + drag.value.y
            return Rect(Offset(cx - f.width * s / 2f, cy - f.height * s / 2f), Size(f.width * s, f.height * s))
        }

        fun close() {
            if (phase == Phase.Closing) return
            val m = currentRef
            if (m == null) { onClosed(); return }
            val zs = zoomStates[m.id]?.zoomableState
            val zoomedBounds = zs?.transformedContentBounds?.takeIf { (zs.zoomFraction ?: 0f) > 0.02f && it.width > 2f }
            val from = zoomedBounds ?: draggedRect(m)
            val bgFrom = if (phase == Phase.Open) dragBg() else lerp(morph?.bgFrom ?: 1f, morph?.bgTo ?: 1f, progress.value)
            chrome = false
            showInfo = false
            showMore = false
            scope.launch {
                morphMedia = m
                morph = Morph(from, from, from, from, bgFrom, bgFrom)
                progress.snapTo(0f)
                phase = Phase.Closing
                drag.snapTo(Offset.Zero)
                grid.ensureVisible(m.id, topInset, bottomInset)
                val cell = grid.rectOf(m.id)
                morph = if (cell != null) {
                    Morph(from, from, cell, coverRect(cell, m), bgFrom, 0f)
                } else {
                    val shrunk = Rect(
                        Offset(from.center.x - from.width * 0.35f, from.center.y - from.height * 0.35f),
                        Size(from.width * 0.7f, from.height * 0.7f),
                    )
                    Morph(from, from, shrunk, shrunk, bgFrom, 0f, 1f, 0f)
                }
                progress.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 380f))
                onClosed()
            }
        }

        // ---- Opening: fly out of the grid cell ----
        LaunchedEffect(Unit) {
            val m = items.getOrNull(startIndex)
            if (m == null) { onClosed(); return@LaunchedEffect }
            val cell = grid.rectOf(m.id)
            val fit = fitRect(m)
            morphMedia = m
            morph = if (cell != null) {
                Morph(cell, coverRect(cell, m), fit, fit, 0f, 1f)
            } else {
                val small = Rect(
                    Offset(fit.center.x - fit.width * 0.42f, fit.center.y - fit.height * 0.42f),
                    Size(fit.width * 0.84f, fit.height * 0.84f),
                )
                Morph(small, small, fit, fit, 0f, 1f, 0f, 1f)
            }
            progress.snapTo(0f)
            progress.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 420f))
            phase = Phase.Open
        }

        LaunchedEffect(items.isEmpty()) { if (items.isEmpty() && phase == Phase.Open) onClosed() }

        BackHandler(enabled = phase != Phase.Closing) {
            when {
                lens.active -> lens.close()
                wallpaperFor != null -> wallpaperFor = null
                confirm != null -> confirm = null
                showMore -> showMore = false
                showInfo -> showInfo = false
                else -> close()
            }
        }

        // Hide status/navigation bars while the controls are hidden.
        val view = LocalView.current
        val controller = remember(view) {
            (view.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view) }
        }
        val barsVisible = chrome || phase != Phase.Open
        LaunchedEffect(barsVisible) {
            controller ?: return@LaunchedEffect
            if (barsVisible) controller.show(WindowInsetsCompat.Type.systemBars())
            else {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        DisposableEffect(controller) { onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) } }

        val bgColor by animateColorAsState(if (chrome) c.background else Color.Black, tween(250), label = "bg")

        // ---- Background dim ----
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val a = when (phase) {
                        Phase.Open -> dragBg()
                        else -> morph?.let { lerp(it.bgFrom, it.bgTo, progress.value) } ?: 0f
                    }
                    drawRect(bgColor, alpha = a)
                }
        )

        // ---- Pager (live, zoomable content) ----
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = if (phase == Phase.Open) 1f else 0f
                    val s = dragScale()
                    scaleX = s
                    scaleY = s
                    translationX = drag.value.x
                    translationY = drag.value.y
                }
                .haze(haze)
                // Vertical drag (up or down) shrinks the photo; release to send it home.
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (phase != Phase.Open || lens.active) return@awaitEachGesture
                        val m = currentRef ?: return@awaitEachGesture
                        if ((zoomStates[m.id]?.zoomableState?.zoomFraction ?: 0f) > 0.02f) return@awaitEachGesture
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var total = Offset.Zero
                        var started = false
                        var up = false
                        var cancelled = false
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            if (ev.changes.count { it.pressed } > 1) { cancelled = true; break }
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            val delta = ch.positionChange()
                            tracker.addPosition(ch.uptimeMillis, ch.position)
                            if (!started) {
                                total += delta
                                if (total.getDistance() > slop) {
                                    if (abs(total.y) > abs(total.x) * 1.15f) {
                                        started = true
                                        up = total.y < 0f
                                        if (!up) chrome = false
                                    } else return@awaitEachGesture
                                }
                            }
                            if (started) {
                                ch.consume()
                                val target = if (up) {
                                    // Resist upward: the photo nudges up a little, then details open.
                                    Offset(0f, (drag.value.y + delta.y * 0.4f).coerceIn(-h * 0.18f, 0f))
                                } else drag.value + delta
                                scope.launch { drag.snapTo(target) }
                            }
                        }
                        if (!started) return@awaitEachGesture
                        val v = tracker.calculateVelocity()
                        if (up) {
                            if (!cancelled && (drag.value.y < -h * 0.05f || v.y < -900f)) {
                                chrome = true
                                showInfo = true
                            }
                            scope.launch { drag.animateTo(Offset.Zero, spring(dampingRatio = 0.8f, stiffness = 420f)) }
                        } else if (!cancelled && (abs(drag.value.y) > h * 0.1f || abs(v.y) > 1400f)) {
                            close()
                        } else {
                            scope.launch {
                                drag.animateTo(Offset.Zero, spring(dampingRatio = 0.78f, stiffness = 520f))
                            }
                        }
                    }
                },
        ) {
            HorizontalPager(
                state = pager,
                userScrollEnabled = !lens.active,
                key = { items.getOrNull(it)?.id ?: it },
                beyondViewportPageCount = 1,
                pageSpacing = 16.dp,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val m = items.getOrNull(page) ?: return@HorizontalPager
                if (m.isVideo) {
                    VideoPage(
                        media = m,
                        isCurrent = page == pager.currentPage && phase == Phase.Open,
                        chromeVisible = chrome,
                        onTap = { chrome = !chrome },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    val zs = rememberZoomableImageState(rememberZoomableState())
                    DisposableEffect(m.id, zs) {
                        zoomStates[m.id] = zs
                        onDispose { zoomStates.remove(m.id) }
                    }
                    val model = remember(m.id, m.dateModified) { fullRequest(context, m) }
                    ZoomableAsyncImage(
                        model = model,
                        contentDescription = m.name,
                        state = zs,
                        modifier = Modifier.fillMaxSize(),
                        onClick = { chrome = !chrome },
                        onLongClick = { p ->
                            if (!session.trash) {
                                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                lens.begin(scope, context, m, p, { contentBounds(m) }) {
                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                }
                            }
                        },
                    )
                }
            }
        }

        // ---- Live text and lifted subjects ----
        if (phase == Phase.Open) {
            LensOverlay(lens, haze) { currentRef?.let { contentBounds(it) } }
        }

        // ---- Flying photo used while opening and closing ----
        val mm = morphMedia
        if (phase != Phase.Open && mm != null) {
            val model = remember(mm.id) {
                if (mm.isVideo) mm.thumb() else flyRequest(context, mm)
            }
            Box(
                Modifier
                    .frame {
                        val mo = morph ?: Morph(screen, screen, screen, screen, 0f, 0f)
                        lerp(mo.clipFrom, mo.clipTo, progress.value)
                    }
                    .graphicsLayer {
                        val mo = morph
                        alpha = if (mo == null) 0f else lerp(mo.alphaFrom, mo.alphaTo, progress.value)
                        clip = true
                    }
            ) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.frame {
                        val mo = morph ?: Morph(screen, screen, screen, screen, 0f, 0f)
                        val t = progress.value
                        val clipR = lerp(mo.clipFrom, mo.clipTo, t)
                        lerp(mo.imageFrom, mo.imageTo, t).translate(-clipR.left, -clipR.top)
                    },
                )
            }
        }

        // ---- Controls ----
        val showChrome = chrome && phase == Phase.Open && current != null && !lens.active
        AnimatedVisibility(
            visible = showChrome,
            enter = fadeIn(tween(200)) + slideInVertically { -it / 2 },
            exit = fadeOut(tween(140)) + slideOutVertically { -it / 2 },
        ) {
            val m = current ?: return@AnimatedVisibility
            val (day, time) = viewerTitle(m.dateTaken)
            Row(
                Modifier
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(haze, Icons.AutoMirrored.Outlined.ArrowBack, "Back", size = 44.dp) { close() }
                GlassSurface(
                    haze,
                    Modifier.padding(horizontal = 10.dp).weight(1f).height(44.dp),
                    shape = CircleShape,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(day, style = MaterialTheme.typography.titleSmall, color = c.content)
                        val sub = if (session.trash && m.dateExpires > 0) {
                            val days = ceil((m.dateExpires - System.currentTimeMillis() / 1000) / 86400.0).toInt().coerceAtLeast(0)
                            "Deletes in $days days"
                        } else time
                        Text(sub, style = MaterialTheme.typography.labelSmall, color = c.secondary)
                    }
                }
                GlassIconButton(haze, Icons.Outlined.Info, "Details", size = 44.dp) { showInfo = true }
            }
        }

        val request = rememberMediaRequest { vm.scheduleRefresh(0); if (session.trash) vm.loadTrash() }

        AnimatedVisibility(
            visible = showChrome,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(200)) + slideInVertically { it / 2 },
            exit = fadeOut(tween(140)) + slideOutVertically { it / 2 },
        ) {
            val m = current ?: return@AnimatedVisibility
            GlassSurface(
                haze,
                Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 14.dp)
                    .height(66.dp),
                shape = CircleShape,
            ) {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (session.trash) {
                        BarAction(Icons.Outlined.Restore, "Restore", true, {
                            confirm = ConfirmRequest(
                                "Restore this item?", "It will go back to its original album.", "Restore",
                                destructive = false,
                            ) { request(vm.restoreRequest(listOf(m))) }
                        })
                        BarAction(Icons.Outlined.DeleteForever, "Delete forever", true, {
                            confirm = ConfirmRequest(
                                "Delete permanently?", "This item will be erased and can't be recovered.", "Delete",
                            ) { request(vm.deleteForeverRequest(listOf(m))) }
                        }, tint = Color(0xFFFF453A))
                    } else {
                        val heart by animateFloatAsState(
                            if (m.isFavorite) 1f else 0.9f,
                            spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium),
                            label = "heart",
                        )
                        BarAction(Icons.Outlined.Share, "Share", true, { shareMedia(context, listOf(m)) })
                        Box(Modifier.graphicsLayer { scaleX = heart; scaleY = heart }) {
                            BarAction(
                                if (m.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                "Favourite", true,
                                { request(vm.favoriteRequest(listOf(m), !m.isFavorite)) },
                                tint = if (m.isFavorite) Color(0xFFFF3D5A) else c.content,
                            )
                        }
                        BarAction(Icons.Outlined.Edit, "Edit", !m.isVideo, {
                            if (!m.isVideo) editing = m
                        })
                        BarAction(Icons.Outlined.DeleteOutline, "Delete", true, {
                            val doIt = { request(vm.trashRequest(listOf(m))) }
                            if (canManage) {
                                confirm = ConfirmRequest(
                                    "Delete this ${if (m.isVideo) "video" else "photo"}?",
                                    "It will stay in Recently deleted for 30 days.",
                                    "Delete",
                                ) { doIt() }
                            } else doIt()
                        })
                        Box(Modifier.onGloballyPositioned { moreAnchor = it.boundsInRoot() }) {
                            BarAction(Icons.Outlined.MoreHoriz, "More", true, { showMore = true })
                        }
                    }
                }
            }
        }

        val hasText = current != null && !current.isVideo && !session.trash &&
            (index[current.id]?.text?.isNotBlank() == true)
        AnimatedVisibility(
            visible = showChrome && hasText,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 96.dp),
            enter = fadeIn(tween(200)) + androidx.compose.animation.scaleIn(initialScale = 0.8f),
            exit = fadeOut(tween(140)) + androidx.compose.animation.scaleOut(targetScale = 0.8f),
        ) {
            GlassIconButton(haze, Icons.Outlined.DocumentScanner, "Select text", size = 44.dp) {
                val m = current ?: return@GlassIconButton
                lens.begin(scope, context, m, null, { contentBounds(m) }) {}
            }
        }

        InfoPanel(
            visible = showInfo && current != null && phase == Phase.Open,
            media = current,
            vm = vm,
            haze = haze,
            onDismiss = { showInfo = false },
        )

        val m = current
        GlassPopupMenu(
            visible = showMore && m != null,
            state = haze,
            anchor = moreAnchor,
            placement = PopupAnchor.AboveEnd,
            options = if (m == null) emptyList() else buildList {
                if (!m.isVideo) add(SheetOption("Set as wallpaper", Icons.Outlined.Wallpaper) { wallpaperFor = m })
                if (!session.trash) add(SheetOption("Move to Private safe", Icons.Outlined.Lock) { hideInSafe(listOf(m)) })
                add(SheetOption("Use in another app…", Icons.Outlined.OpenInNew) { setAs(context, m) })
                add(SheetOption("Open with…", Icons.Outlined.OpenInNew) { openWith(context, m) })
                add(SheetOption("Details", Icons.Outlined.Info) { showInfo = true })
            },
            onDismiss = { showMore = false },
        )

        val wp = wallpaperFor
        GlassPopupMenu(
            visible = wp != null,
            state = haze,
            anchor = moreAnchor,
            placement = PopupAnchor.AboveEnd,
            title = "Set as wallpaper on",
            options = WallpaperTarget.entries.map { target ->
                SheetOption(target.label, Icons.Outlined.Wallpaper) {
                    val media = wp ?: return@SheetOption
                    toast(context, "Setting wallpaper…")
                    scope.launch {
                        val ok = Wallpaper.set(context, media, target)
                        toast(context, if (ok) "Wallpaper set" else "Couldn't set this photo as wallpaper")
                    }
                }
            },
            onDismiss = { wallpaperFor = null },
        )

        GlassDialog(request = confirm, state = haze, onDismiss = { confirm = null })

        val ed = editing
        if (ed != null) {
            com.javaram.gallery.ui.editor.EditorOverlay(media = ed, vm = vm, onClose = { editing = null })
        }
    }
}

private fun fullRequest(context: android.content.Context, m: Media): ImageRequest =
    ImageRequest.Builder(context)
        .data(m.uri)
        .placeholderMemoryCacheKey(thumbKey(m.thumb()))
        .crossfade(false)
        .build()

/** Screen-sized copy used for the flying open/close animation; cached so closing is instant. */
private fun flyRequest(context: android.content.Context, m: Media): ImageRequest =
    ImageRequest.Builder(context)
        .data(m.uri)
        .size(1440)
        .placeholderMemoryCacheKey(thumbKey(m.thumb()))
        .memoryCacheKey("fly:${m.id}:${m.dateModified}")
        .crossfade(false)
        .build()
