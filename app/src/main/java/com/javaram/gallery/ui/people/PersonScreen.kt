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
import androidx.compose.material.icons.outlined.Edit
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
import com.javaram.gallery.ui.components.GlassDialog
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
    var moreAnchor by remember { mutableStateOf<Rect?>(null) }
    var session by remember { mutableStateOf<ViewerSession?>(null) }
    var hiddenId by remember { mutableStateOf<Long?>(null) }
    val handle = remember { GridHandle() }
    val state = rememberLazyGridState()
    val collapsed by remember { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 200 } }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 110.dp

    fun exitSelection() { selectionMode = false; selection = emptySet() }
    BackHandler(selectionMode) { exitSelection() }
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
                onOpen = { m -> session = ViewerSession(items.map { it.id }, m.id) },
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
            if (selectionMode) {
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
                        confirm = ConfirmRequest(
                            "Remove from ${person?.name ?: "this person"}?",
                            "These ${selection.size} photos won't be grouped with this person any more. The photos aren't deleted.",
                            "Remove",
                            destructive = false,
                        ) {
                            vm.removeFromPerson(personId, selection)
                            exitSelection()
                        }
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
