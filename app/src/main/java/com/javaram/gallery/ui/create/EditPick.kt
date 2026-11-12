package com.javaram.gallery.ui.create

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javaram.gallery.GalleryViewModel
import com.javaram.gallery.data.Media
import com.javaram.gallery.ui.components.GlassIconButton
import com.javaram.gallery.ui.components.GroupMode
import com.javaram.gallery.ui.components.MediaGrid
import com.javaram.gallery.ui.components.ProgressiveGlass
import com.javaram.gallery.ui.editor.EditorOverlay
import com.javaram.gallery.ui.home.LargeHeader
import com.javaram.gallery.ui.theme.GalleryTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

/** Pick a photo, then open it in the editor. */
@Composable
fun EditPickScreen(vm: GalleryViewModel, onBack: () -> Unit) {
    val haze = remember { HazeState() }
    val all by vm.all.collectAsStateWithLifecycle()
    val columns by vm.columns.collectAsStateWithLifecycle()
    val photos = remember(all) { all.filter { !it.isVideo } }
    var editing by remember { mutableStateOf<Media?>(null) }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 30.dp
    Box(Modifier.fillMaxSize().background(GalleryTheme.colors.background)) {
        Box(Modifier.fillMaxSize().haze(haze)) {
            MediaGrid(
                items = photos,
                columns = columns,
                group = GroupMode.Day,
                state = rememberLazyGridState(),
                selection = emptySet(),
                selectionMode = false,
                onSelectionChange = {},
                onEnterSelection = {},
                onOpen = { editing = it },
                contentPadding = PaddingValues(bottom = bottom),
                onColumnsChange = vm::setColumns,
                header = { LargeHeader("Edit a photo", "Tap a photo to open it in the editor") },
            )
        }
        ProgressiveGlass(haze, Modifier.fillMaxWidth().height(110.dp)) {}
        Row(Modifier.statusBarsPadding().padding(12.dp)) {
            GlassIconButton(haze, Icons.AutoMirrored.Outlined.ArrowBack, "Back", size = 42.dp, onClick = onBack)
        }
        val e = editing
        if (e != null) EditorOverlay(media = e, vm = vm, onClose = { editing = null })
    }
}
