package com.javaram.gallery.ui.trash

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javaram.gallery.GalleryViewModel
import com.javaram.gallery.data.Media
import com.javaram.gallery.ui.components.ConfirmRequest
import com.javaram.gallery.ui.components.GlassDialog
import com.javaram.gallery.ui.components.GlassIconButton
import com.javaram.gallery.ui.components.GlassSurface
import com.javaram.gallery.ui.components.GridHandle
import com.javaram.gallery.ui.components.GroupMode
import com.javaram.gallery.ui.components.MediaGrid
import com.javaram.gallery.ui.components.ProgressiveGlass
import com.javaram.gallery.ui.components.pressable
import com.javaram.gallery.ui.components.rememberMediaRequest
import com.javaram.gallery.ui.home.BarAction
import com.javaram.gallery.ui.home.LargeHeader
import com.javaram.gallery.ui.theme.GalleryTheme
import com.javaram.gallery.ui.viewer.ViewerOverlay
import com.javaram.gallery.ui.viewer.ViewerSession
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlin.math.ceil

@Composable
fun TrashScreen(vm: GalleryViewModel, onBack: () -> Unit) {
    val c = GalleryTheme.colors
    val haze = remember { HazeState() }
    val items by vm.trash.collectAsStateWithLifecycle()
    val columns by vm.columns.collectAsStateWithLifecycle()
    var selectionMode by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    var session by remember { mutableStateOf<ViewerSession?>(null) }
    var hiddenId by remember { mutableStateOf<Long?>(null) }
    val handle = remember { GridHandle() }
    val state = rememberLazyGridState()
    val collapsed by remember { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 160 } }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 110.dp
    val byId = remember(items) { items.associateBy { it.id } }

    LaunchedEffect(Unit) { vm.loadTrash() }

    fun exitSelection() { selectionMode = false; selection = emptySet() }
    BackHandler(selectionMode) { exitSelection() }

    val request = rememberMediaRequest { ok ->
        if (ok) exitSelection()
        vm.scheduleRefresh(0)
        vm.loadTrash()
    }
    val nowSec = remember { System.currentTimeMillis() / 1000 }

    fun askRestore(targets: List<Media>, all: Boolean) {
        confirm = ConfirmRequest(
            title = if (all) "Restore all items?" else if (targets.size == 1) "Restore this item?" else "Restore ${targets.size} items?",
            message = "They'll go back to their original albums.",
            confirmLabel = "Restore",
            destructive = false,
        ) { request(vm.restoreRequest(targets)) }
    }

    fun askDelete(targets: List<Media>, all: Boolean) {
        confirm = ConfirmRequest(
            title = if (all) "Delete everything?" else if (targets.size == 1) "Delete permanently?" else "Delete ${targets.size} items permanently?",
            message = "This can't be undone.",
            confirmLabel = "Delete",
        ) { request(vm.deleteForeverRequest(targets)) }
    }

    Box(Modifier.fillMaxSize().background(c.background)) {
        Box(Modifier.fillMaxSize().haze(haze)) {
            MediaGrid(
                items = items,
                columns = columns,
                group = GroupMode.None,
                state = state,
                handle = handle,
                hiddenId = hiddenId,
                selection = selection,
                selectionMode = selectionMode,
                onSelectionChange = { selection = it },
                onEnterSelection = { selectionMode = true },
                onOpen = { m -> session = ViewerSession(items.map { it.id }, m.id, trash = true) },
                contentPadding = PaddingValues(bottom = bottom),
                onColumnsChange = vm::setColumns,
                badge = { m ->
                    if (m.dateExpires <= 0) null
                    else {
                        val days = ceil((m.dateExpires - nowSec) / 86400.0).toInt().coerceAtLeast(0)
                        if (days <= 1) "Today" else "$days days"
                    }
                },
                header = {
                    LargeHeader("Recently deleted", "Items are removed for good after 30 days.")
                },
            )
            if (items.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Outlined.AutoDelete, null, tint = c.secondary, modifier = Modifier.size(56.dp))
                    Text(
                        "Nothing in Recently deleted",
                        style = MaterialTheme.typography.titleMedium,
                        color = c.content,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
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
                    color = c.content,
                    modifier = Modifier.weight(1f).padding(start = 14.dp),
                )
                val allSelected = items.isNotEmpty() && selection.size == items.size
                GlassSurface(
                    haze,
                    Modifier.height(42.dp).pressable {
                        selection = if (allSelected) emptySet() else items.map { it.id }.toSet()
                    },
                    shape = CircleShape,
                ) {
                    Text(
                        if (allSelected) "Deselect all" else "Select all",
                        style = MaterialTheme.typography.titleSmall,
                        color = c.content,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else {
                GlassIconButton(haze, Icons.AutoMirrored.Outlined.ArrowBack, "Back", size = 42.dp, onClick = onBack)
                Spacer(Modifier.weight(1f))
                if (items.isNotEmpty()) {
                    GlassSurface(
                        haze,
                        Modifier.height(42.dp).pressable { selectionMode = true },
                        shape = CircleShape,
                    ) {
                        Text(
                            "Select",
                            style = MaterialTheme.typography.titleSmall,
                            color = c.content,
                            modifier = Modifier.padding(horizontal = 18.dp),
                        )
                    }
                }
            }
        }

        // Floating glass dock: only the controls are glass.
        if (items.isNotEmpty()) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 14.dp),
            ) {
                AnimatedContent(
                    targetState = selectionMode,
                    transitionSpec = {
                        (fadeIn(tween(200)) + slideInVertically { it / 2 }) togetherWith
                            (fadeOut(tween(150)) + slideOutVertically { it / 2 })
                    },
                    label = "trash-dock",
                ) { sel ->
                    val enabled = !sel || selection.isNotEmpty()
                    val targets = if (sel) items.filter { it.id in selection } else items
                    GlassSurface(haze, Modifier.height(66.dp), shape = CircleShape) {
                        Row(
                            Modifier.padding(horizontal = 10.dp).alpha(if (enabled) 1f else 0.45f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BarAction(
                                Icons.Outlined.Restore,
                                if (sel) "Restore" else "Restore all",
                                enabled,
                                { askRestore(targets, all = !sel) },
                            )
                            Spacer(Modifier.width(4.dp))
                            BarAction(
                                Icons.Outlined.DeleteForever,
                                if (sel) "Delete" else "Delete all",
                                enabled,
                                { askDelete(targets, all = !sel) },
                                tint = Color(0xFFFF453A),
                            )
                        }
                    }
                }
            }
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
