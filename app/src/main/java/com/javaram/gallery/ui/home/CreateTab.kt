package com.javaram.gallery.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MovieFilter
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.javaram.gallery.data.thumb
import com.javaram.gallery.search.Memory
import com.javaram.gallery.ui.components.pressable
import com.javaram.gallery.ui.components.toast
import com.javaram.gallery.ui.theme.Accent
import com.javaram.gallery.ui.theme.GalleryTheme

private data class CreateItem(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val colors: List<Color>,
    val soon: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun CreateTab(
    state: LazyGridState,
    memories: List<Memory> = emptyList(),
    onMemory: (Memory) -> Unit = {},
    onCollage: () -> Unit = {},
    onEditor: () -> Unit = {},
    onCleanup: () -> Unit = {},
    onSafe: () -> Unit = {},
    onSlideshow: () -> Unit = {},
) {
    val context = LocalContext.current
    val c = GalleryTheme.colors
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 110.dp
    val tools = listOf(
        CreateItem("Collage", "Combine up to 9 photos", Icons.Outlined.Dashboard, listOf(Color(0xFFFFA24C), Color(0xFFFF4F2E)), onClick = onCollage),
        CreateItem("Photo editor", "Crop, adjust, filters, background", Icons.Outlined.Tune, listOf(Color(0xFF5C5C66), Color(0xFF2E2E34)), onClick = onEditor),
        CreateItem("Cleanup", "Duplicates, blurry, large files", Icons.Outlined.CleaningServices, listOf(Color(0xFF34C3A0), Color(0xFF14937A)), onClick = onCleanup),
        CreateItem("Private safe", "Hide photos behind your lock", Icons.Outlined.Lock, listOf(Color(0xFF7B61FF), Color(0xFF4B2FD6)), onClick = onSafe),
        CreateItem("Slideshow", "Play your favourites", Icons.Outlined.Slideshow, listOf(Color(0xFF00C2FF), Color(0xFF0077E6)), onClick = onSlideshow),
        CreateItem("Video editor", "Trim and cut clips", Icons.Outlined.MovieFilter, listOf(Color(0xFFFF6B8B), Color(0xFFD9365E)), soon = true) {
            toast(context, "Video editor is coming in a later update")
        },
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = state,
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            LargeHeader("Create", "Memories, collages and tools")
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text(
                    "Memories", style = MaterialTheme.typography.headlineSmall, color = c.content,
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                )
                if (memories.isEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = Accent, modifier = Modifier.size(22.dp))
                        Text(
                            "Memories appear here — trips, people and on this day — as your photos are analysed.",
                            style = MaterialTheme.typography.bodySmall, color = c.secondary,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                        items(memories, key = { it.key }) { m ->
                            Box(
                                Modifier
                                    .size(width = 170.dp, height = 240.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(c.card)
                                    .pressable { onMemory(m) }
                            ) {
                                AsyncImage(
                                    model = m.cover.thumb(), contentDescription = null,
                                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                                )
                                Box(
                                    Modifier.fillMaxSize().background(
                                        Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))
                                    )
                                )
                                Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                                    Text(
                                        m.title, color = Color.White, style = MaterialTheme.typography.titleMedium,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(m.subtitle, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "Tools", style = MaterialTheme.typography.headlineSmall, color = c.content,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        }
        items(tools) { item ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(Brush.linearGradient(item.colors), RoundedCornerShape(24.dp))
                    .pressable(onClick = item.onClick)
                    .padding(16.dp),
            ) {
                Icon(item.icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
                Column(Modifier.align(Alignment.BottomStart)) {
                    Text(item.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(item.subtitle, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                }
                if (item.soon) {
                    Text(
                        "Soon",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}
