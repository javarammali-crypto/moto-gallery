package com.javaram.gallery.ui.album

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javaram.gallery.GalleryViewModel
import com.javaram.gallery.data.Media
import com.javaram.gallery.ui.components.ConfirmRequest
import com.javaram.gallery.ui.components.GlassDialog
import com.javaram.gallery.ui.components.GlassIconButton
import com.javaram.gallery.ui.components.GridHandle
import com.javaram.gallery.ui.viewer.ViewerOverlay
import com.javaram.gallery.ui.viewer.ViewerSession
import com.javaram.gallery.ui.components.GroupMode
import com.javaram.gallery.ui.components.MediaGrid
import com.javaram.gallery.ui.components.ProgressiveGlass
import com.javaram.gallery.ui.components.rememberMediaRequest
import com.javaram.gallery.ui.components.shareMedia
import com.javaram.gallery.ui.home.LargeHeader
import com.javaram.gallery.ui.home.SelectionBar
import com.javaram.gallery.ui.theme.GalleryTheme
import com.javaram.gallery.util.countLabel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

@Composable
fun AlbumScreen(
    vm: GalleryViewModel,
    albumKey: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val haze = remember { HazeState() }
    val all by vm.all.collectAsStateWithLifecycle()
    val columns by vm.columns.collectAsStateWithLifecycle()
    val items = remember(all, albumKey) { vm.itemsFor(albumKey, all) }
    val title = remember(albumKey, all) { vm.titleFor(albumKey) }
    val state = rememberLazyGridState()
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var grouped by rememberSaveable { mutableStateOf(false) }
    var session by remember { mutableStateOf<ViewerSession?>(null) }
    var hiddenId by remember { mutableStateOf<Long?>(null) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    val handle = remember { GridHandle() }
    val canManage by vm.canManageMedia.collectAsStateWithLifecycle()
    val byId = remember(all) { all.associateBy { it.id } }

    fun exitSelection() { selectionMode = false; selection = emptySet() }
    BackHandler(selectionMode) { exitSelection() }

    val request = rememberMediaRequest { ok -> if (ok) exitSelection(); vm.scheduleRefresh(0) }
    val selectedItems = remember(selection, items) { items.filter { it.id in selection } }
    val collapsed by remember { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 160 } }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 110.dp
    val videos = remember(items) { items.count { it.isVideo } }

    Box(Modifier.fillMaxSize().background(GalleryTheme.colors.background)) {
        Box(Modifier.fillMaxSize().haze(haze)) {
            MediaGrid(
                items = items,
                columns = columns,
                group = if (grouped) GroupMode.Day else GroupMode.None,
                state = state,
                handle = handle,
                hiddenId = hiddenId,
                selection = selection,
                selectionMode = selectionMode,
                onSelectionChange = { selection = it },
                onEnterSelection = { selectionMode = true },
                onOpen = { m -> session = ViewerSession(items.map { it.id }, m.id) },
                contentPadding = PaddingValues(bottom = bottom),
                onColumnsChange = vm::setColumns,
                header = {
                    LargeHeader(
                        title,
                        "${countLabel(items.size - videos)} photos · ${countLabel(videos)} videos",
                    )
                },
            )
        }

        val topAlpha by animateFloatAsState(if (collapsed || selectionMode) 1f else 0f, tween(220), label = "top")
        ProgressiveGlass(haze, Modifier.fillMaxWidth().height(110.dp), visibleAlpha = topAlpha) {}
        Row(
            Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                GlassIconButton(haze, Icons.Filled.Close, "Cancel", size = 42.dp) { exitSelection() }
                Text(
                    if (selection.isEmpty()) "Select items" else "${selection.size} selected",
                    style = MaterialTheme.typography.titleMedium,
                    color = GalleryTheme.colors.content,
                    modifier = Modifier.weight(1f).padding(start = 14.dp),
                )
            } else {
                GlassIconButton(haze, Icons.AutoMirrored.Outlined.ArrowBack, "Back", size = 42.dp, onClick = onBack)
                Box(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    androidx.compose.animation.AnimatedVisibility(collapsed, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
                        Text(
                            title, style = MaterialTheme.typography.titleMedium,
                            color = GalleryTheme.colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                GlassIconButton(haze, Icons.Outlined.GridView, "Group by day", size = 42.dp) { grouped = !grouped }
                Spacer(Modifier.width(10.dp))
                GlassIconButton(haze, Icons.Outlined.Checklist, "Select", size = 42.dp) { selectionMode = true }
            }
        }

        AnimatedVisibility(
            visible = selectionMode,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            val allFav = selectedItems.isNotEmpty() && selectedItems.all { it.isFavorite }
            SelectionBar(
                haze = haze,
                enabled = selection.isNotEmpty(),
                allFavorite = allFav,
                onShare = { shareMedia(context, selectedItems) },
                onFavorite = { request(vm.favoriteRequest(selectedItems, !allFav)) },
                onDelete = {
                    val targets = selectedItems
                    val doIt = { request(vm.trashRequest(targets)) }
                    if (canManage) {
                        confirm = ConfirmRequest(
                            if (targets.size == 1) "Delete this item?" else "Delete ${targets.size} items?",
                            "They'll stay in Recently deleted for 30 days.",
                            "Delete",
                        ) { doIt() }
                    } else doIt()
                },
            )
        }

        GlassDialog(request = confirm, state = haze, onDismiss = { confirm = null })

        val sess = session
        if (sess != null) {
            val viewerItems = remember(sess, byId) { sess.ids.mapNotNull { byId[it] } }
            ViewerOverlay(
                session = sess,
                items = viewerItems,
                grid = handle,
                vm = vm,
                onCurrentChanged = { hiddenId = it },
                onClosed = { session = null; hiddenId = null },
            )
        }
    }
}
