package com.javaram.gallery.ui.cleanup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.BurstMode
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.VideoFile
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.javaram.gallery.GalleryViewModel
import com.javaram.gallery.data.Media
import com.javaram.gallery.data.thumb
import com.javaram.gallery.search.CleanupKind
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
import com.javaram.gallery.ui.components.toast
import com.javaram.gallery.ui.home.LargeHeader
import com.javaram.gallery.ui.theme.Accent
import com.javaram.gallery.ui.theme.GalleryTheme
import com.javaram.gallery.util.countLabel
import com.javaram.gallery.util.fileSize
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

private fun icon(k: CleanupKind): ImageVector = when (k) {
    CleanupKind.Duplicates -> Icons.Outlined.ContentCopy
    CleanupKind.Similar -> Icons.Outlined.BurstMode
    CleanupKind.Blurry -> Icons.Outlined.BlurOn
    CleanupKind.LargeVideos -> Icons.Outlined.VideoFile
    CleanupKind.Screenshots -> Icons.Outlined.Screenshot
}

private fun colors(k: CleanupKind): List<Color> = when (k) {
    CleanupKind.Duplicates -> listOf(Color(0xFFFFA24C), Color(0xFFFF4F2E))
    CleanupKind.Similar -> listOf(Color(0xFF7B61FF), Color(0xFF4B2FD6))
    CleanupKind.Blurry -> listOf(Color(0xFF00C2FF), Color(0xFF0077E6))
    CleanupKind.LargeVideos -> listOf(Color(0xFFFF6B8B), Color(0xFFD9365E))
    CleanupKind.Screenshots -> listOf(Color(0xFF34C3A0), Color(0xFF14937A))
}

@Composable
fun CleanupScreen(vm: GalleryViewModel, onBack: () -> Unit, onOpen: (CleanupKind) -> Unit) {
    val c = GalleryTheme.colors
    val haze = remember { HazeState() }
    val report by vm.cleanup.collectAsStateWithLifecycle()
    val progress by vm.qualityProgress.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.watchCleanup() }
    val total = CleanupKind.entries.sumOf { report.bytes(it) }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 30.dp

    Box(Modifier.fillMaxSize().background(c.background)) {
        LazyColumn(Modifier.fillMaxSize().haze(haze), contentPadding = PaddingValues(bottom = bottom)) {
            item {
                LargeHeader("Cleanup", if (total > 0) "Up to ${fileSize(total)} can be freed" else "Free up space")
            }
            if (!progress.complete && progress.total > 0) {
                item {
                    Row(
                        Modifier.padding(16.dp, 6.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = Accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Checking photos… ${countLabel(progress.done)} of ${countLabel(progress.total)}. Results fill in as it goes.",
                            style = MaterialTheme.typography.bodySmall, color = c.secondary,
                        )
                    }
                }
            }
            items(CleanupKind.entries) { k ->
                val n = report.count(k)
                val sample = (report.groups(k).flatMap { it.others } + report.list(k)).take(3)
                Row(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(c.card)
                        .pressable(enabled = n > 0) { onOpen(k) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(48.dp).background(Brush.linearGradient(colors(k)), RoundedCornerShape(15.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon(k), null, tint = Color.White) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(k.title, style = MaterialTheme.typography.titleSmall, color = c.content)
                        Text(
                            if (n == 0) k.subtitle else "${countLabel(n)} items · ${fileSize(report.bytes(k))}",
                            style = MaterialTheme.typography.bodySmall, color = c.secondary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        sample.forEach { m ->
                            AsyncImage(
                                model = m.thumb(), contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).border(2.dp, c.card, RoundedCornerShape(10.dp)),
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.statusBarsPadding().padding(12.dp)) {
            GlassIconButton(haze, Icons.AutoMirrored.Outlined.ArrowBack, "Back", size = 42.dp, onClick = onBack)
        }
    }
}

@Composable
fun CleanupReviewScreen(vm: GalleryViewModel, kind: CleanupKind, onBack: () -> Unit) {
    val c = GalleryTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val haze = remember { HazeState() }
    val report by vm.cleanup.collectAsStateWithLifecycle()
    val columns by vm.columns.collectAsStateWithLifecycle()
    val canManage by vm.canManageMedia.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.watchCleanup() }
    val groups = remember(report, kind) { report.groups(kind) }
    val list = remember(report, kind) { report.list(kind) }
    val isGroups = kind == CleanupKind.Duplicates || kind == CleanupKind.Similar
    var initialised by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    // Pre-select the suggested extras (not the best shot) the first time results arrive.
    LaunchedEffect(groups, list) {
        if (!initialised && (groups.isNotEmpty() || list.isNotEmpty())) {
            selection = if (isGroups) groups.flatMap { g -> g.others.map { it.id } }.toSet()
            else if (kind == CleanupKind.Screenshots) list.map { it.id }.toSet() else emptySet()
            initialised = true
        }
    }
    val everything = remember(groups, list) { groups.flatMap { listOf(it.keep) + it.others } + list }
    val chosen = remember(selection, everything) { everything.filter { it.id in selection } }
    val request = rememberMediaRequest { ok ->
        if (ok) { toast(context, "Moved to Recently deleted"); selection = emptySet() }
        vm.scheduleRefresh(0)
    }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 110.dp

    Box(Modifier.fillMaxSize().background(c.background)) {
        Box(Modifier.fillMaxSize().haze(haze)) {
            if (isGroups) {
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = bottom), modifier = Modifier.fillMaxSize()) {
                    item { LargeHeader(kind.title, "${groups.size} sets · best shot kept, extras selected") }
                    items(groups, key = { it.keep.id }) { g ->
                        Column(Modifier.padding(vertical = 8.dp)) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(listOf(g.keep) + g.others, key = { it.id }) { m ->
                                    ReviewTile(
                                        media = m,
                                        selected = m.id in selection,
                                        best = m.id == g.keep.id,
                                        onToggle = { selection = if (m.id in selection) selection - m.id else selection + m.id },
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                MediaGrid(
                    items = list,
                    columns = columns,
                    group = GroupMode.None,
                    state = gridState,
                    handle = remember { GridHandle() },
                    selection = selection,
                    selectionMode = true,
                    onSelectionChange = { selection = it },
                    onEnterSelection = {},
                    onOpen = {},
                    contentPadding = PaddingValues(bottom = bottom),
                    onColumnsChange = vm::setColumns,
                    badge = if (kind == CleanupKind.LargeVideos) { m -> fileSize(m.size) } else null,
                    header = { LargeHeader(kind.title, "${countLabel(list.size)} items · ${kind.subtitle}") },
                )
            }
            if (groups.isEmpty() && list.isEmpty()) {
                Text(
                    "All clean here.",
                    style = MaterialTheme.typography.titleMedium, color = c.secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        ProgressiveGlass(haze, Modifier.fillMaxWidth().height(110.dp), visibleAlpha = 1f) {}
        Row(
            Modifier.statusBarsPadding().fillMaxWidth().height(60.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(haze, Icons.AutoMirrored.Outlined.ArrowBack, "Back", size = 42.dp, onClick = onBack)
            Spacer(Modifier.weight(1f))
            val all = everything.isNotEmpty() && selection.size == everything.size
            GlassSurface(
                haze,
                Modifier.height(42.dp).pressable {
                    selection = if (selection.isNotEmpty()) emptySet() else everything.map { it.id }.toSet()
                },
                shape = CircleShape,
            ) {
                Text(
                    if (selection.isNotEmpty()) "Clear" else "Select all",
                    style = MaterialTheme.typography.titleSmall, color = c.content,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = selection.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            GlassSurface(haze, Modifier.height(60.dp).pressable {
                val targets = chosen
                val doIt = { request(vm.trashRequest(targets)) }
                if (canManage) {
                    confirm = ConfirmRequest(
                        "Delete ${targets.size} items?",
                        "${fileSize(targets.sumOf { it.size })} will be freed. They stay in Recently deleted for 30 days.",
                        "Delete",
                    ) { doIt() }
                } else doIt()
            }, shape = CircleShape) {
                Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.DeleteOutline, null, tint = Color(0xFFFF453A))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Delete ${countLabel(chosen.size)} · ${fileSize(chosen.sumOf { it.size })}",
                        style = MaterialTheme.typography.titleSmall, color = Color(0xFFFF453A),
                    )
                }
            }
        }
        GlassDialog(request = confirm, state = haze, onDismiss = { confirm = null })
    }
}

@Composable
private fun ReviewTile(media: Media, selected: Boolean, best: Boolean, onToggle: () -> Unit) {
    Box(Modifier.size(width = 120.dp, height = 150.dp).clip(RoundedCornerShape(16.dp)).pressable(onClick = onToggle)) {
        AsyncImage(model = media.thumb(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (selected) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        if (best) {
            Text(
                "Best",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(Accent, CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle, null, tint = Color(0xFFFF453A),
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(Color.White, CircleShape),
            )
        } else {
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).border(1.5.dp, Color.White, CircleShape))
        }
    }
}
