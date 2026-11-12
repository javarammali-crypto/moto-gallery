package com.javaram.gallery.ui.viewer

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Wallpaper
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.request.ImageRequest
import com.javaram.gallery.GalleryViewModel
import com.javaram.gallery.data.Media
import com.javaram.gallery.data.thumb
import com.javaram.gallery.data.thumbKey
import com.javaram.gallery.ui.components.GlassActionSheet
import com.javaram.gallery.ui.components.GlassIconButton
import com.javaram.gallery.ui.components.GlassSurface
import com.javaram.gallery.ui.components.SheetOption
import com.javaram.gallery.ui.components.openWith
import com.javaram.gallery.ui.components.rememberMediaRequest
import com.javaram.gallery.ui.components.setAs
import com.javaram.gallery.ui.components.shareMedia
import com.javaram.gallery.ui.components.sharedMedia
import com.javaram.gallery.ui.components.toast
import com.javaram.gallery.ui.home.BarAction
import com.javaram.gallery.ui.theme.GalleryTheme
import com.javaram.gallery.util.viewerTitle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.delay
import me.saket.telephoto.flick.FlickToDismiss
import me.saket.telephoto.flick.FlickToDismissState
import me.saket.telephoto.flick.rememberFlickToDismissState
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage

@Composable
fun ViewerScreen(vm: GalleryViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val c = GalleryTheme.colors
    val haze = remember { HazeState() }
    val items by vm.viewerItems.collectAsStateWithLifecycle()
    val startIndex = remember { vm.viewerItems.value.indexOfFirst { it.id == vm.viewerStartId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = startIndex) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var flickFraction by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(items.isEmpty()) { if (items.isEmpty()) onBack() }
    val current: Media? = items.getOrNull(pager.currentPage.coerceAtMost(items.lastIndex))

    BackHandler(showInfo || showMore) { showInfo = false; showMore = false }

    // Hide status/navigation bars when the controls are hidden.
    val view = LocalView.current
    val controller = remember(view) {
        (view.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view) }
    }
    LaunchedEffect(chrome) {
        controller ?: return@LaunchedEffect
        if (chrome) controller.show(WindowInsetsCompat.Type.systemBars())
        else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(controller) { onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) } }

    val request = rememberMediaRequest { ok ->
        vm.scheduleRefresh(0)
    }
    var pendingTrashId by remember { mutableStateOf<Long?>(null) }
    val trashRequest = rememberMediaRequest { ok ->
        val id = pendingTrashId
        if (ok && id != null) vm.removeFromViewer(setOf(id))
        pendingTrashId = null
        vm.scheduleRefresh(0)
    }

    val bg by animateColorAsState(if (chrome) c.background else Color.Black, tween(250), label = "bg")

    Box(
        Modifier
            .fillMaxSize()
            .background(bg.copy(alpha = (1f - flickFraction * 1.6f).coerceIn(0f, 1f)))
    ) {
        HorizontalPager(
            state = pager,
            key = { items.getOrNull(it)?.id ?: it },
            beyondViewportPageCount = 1,
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxSize().haze(haze),
        ) { page ->
            val m = items.getOrNull(page) ?: return@HorizontalPager
            val isCurrent = page == pager.currentPage
            val flick = rememberFlickToDismissState(dismissThresholdRatio = 0.12f, rotateOnDrag = false)
            if (isCurrent) {
                LaunchedEffect(flick.offsetFraction) { flickFraction = flick.offsetFraction }
            }
            val gesture = flick.gestureState
            LaunchedEffect(gesture) {
                if (gesture is FlickToDismissState.GestureState.Dismissing) {
                    delay(gesture.animationDuration / 3)
                    onBack()
                }
            }
            FlickToDismiss(state = flick) {
                if (m.isVideo) {
                    VideoPage(
                        media = m,
                        isCurrent = isCurrent,
                        chromeVisible = chrome,
                        onTap = { chrome = !chrome },
                        modifier = Modifier.sharedMedia(m.id).fillMaxSize(),
                    )
                } else {
                    val model = remember(m) {
                        ImageRequest.Builder(context)
                            .data(m.uri)
                            .placeholderMemoryCacheKey(thumbKey(m.thumb()))
                            .crossfade(false)
                            .build()
                    }
                    ZoomableAsyncImage(
                        model = model,
                        contentDescription = m.name,
                        modifier = Modifier.sharedMedia(m.id).fillMaxSize(),
                        onClick = { chrome = !chrome },
                    )
                }
            }
        }

        // ---- Top bar ----
        AnimatedVisibility(
            visible = chrome && current != null,
            enter = fadeIn(tween(200)) + slideInVertically { -it / 2 },
            exit = fadeOut(tween(160)) + slideOutVertically { -it / 2 },
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
                GlassIconButton(haze, Icons.AutoMirrored.Outlined.ArrowBack, "Back", size = 44.dp, onClick = onBack)
                GlassSurface(
                    haze,
                    Modifier.padding(horizontal = 10.dp).weight(1f).height(44.dp),
                    shape = CircleShape,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(day, style = MaterialTheme.typography.titleSmall, color = c.content)
                        Text(time, style = MaterialTheme.typography.labelSmall, color = c.secondary)
                    }
                }
                GlassIconButton(haze, Icons.Outlined.Info, "Details", size = 44.dp) { showInfo = true }
            }
        }

        // ---- Bottom bar ----
        AnimatedVisibility(
            visible = chrome && current != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(200)) + slideInVertically { it / 2 },
            exit = fadeOut(tween(160)) + slideOutVertically { it / 2 },
        ) {
            val m = current ?: return@AnimatedVisibility
            val heartScale by animateFloatAsState(
                if (m.isFavorite) 1f else 0.92f,
                spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium),
                label = "heart",
            )
            GlassSurface(
                haze,
                Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 14.dp)
                    .height(66.dp),
                shape = CircleShape,
            ) {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BarAction(Icons.Outlined.Share, "Share", true, { shareMedia(context, listOf(m)) })
                    Box(Modifier.graphicsLayer { scaleX = heartScale; scaleY = heartScale }) {
                        BarAction(
                            if (m.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            "Favourite",
                            true,
                            { request(vm.favoriteRequest(listOf(m), !m.isFavorite)) },
                            tint = if (m.isFavorite) Color(0xFFFF3D5A) else c.content,
                        )
                    }
                    BarAction(Icons.Outlined.Edit, "Edit", true, {
                        toast(context, "The photo editor arrives in the next update")
                    })
                    BarAction(Icons.Outlined.DeleteOutline, "Delete", true, {
                        pendingTrashId = m.id
                        trashRequest(vm.trashRequest(listOf(m)))
                    })
                    BarAction(Icons.Outlined.MoreHoriz, "More", true, { showMore = true })
                }
            }
        }

        // ---- Details panel ----
        InfoPanel(
            visible = showInfo && current != null,
            media = current,
            vm = vm,
            haze = haze,
            onDismiss = { showInfo = false },
        )

        val m = current
        GlassActionSheet(
            visible = showMore && m != null,
            state = haze,
            title = null,
            options = if (m == null) emptyList() else listOf(
                SheetOption("Set as…", Icons.Outlined.Wallpaper) { setAs(context, m) },
                SheetOption("Open with…", Icons.Outlined.OpenInNew) { openWith(context, m) },
                SheetOption("Details", Icons.Outlined.Info) { showInfo = true },
            ),
            onDismiss = { showMore = false },
        )
    }
}
