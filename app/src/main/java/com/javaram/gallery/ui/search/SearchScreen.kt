package com.javaram.gallery.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.javaram.gallery.GalleryViewModel
import com.javaram.gallery.data.Media
import com.javaram.gallery.data.thumb
import com.javaram.gallery.search.SearchEngine
import com.javaram.gallery.search.Suggestion
import com.javaram.gallery.ui.components.GlassSurface
import com.javaram.gallery.ui.components.GridHandle
import com.javaram.gallery.ui.components.GroupMode
import com.javaram.gallery.ui.components.MediaGrid
import com.javaram.gallery.ui.components.ProgressiveGlass
import com.javaram.gallery.ui.components.pressable
import com.javaram.gallery.ui.theme.Accent
import com.javaram.gallery.ui.theme.GalleryTheme
import com.javaram.gallery.ui.viewer.ViewerOverlay
import com.javaram.gallery.ui.viewer.ViewerSession
import com.javaram.gallery.util.countLabel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun SearchScreen(
    vm: GalleryViewModel,
    initialQuery: String,
    onBack: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenPerson: (Long) -> Unit,
) {
    val c = GalleryTheme.colors
    val haze = remember { HazeState() }
    val all by vm.all.collectAsStateWithLifecycle()
    val index by vm.index.collectAsStateWithLifecycle()
    val progress by vm.indexProgress.collectAsStateWithLifecycle()
    val recent by vm.recentSearches.collectAsStateWithLifecycle()
    val columns by vm.columns.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()
    val peopleByMedia by vm.peopleByMedia.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<Media>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<ViewerSession?>(null) }
    var hiddenId by remember { mutableStateOf<Long?>(null) }
    val handle = remember { GridHandle() }
    val gridState = rememberLazyGridState()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val byId = remember(all) { all.associateBy { it.id } }

    LaunchedEffect(Unit) {
        vm.reloadIndex()
        if (initialQuery.isEmpty()) runCatching { focus.requestFocus() }
    }

    LaunchedEffect(query, all, index, peopleByMedia) {
        if (query.isBlank()) { results = null; searching = false; return@LaunchedEffect }
        searching = true
        delay(220)
        results = withContext(Dispatchers.Default) { SearchEngine.search(query, all, index, peopleByMedia) }
        searching = false
    }

    val places by produceState(emptyList<Suggestion>(), all, index) {
        value = withContext(Dispatchers.Default) { SearchEngine.placeSuggestions(all, index) }
    }
    val things by produceState(emptyList<Suggestion>(), all, index) {
        value = withContext(Dispatchers.Default) { SearchEngine.thingSuggestions(all, index) }
    }
    val monthsList by produceState(emptyList<Suggestion>(), all) {
        value = withContext(Dispatchers.Default) { SearchEngine.monthSuggestions(all) }
    }

    // Back from a result list returns to the search page rather than leaving search.
    BackHandler(enabled = session == null && query.isNotEmpty() && query != initialQuery) {
        query = ""
        keyboard?.hide()
        focusManager.clearFocus()
    }

    fun runQuery(q: String) {
        query = q
        vm.addRecentSearch(q)
        keyboard?.hide()
        focusManager.clearFocus()
    }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 76.dp
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 30.dp

    Box(Modifier.fillMaxSize().background(c.background)) {
        Box(Modifier.fillMaxSize().haze(haze)) {
            AnimatedContent(
                targetState = query.isBlank(),
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                label = "search",
            ) { empty ->
                if (empty) {
                    LazyColumn(
                        contentPadding = PaddingValues(top = top, bottom = bottom),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (!progress.complete && progress.total > 0) {
                            item { IndexingCard(progress.done, progress.total, progress.running) }
                        }
                        if (recent.isNotEmpty()) {
                            item {
                                Column {
                                    SectionTitle("Recent", action = "Clear", onAction = vm::clearRecentSearches)
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        items(recent) { r -> Chip(r, Icons.Outlined.History) { runQuery(r) } }
                                    }
                                }
                            }
                        }
                        item { Column {
                            SectionTitle("Quick")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                item { Chip("Videos", Icons.Outlined.Videocam) { runQuery("videos") } }
                                item { Chip("Screenshots", Icons.Outlined.Screenshot) { runQuery("screenshots") } }
                                item { Chip("Favourites", Icons.Outlined.FavoriteBorder) { runQuery("favourites") } }
                                item { Chip("This month", Icons.Outlined.CalendarMonth) { runQuery("this month") } }
                                item { Chip("Last year", Icons.Outlined.CalendarMonth) { runQuery("last year") } }
                            }
                        } }
                        val named = people.filter { it.name != null && !it.hidden }
                        if (named.isNotEmpty()) {
                            item { Column {
                                SectionTitle("People")
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    items(named, key = { it.id }) { p ->
                                        Column(
                                            Modifier.width(72.dp).pressable { onOpenPerson(p.id) },
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            com.javaram.gallery.ui.people.FaceAvatar(p, byId, 68.dp)
                                            Text(
                                                p.name ?: "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = c.content,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(top = 6.dp),
                                            )
                                        }
                                    }
                                }
                            } }
                        }
                        item { Column {
                            SectionTitle("Places", action = "Map", actionIcon = Icons.Outlined.Map, onAction = onOpenMap)
                            if (places.isEmpty()) {
                                EmptyHint("Places appear here as photos with location are analysed.")
                            } else {
                                CardRow(places) { runQuery(it.query) }
                            }
                        } }
                        item { Column {
                            SectionTitle("Things")
                            if (things.isEmpty()) {
                                EmptyHint("Things like food, pets, beaches and documents appear as photos are analysed.")
                            } else {
                                CardRow(things) { runQuery(it.query) }
                            }
                        } }
                        item { Column {
                            SectionTitle("Text in photos")
                            EmptyHint(
                                "Type any word printed in a photo — a bill, a ticket, a screenshot — and it will be found.",
                                icon = Icons.Outlined.TextFields,
                            )
                        } }
                        if (monthsList.isNotEmpty()) {
                            item { Column {
                                SectionTitle("Months")
                                CardRow(monthsList, wide = true) { runQuery(it.query) }
                            } }
                        }
                    }
                } else {
                    val r = results
                    when {
                        r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Accent, strokeWidth = 2.5.dp, modifier = Modifier.size(30.dp))
                        }
                        r.isEmpty() && !searching -> Column(
                            Modifier.fillMaxSize().padding(top = top, start = 32.dp, end = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Spacer(Modifier.height(60.dp))
                            Icon(Icons.Outlined.Search, null, tint = c.secondary, modifier = Modifier.size(52.dp))
                            Text(
                                "No results for \"$query\"",
                                style = MaterialTheme.typography.titleMedium,
                                color = c.content,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                            if (!progress.complete) {
                                Text(
                                    "Still analysing ${countLabel(progress.total - progress.done)} photos — try again later.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c.secondary,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                        else -> MediaGrid(
                            items = r,
                            columns = columns,
                            group = GroupMode.Day,
                            state = gridState,
                            handle = handle,
                            hiddenId = hiddenId,
                            selection = emptySet(),
                            selectionMode = false,
                            onSelectionChange = {},
                            onEnterSelection = {},
                            onOpen = { m ->
                                vm.addRecentSearch(query)
                                session = ViewerSession(r.map { it.id }, m.id)
                            },
                            contentPadding = PaddingValues(bottom = bottom),
                            onColumnsChange = vm::setColumns,
                            header = {
                                Text(
                                    "${countLabel(r.size)} ${if (r.size == 1) "result" else "results"}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = c.secondary,
                                    modifier = Modifier.padding(top = top, start = 16.dp, bottom = 4.dp),
                                )
                            },
                        )
                    }
                }
            }
        }

        ProgressiveGlass(haze, Modifier.fillMaxWidth().height(130.dp)) {}

        // ---- Floating glass search bar ----
        GlassSurface(
            haze,
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth()
                .height(54.dp),
            shape = CircleShape,
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).pressable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = c.content) }
                Box(Modifier.weight(1f).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            "Search places, things, text, dates",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.content),
                        cursorBrush = SolidColor(Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { runQuery(query) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                androidx.compose.animation.AnimatedVisibility(query.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                    Box(
                        Modifier.size(42.dp).pressable { query = ""; runCatching { focus.requestFocus() } },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Cancel, "Clear", tint = c.secondary, modifier = Modifier.size(20.dp)) }
                }
            }
        }

        val sess = session
        if (sess != null) {
            val viewerItems = remember(sess, byId) { sess.ids.mapNotNull { byId[it] } }
            ViewerOverlay(
                session = sess,
                items = viewerItems,
                grid = handle,
                vm = vm,
                onCurrentChanged = { hiddenId = it },
                onClosed = { session = null; hiddenId = null },
            )
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    action: String? = null,
    actionIcon: ImageVector? = null,
    onAction: () -> Unit = {},
) {
    val c = GalleryTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = c.content, modifier = Modifier.weight(1f))
        if (action != null) {
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(c.card)
                    .pressable(onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (actionIcon != null) {
                    Icon(actionIcon, null, tint = Accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(action, color = Accent, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun Chip(label: String, icon: ImageVector, onClick: () -> Unit) {
    val c = GalleryTheme.colors
    Row(
        Modifier
            .clip(CircleShape)
            .background(c.card)
            .pressable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.secondary, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = c.content)
    }
}

@Composable
private fun CardRow(list: List<Suggestion>, wide: Boolean = false, onClick: (Suggestion) -> Unit) {
    val c = GalleryTheme.colors
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(list, key = { it.query }) { s ->
            Column(Modifier.width(if (wide) 150.dp else 112.dp).pressable { onClick(s) }) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(if (wide) 1.35f else 1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFFFFA24C), Color(0xFFFF4F2E)))),
                ) {
                    if (s.cover != null) {
                        AsyncImage(
                            model = s.cover.thumb(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(46.dp)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                    )
                    Text(
                        s.title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 8.dp, bottom = 8.dp),
                    )
                }
                Text(
                    "${s.subtitle} items",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.secondary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String, icon: ImageVector = Icons.Outlined.AutoAwesome) {
    val c = GalleryTheme.colors
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(c.card)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.secondary)
    }
}

@Composable
private fun IndexingCard(done: Int, total: Int, running: Boolean) {
    val c = GalleryTheme.colors
    val fraction = if (total > 0) done.toFloat() / total else 0f
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(c.card)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = Accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                if (running) "Analysing your photos" else "Analysis paused",
                style = MaterialTheme.typography.titleSmall,
                color = c.content,
                modifier = Modifier.weight(1f),
            )
            Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.titleSmall, color = Accent)
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(c.divider)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.01f, 1f))
                    .height(6.dp)
                    .background(Accent, CircleShape)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "${countLabel(done)} of ${countLabel(total)} done. Search gets smarter as this finishes — it carries on while your phone charges. Nothing leaves your phone except place lookups.",
            style = MaterialTheme.typography.bodySmall,
            color = c.secondary,
        )
    }
}
