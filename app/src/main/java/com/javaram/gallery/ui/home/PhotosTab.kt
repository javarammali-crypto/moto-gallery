package com.javaram.gallery.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.javaram.gallery.MediaFilter
import com.javaram.gallery.data.Media
import com.javaram.gallery.ui.components.GridHandle
import com.javaram.gallery.ui.components.GroupMode
import com.javaram.gallery.ui.components.MediaGrid
import com.javaram.gallery.ui.theme.Accent
import com.javaram.gallery.ui.theme.GalleryTheme
import com.javaram.gallery.util.countLabel

@Composable
fun PhotosTab(
    items: List<Media>,
    allCount: Int,
    videoCount: Int,
    filter: MediaFilter,
    loaded: Boolean,
    columns: Int,
    state: LazyGridState,
    handle: GridHandle,
    hiddenId: Long?,
    selection: Set<Long>,
    selectionMode: Boolean,
    onSelectionChange: (Set<Long>) -> Unit,
    onEnterSelection: () -> Unit,
    onOpen: (Media) -> Unit,
    onColumnsChange: (Int) -> Unit,
    showBanner: Boolean,
    onAllowManage: () -> Unit,
    onDismissBanner: () -> Unit,
) {
    val c = GalleryTheme.colors
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 110.dp

    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Accent, strokeWidth = 2.5.dp, modifier = Modifier.size(32.dp))
        }
        return
    }

    val subtitle = when (filter) {
        MediaFilter.All -> "${countLabel(allCount - videoCount)} photos · ${countLabel(videoCount)} videos"
        else -> "${filter.label} · ${countLabel(items.size)}"
    }

    if (items.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            LargeHeader("Photos", subtitle)
            Box(Modifier.fillMaxSize().padding(bottom = bottom), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.PhotoLibrary, null, tint = c.secondary, modifier = Modifier.size(56.dp))
                    Text(
                        if (filter == MediaFilter.All) "No photos yet" else "Nothing matches this filter",
                        style = MaterialTheme.typography.titleMedium,
                        color = c.content,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
        return
    }

    MediaGrid(
        items = items,
        columns = columns,
        group = if (columns >= 6) GroupMode.Month else GroupMode.Day,
        state = state,
        handle = handle,
        hiddenId = hiddenId,
        selection = selection,
        selectionMode = selectionMode,
        onSelectionChange = onSelectionChange,
        onEnterSelection = onEnterSelection,
        onOpen = onOpen,
        contentPadding = PaddingValues(bottom = bottom),
        onColumnsChange = onColumnsChange,
        header = {
            LargeHeader("Photos", subtitle) {
                if (showBanner) ManageBanner(onAllow = onAllowManage, onDismiss = onDismissBanner)
            }
        },
    )
}
