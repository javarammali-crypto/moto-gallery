package com.javaram.gallery.ui.album

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javaram.gallery.GalleryViewModel
import com.javaram.gallery.ui.components.GlassIconButton
import com.javaram.gallery.ui.components.GlassSurface
import com.javaram.gallery.ui.components.GroupMode
import com.javaram.gallery.ui.components.MediaGrid
import com.javaram.gallery.ui.components.ProgressiveGlass
import com.javaram.gallery.ui.components.pressable
import com.javaram.gallery.ui.components.toast
import com.javaram.gallery.ui.home.LargeHeader
import com.javaram.gallery.ui.theme.GalleryTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

/** Tap a photo to make it the album's cover. */
@Composable
fun AlbumCoverScreen(vm: GalleryViewModel, key: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val haze = remember { HazeState() }
    val all by vm.all.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val columns by vm.columns.collectAsStateWithLifecycle()
    val album = albums.firstOrNull { it.key == key }
    val items = remember(all, key) { all.filter { it.bucketId.toString() == key } }
    val c = GalleryTheme.colors
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp

    Box(Modifier.fillMaxSize().background(c.background)) {
        Box(Modifier.fillMaxSize().haze(haze)) {
            MediaGrid(
                items = items,
                columns = columns,
                group = GroupMode.Day,
                state = rememberLazyGridState(),
                selection = setOfNotNull(album?.cover?.id),
                selectionMode = false,
                onSelectionChange = {},
                onEnterSelection = {},
                onOpen = { m ->
                    vm.setAlbumCover(key, m.id)
                    toast(context, "Cover changed")
                    onBack()
                },
                contentPadding = PaddingValues(bottom = bottom),
                onColumnsChange = vm::setColumns,
                header = { LargeHeader(album?.name ?: "Album", "Tap a photo to use it as the cover") },
            )
        }
        ProgressiveGlass(haze, Modifier.fillMaxWidth().height(110.dp)) {}
        Row(
            Modifier.statusBarsPadding().fillMaxWidth().height(60.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(haze, Icons.Filled.Close, "Cancel", size = 42.dp, onClick = onBack)
            Text(
                "Choose cover",
                style = MaterialTheme.typography.titleMedium,
                color = c.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 14.dp),
            )
            GlassSurface(
                haze,
                Modifier.height(40.dp).pressable {
                    vm.setAlbumCover(key, null)
                    toast(context, "Cover set to the newest photo")
                    onBack()
                },
                shape = CircleShape,
            ) {
                Text(
                    "Newest",
                    style = MaterialTheme.typography.titleSmall,
                    color = c.content,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}
