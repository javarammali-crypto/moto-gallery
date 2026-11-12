package com.javaram.gallery.ui.trash

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javaram.gallery.GalleryViewModel
import com.javaram.gallery.ui.components.GlassIconButton
import com.javaram.gallery.ui.components.GlassSurface
import com.javaram.gallery.ui.components.GroupMode
import com.javaram.gallery.ui.components.MediaGrid
import com.javaram.gallery.ui.components.ProgressiveGlass
import com.javaram.gallery.ui.components.pressable
import com.javaram.gallery.ui.components.rememberMediaRequest
import com.javaram.gallery.ui.home.BarAction
import com.javaram.gallery.ui.home.LargeHeader
import com.javaram.gallery.ui.theme.GalleryTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlin.math.ceil

@Composable
fun TrashScreen(vm: GalleryViewModel, onBack: () -> Unit) {
    val c = GalleryTheme.colors
    val haze = remember { HazeState() }
    val items by vm.trash.collectAsStateWithLifecycle()
    val columns by vm.columns.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    val state = rememberLazyGridState()
    val collapsed by remember { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 160 } }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 110.dp

    LaunchedEffect(Unit) { vm.loadTrash() }

    val request = rememberMediaRequest { ok ->
        if (ok) selection = emptySet()
        vm.scheduleRefresh(0)
        vm.loadTrash()
    }
    val targets = remember(selection, items) {
        if (selection.isEmpty()) items else items.filter { it.id in selection }
    }
    val nowSec = remember { System.currentTimeMillis() / 1000 }

    Box(Modifier.fillMaxSize().background(c.background)) {
        Box(Modifier.fillMaxSize().haze(haze)) {
            MediaGrid(
                items = items,
                columns = columns,
                group = GroupMode.None,
                state = state,
                selection = selection,
                selectionMode = true,
                onSelectionChange = { selection = it },
                onEnterSelection = {},
                onOpen = {},
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
                    LargeHeader(
                        "Recently deleted",
                        "Items are removed for good after 30 days.",
                    )
                },
            )
            if (items.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
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

        val topAlpha by animateFloatAsState(if (collapsed) 1f else 0f, tween(220), label = "top")
        ProgressiveGlass(haze, Modifier.fillMaxWidth().height(110.dp), visibleAlpha = topAlpha) {}
        Row(
            Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(haze, Icons.AutoMirrored.Outlined.ArrowBack, "Back", size = 42.dp, onClick = onBack)
            Text(
                if (selection.isEmpty()) "" else "${selection.size} selected",
                style = MaterialTheme.typography.titleMedium,
                color = c.content,
                modifier = Modifier.weight(1f).padding(start = 14.dp),
            )
            if (items.isNotEmpty()) {
                val allSelected = selection.size == items.size
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
            }
        }

        if (items.isNotEmpty()) {
            GlassSurface(
                haze,
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 14.dp)
                    .height(66.dp),
                shape = CircleShape,
            ) {
                Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    BarAction(
                        Icons.Outlined.Restore,
                        if (selection.isEmpty()) "Restore all" else "Restore",
                        true,
                        { request(vm.restoreRequest(targets)) },
                    )
                    BarAction(
                        Icons.Outlined.DeleteForever,
                        if (selection.isEmpty()) "Delete all" else "Delete",
                        true,
                        { request(vm.deleteForeverRequest(targets)) },
                        tint = Color(0xFFFF453A),
                    )
                }
            }
        }
    }
}
