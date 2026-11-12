package com.javaram.gallery.ui.people

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javaram.gallery.GalleryViewModel
import com.javaram.gallery.ui.components.ConfirmRequest
import com.javaram.gallery.ui.components.GlassActionSheet
import com.javaram.gallery.ui.components.GlassDialog
import com.javaram.gallery.ui.components.toast
import com.javaram.gallery.ui.components.GlassIconButton
import com.javaram.gallery.ui.components.GlassPopupMenu
import com.javaram.gallery.ui.components.GlassSurface
import com.javaram.gallery.ui.components.GridHandle
import com.javaram.gallery.ui.components.GroupMode
import com.javaram.gallery.ui.components.MediaGrid
import com.javaram.gallery.ui.components.PopupAnchor
import com.javaram.gallery.ui.components.ProgressiveGlass
import com.javaram.gallery.ui.components.SheetOption
import com.javaram.gallery.ui.components.pressable
import com.javaram.gallery.ui.components.rememberMediaRequest
import com.javaram.gallery.ui.components.shareMedia
import com.javaram.gallery.ui.home.BarAction
import com.javaram.gallery.ui.theme.Accent
import com.javaram.gallery.ui.theme.GalleryTheme
import com.javaram.gallery.ui.viewer.ViewerOverlay
import com.javaram.gallery.ui.viewer.ViewerSession
import com.javaram.gallery.util.countLabel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

@Composable
fun PersonScreen(vm: GalleryViewModel, personId: Long, onBack: () -> Unit) {
    val c = GalleryTheme.colors
    val context = LocalContext.current
    val haze = remember { HazeState() }
    val all by vm.all.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()
    val version by vm.peopleVersion.collectAsStateWithLifecycle()
    val columns by vm.columns.collectAsStateWithLifecycle()
    val canManage by vm.canManageMedia.collectAsStateWithLifecycle()
    val person = people.firstOrNull { it.id == personId }
    val ids by produceState(emptySet<Long>(), personId, version) { value = vm.mediaForPerson(personId) }
    val items = remember(all, ids) { all.filter { it.id in ids } }
    val byId = remember(all) { all.associateBy { it.id } }

    var selectionMode by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var naming by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    var showMore by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    // "Not them" flow: the photos being moved, and which step is open.
    var moving by remember { mutableStateOf(emptySet<Long>()) }
    var moveSheet by remember { mutableStateOf(false) }
    var movePicker by remember { mutableStateOf(false) }
    var newPerson by remember { mutableStateOf(false) }
    var coverPick by remember { mutableStateOf(false) }
    var moreAnchor by remember { mutableStateOf<Rect?>(null) }
    var session by remember { mutableStateOf<ViewerSession?>(null) }
    var hiddenId by remember { mutableStateOf<Long?>(null) }
    val handle = remember { GridHandle() }
    val state = rememberLazyGridState()
    val collapsed by remember { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 200 } }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 110.dp

    fun exitSelection() { selectionMode = false; selection = emptySet() }
    BackHandler(selectionMode) { exitSelection() }
    BackHandler(merging) { merging = false }
    BackHandler(coverPick) { coverPick = false }
    BackHandler(moveSheet || movePicker || newPerson) { moveSheet = false; movePicker = false; newPerson = false }
    val request = rememberMediaRequest { ok -> if (ok) exitSelection(); vm.scheduleRefresh(0) }
    val selected = remember(selection, items) { items.filter { it.id in selection } }
    val title = person?.name ?: "Add name"

    Box(Modifier.fillMaxSize().background(c.background)) {
        Box(Modifier.fillMaxSize().haze(haze)) {
            MediaGrid(
                items = items,
                columns = columns,
                group = GroupMode.Day,
                state = state,
                handle = handle,
                hiddenId = hiddenId,
                selection = selection,
                selectionMode = selectionMode,
                onSelectionChange = { selection = it },
                onEnterSelection = { selectionMode = true },
                onOpen = { m ->
                    if (coverPick) {
                        coverPick = false
                        vm.setPersonCover(personId, m.id)
                        toast(context, "Cover photo updated")
                    } else session = ViewerSession(items.map { it.id }, m.id)
                },
                contentPadding = PaddingValues(bottom = bottom),
                onColumnsChange = vm::setColumns,
                header = {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(top = 64.dp, bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (person != null) FaceAvatar(person, byId, 112.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            title,
                            style = MaterialTheme.typography.displaySmall,
                            color = if (person?.name == null) Accent else c.content,
                            modifier = Modifier.pressable { naming = true },
                        )
                        Text(
                            "${countLabel(items.size)} photos",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.secondary,
                        )
                    }
                },
            )
        }

        val topAlpha by animateFloatAsState(if (collapsed || selectionMode) 1f else 0f, tween(220), label = "top")
        ProgressiveGlass(haze, Modifier.fillMaxWidth().height(110.dp), visibleAlpha = topAlpha) {}
        Row(
            Modifier.statusBarsPadding().fillMaxWidth().height(60.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (coverPick) {
                GlassIconButton(haze, Icons.Filled.Close, "Cancel", size = 42.dp) { coverPick = false }
                GlassSurface(haze, Modifier.padding(start = 10.dp).height(42.dp), shape = CircleShape) {
                    Text(
                        "Tap a photo to use as cover",
                        style = MaterialTheme.typography.titleSmall, color = c.content,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else if (selectionMode) {
                GlassIconButton(haze, Icons.Filled.Close, "Cancel", size = 42.dp) { exitSelection() }
                Text(
                    if (selection.isEmpty()) "Select items" else "${selection.size} selected",
                    style = MaterialTheme.typography.titleMedium, color = c.content,
                    modifier = Modifier.weight(1f).padding(start = 14.dp),
                )
            } else {
                GlassIconButton(haze, Icons.AutoMirrored.Outlined.ArrowBack, "Back", size = 42.dp, onClick = onBack)
                Box(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    androidx.compose.animation.AnimatedVisibility(collapsed, enter = fadeIn(), exit = fadeOut()) {
                        Text(title, style = MaterialTheme.typography.titleMedium, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                GlassIconButton(haze, Icons.Outlined.Checklist, "Select", size = 42.dp) { selectionMode = true }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.onGloballyPositioned { moreAnchor = it.boundsInRoot() }) {
                    GlassIconButton(haze, Icons.Outlined.MoreVert, "More", size = 42.dp) { showMore = true }
                }
            }
        }

        AnimatedVisibility(
            visible = selectionMode,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            GlassSurface(haze, Modifier.height(66.dp), shape = CircleShape) {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val enabled = selection.isNotEmpty()
                    BarAction(Icons.Outlined.PersonRemove, "Not them", enabled, {
                        moving = selection
                        moveSheet = true
                    })
                    BarAction(Icons.Outlined.AccountCircle, "Cover", selection.size == 1, {
                        selection.firstOrNull()?.let { vm.setPersonCover(personId, it) }
                        toast(context, "Cover photo updated")
                        exitSelection()
                    })
                    BarAction(Icons.Outlined.Share, "Share", enabled, { shareMedia(context, selected) })
                    BarAction(Icons.Outlined.DeleteOutline, "Delete", enabled, {
                        val targets = selected
                        val doIt = { request(vm.trashRequest(targets)) }
                        if (canManage) {
                            confirm = ConfirmRequest(
                                if (targets.size == 1) "Delete this item?" else "Delete ${targets.size} items?",
                                "They'll stay in Recently deleted for 30 days.",
                                "Delete",
                            ) { doIt() }
                        } else doIt()
                    }, tint = Color(0xFFFF453A))
                }
            }
        }

        GlassPopupMenu(
            visible = showMore,
            state = haze,
            anchor = moreAnchor,
            placement = PopupAnchor.BelowEnd,
            options = listOf(
                SheetOption(if (person?.name == null) "Add name" else "Rename", Icons.Outlined.Edit) { naming = true },
                SheetOption("Merge with…", Icons.Outlined.GroupAdd) { merging = true },
                SheetOption("Change cover photo", Icons.Outlined.AccountCircle) { coverPick = true },
                SheetOption(
                    if (person?.hidden == true) "Show this person" else "Hide this person",
                    if (person?.hidden == true) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                ) {
                    if (person != null) vm.setPersonHidden(person.id, !person.hidden)
                    if (person?.hidden == false) onBack()
                },
            ),
            onDismiss = { showMore = false },
        )

        NameDialog(
            visible = naming,
            haze = haze,
            initial = person?.name ?: "",
            onDismiss = { naming = false },
            onSave = { text ->
                naming = false
                if (person != null) saveName(vm, person, text) { confirm = it }
            },
        )
        // ---- "Not them" options ----
        val who = person?.name ?: "this person"
        val n = moving.size
        GlassActionSheet(
            visible = moveSheet,
            state = haze,
            title = if (n == 1) "This photo isn't $who" else "These $n photos aren't $who",
            options = listOf(
                SheetOption("Move to someone else", Icons.Outlined.SwapHoriz, subtitle = "Pick the right person") {
                    movePicker = true
                },
                SheetOption("Move to a new person", Icons.Outlined.PersonAdd, subtitle = "Start a new group with these") {
                    newPerson = true
                },
                SheetOption("Just remove from $who", Icons.Outlined.PersonRemove, subtitle = "Photos aren't deleted") {
                    vm.removeFromPerson(personId, moving)
                    exitSelection()
                    toast(context, "Removed from $who")
                },
            ),
            onDismiss = { moveSheet = false },
        )
        MergePicker(
            visible = movePicker,
            haze = haze,
            title = "Move to…",
            people = people.filter { it.id != personId && !it.hidden && (it.name != null || it.count >= MIN_PERSON_PHOTOS) },
            byId = byId,
            onDismiss = { movePicker = false },
            onPick = { other ->
                movePicker = false
                val ids = moving
                vm.moveToPerson(personId, ids, other.id)
                exitSelection()
                toast(context, "Moved ${ids.size} to ${other.name ?: "the selected person"}")
            },
        )
        NameDialog(
            visible = newPerson,
            haze = haze,
            initial = "",
            title = "Name the new person",
            allowEmpty = true,
            onDismiss = { newPerson = false },
            onSave = { text ->
                newPerson = false
                val ids = moving
                val clean = text.trim().ifEmpty { null }
                val existing = clean?.let { vm.personWithName(it, personId) }
                if (existing != null) {
                    vm.moveToPerson(personId, ids, existing.id)
                    toast(context, "Moved ${ids.size} to ${existing.name}")
                } else {
                    vm.moveToNewPerson(personId, ids, clean)
                    toast(context, "Moved ${ids.size} to ${clean ?: "a new person"}")
                }
                exitSelection()
            },
        )

        MergePicker(
            visible = merging,
            title = "Merge with…",
            haze = haze,
            people = people.filter { it.id != personId && !it.hidden && (it.name != null || it.count >= MIN_PERSON_PHOTOS) },
            byId = byId,
            onDismiss = { merging = false },
            onPick = { other ->
                merging = false
                confirm = ConfirmRequest(
                    "Combine with ${other.name ?: "this person"}?",
                    "Both groups are the same person. Their photos will be shown together here.",
                    "Combine",
                    destructive = false,
                ) { vm.mergePersons(from = other.id, into = personId) }
            },
        )

        GlassDialog(request = confirm, state = haze, onDismiss = { confirm = null })

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

/** Glass panel listing people to combine with. */
@Composable
private fun MergePicker(
    visible: Boolean,
    haze: HazeState,
    title: String,
    people: List<com.javaram.gallery.search.Person>,
    byId: Map<Long, com.javaram.gallery.data.Media>,
    onDismiss: () -> Unit,
    onPick: (com.javaram.gallery.search.Person) -> Unit,
) {
    val c = GalleryTheme.colors
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f))
                    .then(Modifier.pressable(onClick = onDismiss))
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            GlassSurface(
                haze,
                Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth().height(420.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                Column(Modifier.padding(top = 18.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = c.content,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(4),
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 18.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
                    ) {
                        items(people.size, key = { people[it].id }) { i ->
                            val p = people[i]
                            Column(
                                Modifier.pressable { onPick(p) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                FaceAvatar(p, byId, 64.dp)
                                Text(
                                    p.name ?: countLabel(p.count),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (p.name != null) c.content else c.secondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
