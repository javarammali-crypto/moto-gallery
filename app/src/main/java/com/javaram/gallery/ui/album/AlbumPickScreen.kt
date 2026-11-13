package com.javaram.gallery.ui.album

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javaram.gallery.GalleryViewModel
import com.javaram.gallery.data.AlbumOps
import com.javaram.gallery.ui.components.GlassIconButton
import com.javaram.gallery.ui.components.GroupMode
import com.javaram.gallery.ui.components.MediaGrid
import com.javaram.gallery.ui.components.ProgressiveGlass
import com.javaram.gallery.ui.components.pressable
import com.javaram.gallery.ui.home.LargeHeader
import com.javaram.gallery.ui.theme.Accent
import com.javaram.gallery.ui.theme.GalleryTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

/** Choose the photos that go into a newly named album. */
@Composable
fun AlbumPickScreen(vm: GalleryViewModel, name: String, onBack: () -> Unit) {
    val haze = remember { HazeState() }
    val all by vm.all.collectAsStateWithLifecycle()
    val columns by vm.columns.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    val flow = rememberAddToAlbum()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 110.dp
    val c = GalleryTheme.colors

    Box(Modifier.fillMaxSize().background(c.background)) {
        Box(Modifier.fillMaxSize().haze(haze)) {
            MediaGrid(
                items = all,
                columns = columns,
                group = GroupMode.Day,
                state = rememberLazyGridState(),
                selection = selection,
                selectionMode = true,
                onSelectionChange = { selection = it },
                onEnterSelection = {},
                onOpen = { m -> selection = if (m.id in selection) selection - m.id else selection + m.id },
                contentPadding = PaddingValues(bottom = bottom),
                onColumnsChange = vm::setColumns,
                header = { LargeHeader(name, "Choose photos and videos for this album") },
            )
        }
        ProgressiveGlass(haze, Modifier.fillMaxWidth().height(110.dp)) {}
        Row(
            Modifier.statusBarsPadding().fillMaxWidth().height(60.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(haze, Icons.Filled.Close, "Cancel", size = 42.dp, onClick = onBack)
            Text(
                if (selection.isEmpty()) "Select items" else "${selection.size} selected",
                style = MaterialTheme.typography.titleMedium,
                color = c.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 14.dp),
            )
        }

        AnimatedVisibility(
            visible = selection.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            Box(
                Modifier
                    .height(54.dp)
                    .background(Accent, CircleShape)
                    .pressable {
                        val items = all.filter { it.id in selection }
                        flow.choose(items, AlbumOps.newAlbumPath(name), name)
                    }
                    .padding(horizontal = 34.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Add to $name", style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
                }
            }
        }

        AddToAlbumFlow(flow, vm, haze, onDone = onBack)
    }
}
