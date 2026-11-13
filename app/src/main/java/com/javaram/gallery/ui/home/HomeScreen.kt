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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.ViewComfy
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.MusicOff
import androidx.compose.material.icons.outlined.LibraryAdd
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
import com.javaram.gallery.isCameraShot
import com.javaram.gallery.util.toLocalDate
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
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.javaram.gallery.ui.UpdateDialog
import com.javaram.gallery.ui.components.ConfirmRequest
import com.javaram.gallery.ui.components.GlassDialog
import com.javaram.gallery.ui.components.GlassPopupMenu
import com.javaram.gallery.ui.components.GridHandle
import com.javaram.gallery.ui.components.PopupAnchor
import com.javaram.gallery.ui.viewer.ViewerOverlay
import com.javaram.gallery.ui.viewer.ViewerSession
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
    data class AlbumMenu(val album: Album, val anchor: androidx.compose.ui.geometry.Rect?) : HomeSheet
    data object Hidden : HomeSheet
}

@Composable
fun HomeScreen(
    vm: GalleryViewModel,
    onOpenAlbum: (String) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenPeople: () -> Unit,
    onOpenPerson: (Long) -> Unit,
    onOpenRoute: (String) -> Unit = {},
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
    val update by vm.update.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val hiddenAlbums by vm.hiddenAlbums.collectAsStateWithLifecycle()
    val hideMusicArt by vm.hideMusicArt.collectAsStateWithLifecycle()
    val albumFlow = com.javaram.gallery.ui.album.rememberAddToAlbum()

    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var sheet by remember { mutableStateOf<HomeSheet?>(null) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    var session by remember { mutableStateOf<ViewerSession?>(null) }
    var hiddenId by remember { mutableStateOf<Long?>(null) }

    var moreAnchor by remember { mutableStateOf<Rect?>(null) }
    var layoutAnchor by remember { mutableStateOf<Rect?>(null) }
    var secondaryAnchor by remember { mutableStateOf<Rect?>(null) }

    val photosState = rememberLazyGridState()
    val albumsState = rememberLazyGridState()
    val createState = rememberLazyGridState()
    val photosHandle = remember { GridHandle() }

    val photos = remember(all, filter) {
        when (filter) {
            MediaFilter.All -> all
            MediaFilter.Camera -> all.filter { it.isCameraShot() }
            MediaFilter.Photos -> all.filter { !it.isVideo }
            MediaFilter.Videos -> all.filter { it.isVideo }
            MediaFilter.Favorites -> all.filter { it.isFavorite }
        }
    }
    val videoCount = remember(all) { all.count { it.isVideo } }
    val byId = remember(all) { all.associateBy { it.id } }

    fun exitSelection() {
        selectionMode = false
        selection = emptySet()
    }

    LaunchedEffect(tab) { if (tab != HomeTab.Photos) exitSelection() }
    BackHandler(enabled = selectionMode) { exitSelection() }
    BackHandler(enabled = sheet != null) { sheet = null }
    BackHandler(enabled = confirm != null) { confirm = null }

    val request = rememberMediaRequest { ok ->
        if (ok) exitSelection()
        vm.scheduleRefresh(0)
    }
    val selectedItems = remember(selection, all) { all.filter { it.id in selection } }
    val hideInSafe = com.javaram.gallery.ui.safe.rememberHideInSafe(vm) { exitSelection() }

    val activeState: LazyGridState = when (tab) {
        HomeTab.Photos -> photosState
        HomeTab.Albums -> albumsState
        HomeTab.Create -> createState
    }
    val collapsed by remember(activeState) {
        derivedStateOf { activeState.firstVisibleItemIndex > 0 || activeState.firstVisibleItemScrollOffset > 160 }
    }
    // Month and year of the photos at the top of the screen, shown in the bar while scrolling.
    val topBarHeight = with(androidx.compose.ui.platform.LocalDensity.current) { 110.dp.roundToPx() }
    val scrolledMonth by remember(photosState, byId) {
        derivedStateOf {
            val info = photosState.layoutInfo.visibleItemsInfo
            val firstPhoto = info.firstOrNull { it.key is Long && it.offset.y + it.size.height > topBarHeight }
                ?: info.firstOrNull { it.key is Long }
            (firstPhoto?.key as? Long)?.let { byId[it] }?.let {
                com.javaram.gallery.util.monthLabel(it.dateTaken.toLocalDate())
            }
        }
    }

    Box(Modifier.fillMaxSize().background(GalleryTheme.colors.background)) {
        // Everything inside here is the "source" that glass surfaces blur.
        Box(Modifier.fillMaxSize().haze(haze)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    (fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.985f)) togetherWith fadeOut(tween(120))
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
                        handle = photosHandle,
                        hiddenId = hiddenId,
                        selection = selection,
                        selectionMode = selectionMode,
                        onSelectionChange = { selection = it },
                        onEnterSelection = { selectionMode = true },
                        onOpen = { m -> session = ViewerSession(photos.map { it.id }, m.id) },
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
                        onAlbumMenu = { a, r -> sheet = HomeSheet.AlbumMenu(a, r) },
                        people = people,
                        onOpenPeople = onOpenPeople,
                        onOpenPerson = onOpenPerson,
                        onOpenSafe = { onOpenRoute("safe") },
                        onNewAlbum = { albumFlow.newAlbum() },
                    )
                    HomeTab.Create -> CreateTab(
                        state = createState,
                        memories = memories,
                        onMemory = { m -> vm.playSlideshow(m.title, m.items); onOpenRoute("slideshow") },
                        onCollage = { onOpenRoute("collage") },
                        onEditor = { onOpenRoute("editpick") },
                        onCleanup = { onOpenRoute("cleanup") },
                        onSafe = { onOpenRoute("safe") },
                        onSlideshow = {
                            val favs = all.filter { it.isFavorite && !it.isVideo }
                            val list = if (favs.size >= 3) favs else all.filter { !it.isVideo }.take(60)
                            vm.playSlideshow(if (favs.size >= 3) "Favourites" else "Recent photos", list)
                            onOpenRoute("slideshow")
                        },
                    )
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
                        val label = if (tab == HomeTab.Photos) scrolledMonth ?: tab.label else tab.label
                        AnimatedContent(
                            targetState = label,
                            transitionSpec = {
                                (fadeIn(tween(180)) + slideInVertically { it / 3 }) togetherWith
                                    (fadeOut(tween(120)) + slideOutVertically { -it / 3 })
                            },
                            label = "month",
                        ) { text ->
                            Text(
                                text,
                                style = MaterialTheme.typography.titleMedium,
                                color = GalleryTheme.colors.content,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
                if (tab != HomeTab.Create) {
                    GlassIconButton(haze, Icons.Outlined.Search, "Search", size = 42.dp, onClick = onOpenSearch)
                    Spacer(Modifier.width(10.dp))
                }
                if (tab == HomeTab.Photos) {
                    GlassIconButton(haze, Icons.Outlined.Checklist, "Select", size = 42.dp) { selectionMode = true }
                    Spacer(Modifier.width(10.dp))
                }
                if (tab != HomeTab.Create) {
                    Box(Modifier.onGloballyPositioned { moreAnchor = it.boundsInRoot() }) {
                        GlassIconButton(haze, Icons.Outlined.MoreVert, "More", size = 42.dp) { sheet = HomeSheet.More }
                    }
                }
            }
        }

        // ---- Bottom: floating tab bar or selection actions (only the controls are glass) ----
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
                        onHide = { hideInSafe(selectedItems) },
                        onAddToAlbum = { albumFlow.start(selectedItems) },
                        onDelete = {
                            val items = selectedItems
                            val doIt = { request(vm.trashRequest(items)) }
                            if (canManage) {
                                confirm = ConfirmRequest(
                                    if (items.size == 1) "Delete this item?" else "Delete ${items.size} items?",
                                    "They'll stay in Recently deleted for 30 days.",
                                    "Delete",
                                ) { doIt() }
                            } else doIt()
                        },
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
                        onLayoutAnchor = { layoutAnchor = it },
                        onSecondaryAnchor = { secondaryAnchor = it },
                    )
                }
            }
        }

        // ---- Pop-up menus anchored to the button that opened them ----
        val s = sheet
        val popupOptions: List<SheetOption> = when (s) {
            HomeSheet.Filter -> MediaFilter.entries.map { f ->
                SheetOption(f.label, selected = f == filter) { vm.setFilter(f) }
            }
            HomeSheet.Layout -> listOf(
                Triple(3, "Comfortable", Icons.Outlined.ViewComfy),
                Triple(4, "Standard", Icons.Outlined.GridView),
                Triple(5, "Compact", Icons.Outlined.ViewModule),
                Triple(6, "By month", Icons.Outlined.CalendarMonth),
            ).map { (n, label, icon) ->
                SheetOption(label, icon = icon, selected = columns == n) { vm.setColumns(n) }
            }
            HomeSheet.Sort -> AlbumSort.entries.map { so ->
                SheetOption(so.label, selected = so == albumSort) { vm.setAlbumSort(so) }
            }
            HomeSheet.More -> buildList {
                add(SheetOption("Recently deleted", Icons.Outlined.AutoDelete) { onOpenTrash() })
                add(SheetOption("Private safe", Icons.Outlined.Lock) { onOpenRoute("safe") })
                add(SheetOption("Cleanup", Icons.Outlined.Refresh) { onOpenRoute("cleanup") })
                add(SheetOption("Hidden albums", Icons.Outlined.VisibilityOff) { sheet = HomeSheet.Hidden })
                add(SheetOption("Check for updates", Icons.Outlined.SystemUpdate) { vm.checkForUpdates(manual = true) })
                if (!canManage) {
                    add(SheetOption("Skip pop-ups", Icons.Outlined.VerifiedUser, subtitle = "Manage media access") {
                        requestManageMedia(context)
                    })
                }
                add(SheetOption("Refresh", Icons.Outlined.Refresh) { vm.scheduleRefresh(0) })
            }
            else -> emptyList()
        }
        val popupVisible = s == HomeSheet.Filter || s == HomeSheet.Layout || s == HomeSheet.Sort || s == HomeSheet.More
        var lastPopup by remember { mutableStateOf<HomeSheet?>(null) }
        if (popupVisible) lastPopup = s
        val shown = if (popupVisible) s else lastPopup
        GlassPopupMenu(
            visible = popupVisible,
            state = haze,
            anchor = when (shown) {
                HomeSheet.More -> moreAnchor
                HomeSheet.Layout -> layoutAnchor
                else -> secondaryAnchor
            },
            placement = when (shown) {
                HomeSheet.More -> PopupAnchor.BelowEnd
                HomeSheet.Layout -> PopupAnchor.AboveStart
                else -> PopupAnchor.AboveEnd
            },
            options = popupOptions,
            title = when (shown) {
                HomeSheet.Filter -> "Show"
                HomeSheet.Layout -> "Layout"
                HomeSheet.Sort -> "Sort albums by"
                else -> null
            },
            onDismiss = { sheet = null },
        )

        // Album menu pops out of the album that was long-pressed (and shrinks back into it).
        val openAlbumMenu = s as? HomeSheet.AlbumMenu
        var lastAlbumMenu by remember { mutableStateOf<HomeSheet.AlbumMenu?>(null) }
        if (openAlbumMenu != null) lastAlbumMenu = openAlbumMenu
        val albumMenu = openAlbumMenu ?: lastAlbumMenu
        val screenH = with(androidx.compose.ui.platform.LocalDensity.current) {
            androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx()
        }
        val menuRoom = with(androidx.compose.ui.platform.LocalDensity.current) { 330.dp.toPx() }
        GlassPopupMenu(
            visible = openAlbumMenu != null,
            state = haze,
            anchor = albumMenu?.anchor,
            placement = if ((albumMenu?.anchor?.bottom ?: 0f) + menuRoom > screenH) PopupAnchor.AboveCenter else PopupAnchor.BelowCenter,
            title = albumMenu?.album?.name,
            options = if (albumMenu == null) emptyList() else listOf(
                SheetOption("Open", Icons.Outlined.PhotoLibrary) { onOpenAlbum(albumMenu.album.key) },
                SheetOption(if (albumMenu.album.pinned) "Unpin from top" else "Pin to top", Icons.Outlined.PushPin) {
                    vm.togglePin(albumMenu.album.key)
                },
                SheetOption("Hide album", Icons.Outlined.VisibilityOff) {
                    vm.setAlbumHidden(albumMenu.album.key, true)
                    com.javaram.gallery.ui.components.toast(context, "Album hidden. Find it under ⋮ › Hidden albums")
                },
                SheetOption("Delete album", Icons.Outlined.DeleteOutline, destructive = true) {
                    val a = albumMenu.album
                    val items = all.filter { it.bucketId.toString() == a.key }
                    if (items.isNotEmpty()) {
                        val doIt = { request(vm.trashRequest(items)) }
                        if (canManage) {
                            confirm = ConfirmRequest(
                                "Delete “${a.name}”?",
                                if (items.size == 1) "Its 1 item will stay in Recently deleted for 30 days."
                                else "All ${items.size} items will stay in Recently deleted for 30 days.",
                                "Delete",
                            ) { doIt() }
                        } else doIt()
                    }
                },
            ),
            onDismiss = { if (sheet is HomeSheet.AlbumMenu) sheet = null },
        )

        GlassActionSheet(
            visible = s == HomeSheet.Hidden,
            state = haze,
            title = "Hidden albums",
            options = buildList {
                add(
                    SheetOption(
                        "Hide music cover art",
                        Icons.Outlined.MusicOff,
                        selected = hideMusicArt,
                        subtitle = "Album art saved in music folders",
                    ) { vm.setHideMusicArt(!hideMusicArt) }
                )
                if (hiddenAlbums.isEmpty()) {
                    add(SheetOption("No hidden albums", subtitle = "Long-press an album to hide it") {})
                }
                hiddenAlbums.forEach { a ->
                    add(SheetOption(a.name, Icons.Outlined.Visibility, subtitle = "${a.count} items · Tap to show again") {
                        vm.setAlbumHidden(a.key, false)
                    })
                }
            },
            onDismiss = { if (sheet == HomeSheet.Hidden) sheet = null },
        )

        com.javaram.gallery.ui.album.AddToAlbumFlow(
            state = albumFlow,
            vm = vm,
            haze = haze,
            onNamedEmpty = { name -> onOpenRoute("albumpick/" + android.net.Uri.encode(name)) },
            onDone = { exitSelection() },
        )

        GlassDialog(request = confirm, state = haze, onDismiss = { confirm = null })

        UpdateDialog(
            state = update,
            haze = haze,
            onUpdate = vm::startUpdate,
            onDismiss = vm::dismissUpdate,
        )

        // ---- Full-screen photo viewer ----
        val sess = session
        if (sess != null) {
            val viewerItems = remember(sess, byId) { sess.ids.mapNotNull { byId[it] } }
            ViewerOverlay(
                session = sess,
                items = viewerItems,
                grid = photosHandle,
                vm = vm,
                onCurrentChanged = { hiddenId = it },
                onClosed = { session = null; hiddenId = null },
            )
        }
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
    onLayoutAnchor: (Rect) -> Unit,
    onSecondaryAnchor: (Rect) -> Unit,
) {
    val c = GalleryTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(
            visible = tab == HomeTab.Photos,
            enter = fadeIn() + scaleIn() + expandHorizontally(),
            exit = fadeOut() + scaleOut() + shrinkHorizontally(),
        ) {
            Row {
                Box(Modifier.onGloballyPositioned { onLayoutAnchor(it.boundsInRoot()) }) {
                    GlassIconButton(haze, Icons.Outlined.GridView, "Layout", size = 58.dp, onClick = onLayout)
                }
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
                Box(Modifier.onGloballyPositioned { onSecondaryAnchor(it.boundsInRoot()) }) {
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
    onHide: (() -> Unit)? = null,
    onAddToAlbum: (() -> Unit)? = null,
) {
    val count = 3 + (if (onHide != null) 1 else 0) + (if (onAddToAlbum != null) 1 else 0)
    val w = if (count >= 5) 70.dp else 86.dp
    GlassSurface(haze, Modifier.height(66.dp), shape = CircleShape) {
        Row(
            Modifier.padding(horizontal = 8.dp).alpha(if (enabled) 1f else 0.45f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarAction(Icons.Outlined.Share, "Share", enabled, onShare, width = w)
            BarAction(
                if (allFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                if (allFavorite) "Unfavourite" else "Favourite",
                enabled, onFavorite, width = w,
            )
            if (onAddToAlbum != null) BarAction(Icons.Outlined.LibraryAdd, "Album", enabled, onAddToAlbum, width = w)
            if (onHide != null) BarAction(Icons.Outlined.Lock, "Hide", enabled, onHide, width = w)
            BarAction(Icons.Outlined.DeleteOutline, "Delete", enabled, onDelete, tint = Color(0xFFFF453A), width = w)
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
    width: androidx.compose.ui.unit.Dp = 86.dp,
) {
    Column(
        Modifier
            .width(width)
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
