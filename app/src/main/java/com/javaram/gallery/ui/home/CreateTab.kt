package com.javaram.gallery.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.MovieFilter
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.javaram.gallery.ui.components.pressable
import com.javaram.gallery.ui.components.toast

private data class CreateItem(val title: String, val subtitle: String, val icon: ImageVector, val colors: List<Color>)

private val createItems = listOf(
    CreateItem("Collage", "Combine photos into one", Icons.Outlined.Dashboard, listOf(Color(0xFFFFA24C), Color(0xFFFF4F2E))),
    CreateItem("Video editor", "Trim and cut clips", Icons.Outlined.MovieFilter, listOf(Color(0xFF7B61FF), Color(0xFF4B2FD6))),
    CreateItem("Slideshow", "Play an album as a show", Icons.Outlined.Slideshow, listOf(Color(0xFF00C2FF), Color(0xFF0077E6))),
    CreateItem("Memories", "Trips and on this day", Icons.Outlined.AutoAwesome, listOf(Color(0xFFFF6B8B), Color(0xFFD9365E))),
    CreateItem("Cleanup", "Duplicates and blurry shots", Icons.Outlined.CleaningServices, listOf(Color(0xFF34C3A0), Color(0xFF14937A))),
    CreateItem("Photo editor", "Crop, adjust, filters", Icons.Outlined.Tune, listOf(Color(0xFF5C5C66), Color(0xFF2E2E34))),
)

@Composable
fun CreateTab(state: LazyGridState) {
    val context = LocalContext.current
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 110.dp
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = state,
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            LargeHeader("Create", "Make something from your photos")
        }
        items(createItems) { item ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(Brush.linearGradient(item.colors), RoundedCornerShape(24.dp))
                    .pressable { toast(context, "${item.title} is coming in a later update") }
                    .padding(16.dp),
            ) {
                Icon(item.icon, null, tint = Color.White, modifier = Modifier.size(30.dp))
                Column(Modifier.align(Alignment.BottomStart)) {
                    Text(item.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(item.subtitle, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                }
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
