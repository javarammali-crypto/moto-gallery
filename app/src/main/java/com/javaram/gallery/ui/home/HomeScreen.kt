package com.javaram.gallery.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.ViewComfy
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javaram.gallery.AlbumSort
import com.javaram.gallery.GalleryViewModel
import com.javaram.gallery.MediaFilter
import com.javaram.gallery.data.Album
import com.javaram.gallery.data.Media
import com.javaram.gallery.ui.components.GlassActionSheet
import com.javaram.gallery.ui.components.GlassIconButton
import com.javaram.gallery.ui.components.GlassSurface
import com.javaram.gallery.ui.components.ProgressiveGlass
import com.javaram.gallery.ui.components.SheetOption
import com.javaram.gallery.ui.components.pressable
import com.javaram.gallery.ui.components.rememberMediaRequest
import com.javaram.gallery.ui.components.requestManageMedia
import com.javaram.gallery.ui.components.shareMedia
import com.javaram.gallery.ui.theme.GalleryTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

enum class HomeTab(val label: String, val icon: ImageVector) {
    Photos("Photos", Icons.Outlined.Image),
    Albums("Albums", Icons.Outlined.PhotoLibrary),
    Create("Create", Icons.Outlined.AutoAwesome),
}

private sealed interface HomeSheet {
    data object Filter : HomeSheet
    data object Layout : HomeSheet
    data object More : HomeSheet
    data object Sort : HomeSheet
    data class AlbumMenu(val album: Album) : HomeSheet
}

@Composable
fun HomeScreen(
    vm: GalleryViewModel,
    onOpen: (List<Media>, Media) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenTrash: () -> Unit,
) {
    val context = LocalContext.current
    val haze = remember { HazeState() }
    var tab by rememberSaveable { mutableStateOf(HomeTab.Photos) }
    val all by vm.all.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val columns by vm.columns.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val albumSort by vm.albumSort.collectAsStateWithLifecycle()
    val canManage by vm.canManageMedia.collectAsStateWithLifecycle()
    val bannerDismissed by vm.bannerDismissed.collectAsStateWithLifecycle()

    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var sheet by remember { mutableStateOf<HomeSheet?>(null) }

    val photosState = rememberLazyGridState()
    val albumsState = rememberLazyGridState()
    val createState = rememberLazyGridState()

    val photos = remember(all, filter) {
        when (filter) {
            MediaFilter.All -> all
            MediaFilter.Photos -> all.filter { !it.isVideo }
            MediaFilter.Videos -> all.filter { it.isVideo }
            MediaFilter.Favorites -> all.filter { it.isFavorite }
        }
    }
    val videoCount = remember(all) { all.count { it.isVideo } }

    fun exitSelection() {
        selectionMode = false
        selection = emptySet()
    }

    LaunchedEffect(tab) { if (tab != HomeTab.Photos) exitSelection() }
    BackHandler(enabled = selectionMode) { exitSelection() }
    BackHandler(enabled = sheet != null) { sheet = null }

    val request = rememberMediaRequest { ok ->
        if (ok) exitSelection()
        vm.scheduleRefresh(0)
    }
    val selectedItems = remember(selection, all) { all.filter { it.id in selection } }

    val activeState: LazyGridState = when (tab) {
        HomeTab.Photos -> photosState
        HomeTab.Albums -> albumsState
        HomeTab.Create -> createState
    }
    val collapsed by remember(activeState) {
        derivedStateOf { activeState.firstVisibleItemIndex > 0 || activeState.firstVisibleItemScrollOffset > 160 }
    }

    Box(Modifier.fillMaxSize().background(GalleryTheme.colors.background)) {
        // Everything inside here is the "source" that glass surfaces blur.
        Box(Modifier.fillMaxSize().haze(haze)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    (fadeIn(tween(240)) + scaleIn(tween(280), initialScale = 0.985f)) togetherWith fadeOut(tween(140))
                },
                label = "tabs",
            ) { t ->
                when (t) {
                    HomeTab.Photos -> PhotosTab(
                        items = photos,
                        allCount = all.size,
                        videoCount = videoCount,
                        filter = filter,
                        loaded = loaded,
                        columns = columns,
                        state = photosState,
                        selection = selection,
                        selectionMode = selectionMode,
                        onSelectionChange = { selection = it },
                        onEnterSelection = { selectionMode = true },
                        onOpen = { m -> onOpen(photos, m) },
                        onColumnsChange = vm::setColumns,
                        showBanner = !canManage && !bannerDismissed,
                        onAllowManage = { requestManageMedia(context) },
                        onDismissBanner = vm::dismissManageBanner,
                    )
                    HomeTab.Albums -> AlbumsTab(
                        albums = albums,
                        all = all,
                        state = albumsState,
                        onOpenAlbum = onOpenAlbum,
                        onOpenTrash = onOpenTrash,
                        onAlbumMenu = { sheet = HomeSheet.AlbumMenu(it) },
                    )
                    HomeTab.Create -> CreateTab(state = createState)
                }
            }
        }

        // ---- Top bar ----
        val topAlpha by animateFloatAsState(if (collapsed || selectionMode) 1f else 0f, tween(220), label = "top")
        ProgressiveGlass(
            state = haze,
            visibleAlpha = topAlpha,
            modifier = Modifier.fillMaxWidth().height(110.dp),
        ) {}
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
                    color = GalleryTheme.colors.content,
                    modifier = Modifier.weight(1f).padding(start = 14.dp),
                )
                val allSelected = photos.isNotEmpty() && selection.size == photos.size
                GlassSurface(
                    haze,
                    Modifier.height(42.dp).pressable {
                        selection = if (allSelected) emptySet() else photos.map { it.id }.toSet()
                    },
                    shape = CircleShape,
                ) {
                    Text(
                        if (allSelected) "Deselect all" else "Select all",
                        style = MaterialTheme.typography.titleSmall,
                        color = GalleryTheme.colors.content,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else {
                Box(Modifier.weight(1f)) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = collapsed,
                        enter = fadeIn() + slideInVertically { it / 2 },
                        exit = fadeOut() + slideOutVertically { it / 2 },
                    ) {
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = GalleryTheme.colors.content,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                if (tab == HomeTab.Photos) {
                    GlassIconButton(haze, Icons.Outlined.Checklist, "Select", size = 42.dp) { selectionMode = true }
                    Spacer(Modifier.width(10.dp))
                }
                if (tab != HomeTab.Create) {
                    GlassIconButton(haze, Icons.Outlined.MoreVert, "More", size = 42.dp) { sheet = HomeSheet.More }
                }
            }
        }

        // ---- Bottom: tab bar or selection actions ----
        ProgressiveGlass(
            state = haze,
            fadeDown = false,
            visibleAlpha = 0.85f,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(130.dp),
        ) {}
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
                label = "bottom",
            ) { sel ->
                if (sel) {
                    val allFav = selectedItems.isNotEmpty() && selectedItems.all { it.isFavorite }
                    SelectionBar(
                        haze = haze,
                        enabled = selection.isNotEmpty(),
                        allFavorite = allFav,
                        onShare = { shareMedia(context, selectedItems) },
                        onFavorite = { request(vm.favoriteRequest(selectedItems, !allFav)) },
                        onDelete = { request(vm.trashRequest(selectedItems)) },
                    )
                } else {
                    TabBar(
                        haze = haze,
                        tab = tab,
                        onTab = { tab = it },
                        onLayout = { sheet = HomeSheet.Layout },
                        onSecondary = {
                            sheet = if (tab == HomeTab.Albums) HomeSheet.Sort else HomeSheet.Filter
                        },
                        filterActive = filter != MediaFilter.All,
                    )
                }
            }
        }

        // ---- Sheets ----
        val s = sheet
        val options: List<SheetOption> = when (s) {
            HomeSheet.Filter -> MediaFilter.entries.map { f ->
                SheetOption(f.label, selected = f == filter) { vm.filter.value = f }
            }
            HomeSheet.Layout -> listOf(
                Triple(3, "Comfortable", Icons.Outlined.ViewComfy),
                Triple(4, "Standard", Icons.Outlined.GridView),
                Triple(5, "Compact", Icons.Outlined.ViewModule),
                Triple(6, "By month", Icons.Outlined.ViewModule),
            ).map { (n, label, icon) ->
                SheetOption(label, icon = icon, selected = columns == n, subtitle = "$n per row") { vm.setColumns(n) }
            }
            HomeSheet.Sort -> AlbumSort.entries.map { so ->
                SheetOption(so.label, selected = so == albumSort) { vm.setAlbumSort(so) }
            }
            HomeSheet.More -> buildList {
                if (tab == HomeTab.Photos) add(SheetOption("Select", Icons.Outlined.Checklist) { selectionMode = true })
                add(SheetOption("Recently deleted", Icons.Outlined.AutoDelete) { onOpenTrash() })
                if (!canManage) {
                    add(
                        SheetOption(
                            "Skip confirmation pop-ups",
                            Icons.Outlined.VerifiedUser,
                            subtitle = "Allow Gallery to manage media",
                        ) { requestManageMedia(context) }
                    )
                }
                add(SheetOption("Refresh", Icons.Outlined.Refresh) { vm.scheduleRefresh(0) })
            }
            is HomeSheet.AlbumMenu -> listOf(
                SheetOption(if (s.album.pinned) "Unpin from top" else "Pin to top", Icons.Outlined.PushPin) {
                    vm.togglePin(s.album.key)
                },
                SheetOption("Open", Icons.Outlined.PhotoLibrary) { onOpenAlbum(s.album.key) },
            )
            null -> emptyList()
        }
        val title = when (s) {
            HomeSheet.Filter -> "Show"
            HomeSheet.Layout -> "Layout"
            HomeSheet.Sort -> "Sort albums by"
            HomeSheet.More -> null
            is HomeSheet.AlbumMenu -> s.album.name
            null -> null
        }
        GlassActionSheet(
            visible = s != null,
            state = haze,
            title = title,
            options = options,
            onDismiss = { sheet = null },
        )
    }
}

@Composable
private fun TabBar(
    haze: HazeState,
    tab: HomeTab,
    onTab: (HomeTab) -> Unit,
    onLayout: () -> Unit,
    onSecondary: () -> Unit,
    filterActive: Boolean,
) {
    val c = GalleryTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(
            visible = tab == HomeTab.Photos,
            enter = fadeIn() + scaleIn() + expandHorizontally(),
            exit = fadeOut() + scaleOut() + shrinkHorizontally(),
        ) {
            Row {
                GlassIconButton(haze, Icons.Outlined.GridView, "Layout", size = 58.dp, onClick = onLayout)
                Spacer(Modifier.width(10.dp))
            }
        }

        val tabWidth = 82.dp
        val offset by animateDpAsState(
            tabWidth * tab.ordinal,
            spring(dampingRatio = 0.72f, stiffness = 520f),
            label = "pill",
        )
        GlassSurface(haze, Modifier.height(64.dp), shape = CircleShape) {
            Box(Modifier.padding(horizontal = 4.dp)) {
                Box(
                    Modifier
                        .offset(x = offset)
                        .padding(vertical = 5.dp)
                        .width(tabWidth)
                        .height(54.dp)
                        .background(c.glassHighlight, CircleShape)
                )
                Row {
                    HomeTab.entries.forEach { t ->
                        val selected = t == tab
                        Column(
                            Modifier
                                .width(tabWidth)
                                .height(64.dp)
                                .pressable { onTab(t) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                t.icon, null,
                                tint = c.content,
                                modifier = Modifier.size(23.dp).alpha(if (selected) 1f else 0.72f),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                t.label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = c.content.copy(alpha = if (selected) 1f else 0.72f),
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = tab != HomeTab.Create,
            enter = fadeIn() + scaleIn() + expandHorizontally(),
            exit = fadeOut() + scaleOut() + shrinkHorizontally(),
        ) {
            Row {
                Spacer(Modifier.width(10.dp))
                Box {
                    GlassIconButton(
                        haze,
                        if (tab == HomeTab.Albums) Icons.AutoMirrored.Outlined.Sort else Icons.Outlined.FilterList,
                        "Filter",
                        size = 58.dp,
                        onClick = onSecondary,
                    )
                    if (filterActive && tab == HomeTab.Photos) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp)
                                .size(9.dp)
                                .background(com.javaram.gallery.ui.theme.Accent, CircleShape)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SelectionBar(
    haze: HazeState,
    enabled: Boolean,
    allFavorite: Boolean,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassSurface(haze, Modifier.height(66.dp), shape = CircleShape) {
        Row(
            Modifier.padding(horizontal = 10.dp).alpha(if (enabled) 1f else 0.45f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarAction(Icons.Outlined.Share, "Share", enabled, onShare)
            BarAction(
                if (allFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                if (allFavorite) "Unfavourite" else "Favourite",
                enabled, onFavorite,
            )
            BarAction(Icons.Outlined.DeleteOutline, "Delete", enabled, onDelete, tint = Color(0xFFFF453A))
        }
    }
}

@Composable
fun BarAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color = GalleryTheme.colors.content,
) {
    Column(
        Modifier
            .width(86.dp)
            .fillMaxHeight()
            .pressable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/** Big ColorOS-style page title that scrolls with the content. */
@Composable
fun LargeHeader(title: String, subtitle: String?, modifier: Modifier = Modifier, extra: @Composable () -> Unit = {}) {
    val c = GalleryTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 56.dp, start = 16.dp, end = 16.dp, bottom = 6.dp)
    ) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = c.content)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.secondary, modifier = Modifier.padding(top = 2.dp))
        }
        extra()
    }
}

@Composable
fun ManageBanner(onAllow: () -> Unit, onDismiss: () -> Unit) {
    val c = GalleryTheme.colors
    Row(
        Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .background(c.card, RoundedCornerShape(20.dp))
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.VerifiedUser, null, tint = com.javaram.gallery.ui.theme.Accent, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text("Skip confirmation pop-ups", style = MaterialTheme.typography.titleSmall, color = c.content)
            Text(
                "Allow Gallery to manage media so deleting and favouriting is instant.",
                style = MaterialTheme.typography.bodySmall, color = c.secondary,
            )
        }
        Text(
            "Allow",
            color = com.javaram.gallery.ui.theme.Accent,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.pressable(onClick = onAllow).padding(8.dp),
        )
        Icon(
            Icons.Filled.Close, "Dismiss", tint = c.secondary,
            modifier = Modifier.size(34.dp).pressable(onClick = onDismiss).padding(8.dp),
        )
    }
}
