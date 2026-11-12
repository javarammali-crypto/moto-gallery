package com.javaram.gallery.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.javaram.gallery.data.Album
import com.javaram.gallery.data.AlbumKeys
import com.javaram.gallery.data.Media
import com.javaram.gallery.data.thumb
import com.javaram.gallery.ui.theme.GalleryTheme
import com.javaram.gallery.util.countLabel

private data class QuickTile(
    val key: String,
    val name: String,
    val icon: ImageVector,
    val cover: Media?,
    val count: Int?,
    val colors: List<Color>,
)

@Composable
fun AlbumsTab(
    albums: List<Album>,
    all: List<Media>,
    state: LazyGridState,
    onOpenAlbum: (String) -> Unit,
    onOpenTrash: () -> Unit,
    onAlbumMenu: (Album) -> Unit,
    people: List<com.javaram.gallery.search.Person> = emptyList(),
    onOpenPeople: () -> Unit = {},
    onOpenPerson: (Long) -> Unit = {},
) {
    val c = GalleryTheme.colors
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 110.dp
    val quick = remember(all, albums) {
        val favs = all.filter { it.isFavorite }
        val vids = all.filter { it.isVideo }
        val shots = albums.firstOrNull { it.name.equals("Screenshots", ignoreCase = true) }
        buildList {
            add(QuickTile(AlbumKeys.FAVORITES, "Favourites", Icons.Outlined.FavoriteBorder, favs.firstOrNull(), favs.size, listOf(Color(0xFFFF6B8B), Color(0xFFFF3D5A))))
            add(QuickTile(AlbumKeys.VIDEOS, "Videos", Icons.Outlined.Videocam, vids.firstOrNull(), vids.size, listOf(Color(0xFF5BA8FF), Color(0xFF3D6BFF))))
            if (shots != null) add(QuickTile(shots.key, "Screenshots", Icons.Outlined.Screenshot, shots.cover, shots.count, listOf(Color(0xFF34C3A0), Color(0xFF14937A))))
            add(QuickTile("trash", "Recently deleted", Icons.Outlined.AutoDelete, null, null, listOf(Color(0xFF9B9BA5), Color(0xFF63636D))))
        }
    }
    val myAlbums = remember(albums) { albums.filterNot { it.name.equals("Screenshots", ignoreCase = true) } }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = state,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
            LargeHeader("Albums", "${albums.size} albums")
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "people") {
            PeopleRow(people, all, onOpenPeople, onOpenPerson)
        }
        items(quick, key = { "q-" + it.key }) { q ->
            AlbumTile(
                name = q.name,
                count = q.count,
                cover = q.cover,
                icon = q.icon,
                fallbackColors = q.colors,
                pinned = false,
                onClick = { if (q.key == "trash") onOpenTrash() else onOpenAlbum(q.key) },
                onLongClick = null,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "mine") {
            Text(
                "My albums",
                style = MaterialTheme.typography.headlineSmall,
                color = c.content,
                modifier = Modifier.padding(top = 10.dp, start = 4.dp),
            )
        }
        items(myAlbums, key = { "a-" + it.key }) { a ->
            AlbumTile(
                name = a.name,
                count = a.count,
                cover = a.cover,
                icon = if (a.cover?.relativePath?.startsWith("DCIM/Camera", true) == true) Icons.Outlined.CameraAlt else null,
                fallbackColors = listOf(Color(0xFFFFA24C), Color(0xFFFF4F2E)),
                pinned = a.pinned,
                onClick = { onOpenAlbum(a.key) },
                onLongClick = { onAlbumMenu(a) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun AlbumTile(
    name: String,
    count: Int?,
    cover: Media?,
    icon: ImageVector?,
    fallbackColors: List<Color>,
    pinned: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = GalleryTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.94f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "tile",
    )
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(fallbackColors)),
        ) {
            if (cover != null) {
                AsyncImage(
                    model = cover.thumb(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (icon != null) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(34.dp))
            }
            if (cover != null && icon != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(7.dp)
                        .size(26.dp)
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }
            if (pinned) {
                Icon(
                    Icons.Filled.PushPin, null, tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp)
                        .size(22.dp)
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50))
                        .padding(4.dp),
                )
            }
        }
        Row(Modifier.padding(top = 7.dp, start = 2.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                color = c.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (count != null) {
            Text(
                countLabel(count),
                style = MaterialTheme.typography.bodySmall,
                color = c.secondary,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

@Composable
fun PeopleRow(
    people: List<com.javaram.gallery.search.Person>,
    all: List<Media>,
    onOpenPeople: () -> Unit,
    onOpenPerson: (Long) -> Unit,
) {
    val c = GalleryTheme.colors
    val shown = remember(people) { com.javaram.gallery.ui.people.visiblePeople(people, showHidden = false).take(12) }
    val byId = remember(all) { all.associateBy { it.id } }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("People", style = MaterialTheme.typography.headlineSmall, color = c.content, modifier = Modifier.weight(1f))
            Text(
                "See all",
                style = MaterialTheme.typography.titleSmall,
                color = com.javaram.gallery.ui.theme.Accent,
                modifier = Modifier.clip(RoundedCornerShape(50)).combinedClickable(onClick = onOpenPeople).padding(8.dp),
            )
        }
        if (shown.isEmpty()) {
            Text(
                "Faces are being grouped in the background. People will show up here.",
                style = MaterialTheme.typography.bodySmall,
                color = c.secondary,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        } else {
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                items(shown.size, key = { shown[it].id }) { i ->
                    val p = shown[i]
                    Column(
                        Modifier.width(76.dp).combinedClickable(onClick = { onOpenPerson(p.id) }),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        com.javaram.gallery.ui.people.FaceAvatar(p, byId, 72.dp)
                        Text(
                            p.name ?: "Add name",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (p.name == null) c.secondary else c.content,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
