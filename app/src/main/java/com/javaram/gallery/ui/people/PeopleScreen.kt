package com.javaram.gallery.ui.people

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javaram.gallery.GalleryViewModel
import com.javaram.gallery.search.Person
import com.javaram.gallery.ui.components.ConfirmRequest
import com.javaram.gallery.ui.components.GlassActionSheet
import com.javaram.gallery.ui.components.GlassDialog
import com.javaram.gallery.ui.components.GlassIconButton
import com.javaram.gallery.ui.components.GlassSurface
import com.javaram.gallery.ui.components.ProgressiveGlass
import com.javaram.gallery.ui.components.SheetOption
import com.javaram.gallery.ui.components.pressable
import com.javaram.gallery.ui.home.LargeHeader
import com.javaram.gallery.ui.theme.Accent
import com.javaram.gallery.ui.theme.GalleryTheme
import com.javaram.gallery.util.countLabel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

/** People with at least this many photos are shown (named people are always shown). */
const val MIN_PERSON_PHOTOS = 3

fun visiblePeople(all: List<Person>, showHidden: Boolean): List<Person> =
    all.filter { (it.name != null || it.count >= MIN_PERSON_PHOTOS) && it.hidden == showHidden }

@Composable
fun PeopleScreen(vm: GalleryViewModel, onBack: () -> Unit, onOpenPerson: (Long) -> Unit) {
    val c = GalleryTheme.colors
    val haze = remember { HazeState() }
    val all by vm.all.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()
    val progress by vm.faceProgress.collectAsStateWithLifecycle()
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Person?>(null) }
    var naming by remember { mutableStateOf<Person?>(null) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    val byId = remember(all) { all.associateBy { it.id } }
    val list = remember(people, showHidden) { visiblePeople(people, showHidden) }
    val state = rememberLazyGridState()
    val collapsed by remember { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 160 } }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 40.dp

    LaunchedEffect(Unit) { vm.reloadPeople() }

    Box(Modifier.fillMaxSize().background(c.background)) {
        Box(Modifier.fillMaxSize().haze(haze)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = state,
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = bottom),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        LargeHeader(
                            if (showHidden) "Hidden people" else "People",
                            "${list.size} ${if (list.size == 1) "person" else "people"}",
                        )
                        if (!progress.complete && progress.total > 0) {
                            FaceProgressCard(progress.done, progress.total)
                        }
                    }
                }
                if (list.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            if (showHidden) "No hidden people."
                            else "Faces are being grouped. People appear here once someone shows up in a few photos.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.secondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 40.dp, start = 20.dp, end = 20.dp),
                        )
                    }
                }
                items(list, key = { it.id }) { p ->
                    Column(
                        Modifier
                            .animateItem()
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onOpenPerson(p.id) },
                                onLongClick = { menu = p },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FaceAvatar(p, byId, 100.dp)
                        Spacer(Modifier.height(8.dp))
                        if (p.name != null) {
                            Text(
                                p.name, style = MaterialTheme.typography.titleSmall, color = c.content,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Text(
                                "Add name", style = MaterialTheme.typography.titleSmall, color = Accent,
                                modifier = Modifier.pressable { naming = p },
                            )
                        }
                        Text(countLabel(p.count), style = MaterialTheme.typography.bodySmall, color = c.secondary)
                    }
                }
            }
        }

        val topAlpha by animateFloatAsState(if (collapsed) 1f else 0f, tween(220), label = "top")
        ProgressiveGlass(haze, Modifier.fillMaxWidth().height(110.dp), visibleAlpha = topAlpha) {}
        Row(
            Modifier.statusBarsPadding().fillMaxWidth().height(60.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(haze, Icons.AutoMirrored.Outlined.ArrowBack, "Back", size = 42.dp, onClick = onBack)
            Spacer(Modifier.weight(1f))
            GlassSurface(haze, Modifier.height(42.dp).pressable { showHidden = !showHidden }, shape = CircleShape) {
                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (showHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                        null, tint = c.content, modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (showHidden) "Show people" else "Hidden", style = MaterialTheme.typography.titleSmall, color = c.content)
                }
            }
        }

        val m = menu
        GlassActionSheet(
            visible = m != null,
            state = haze,
            title = m?.name ?: "Unnamed person",
            options = if (m == null) emptyList() else listOf(
                SheetOption(if (m.name == null) "Add name" else "Rename", Icons.Outlined.Edit) { naming = m },
                SheetOption(
                    if (m.hidden) "Show this person" else "Hide this person",
                    if (m.hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                ) { vm.setPersonHidden(m.id, !m.hidden) },
            ),
            onDismiss = { menu = null },
        )

        val n = naming
        NameDialog(
            visible = n != null,
            haze = haze,
            initial = n?.name ?: "",
            onDismiss = { naming = null },
            onSave = { text ->
                val target = naming ?: return@NameDialog
                naming = null
                saveName(vm, target, text) { confirm = it }
            },
        )
        GlassDialog(request = confirm, state = haze, onDismiss = { confirm = null })
    }
}

/** Saves a name; asks before merging when the name already belongs to someone else. */
fun saveName(vm: GalleryViewModel, person: Person, text: String, ask: (ConfirmRequest) -> Unit) {
    val name = text.trim()
    if (name.isEmpty()) { vm.renamePerson(person.id, null); return }
    val other = vm.personWithName(name, person.id)
    if (other == null) {
        vm.renamePerson(person.id, name)
    } else {
        ask(
            ConfirmRequest(
                title = "Same person as $name?",
                message = "There's already someone called $name. Their photos will be combined with these.",
                confirmLabel = "Combine",
                destructive = false,
            ) { vm.nameAndMerge(person.id, name, other.id) }
        )
    }
}

@Composable
fun FaceProgressCard(done: Int, total: Int) {
    val c = GalleryTheme.colors
    val f = if (total > 0) done.toFloat() / total else 0f
    Column(
        Modifier
            .padding(horizontal = 2.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(c.card)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Finding faces", style = MaterialTheme.typography.titleSmall, color = c.content, modifier = Modifier.weight(1f))
            Text("${(f * 100).toInt()}%", style = MaterialTheme.typography.titleSmall, color = Accent)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.divider)) {
            Box(Modifier.fillMaxWidth(f.coerceIn(0.01f, 1f)).height(6.dp).background(Accent, CircleShape))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${countLabel(done)} of ${countLabel(total)} photos checked. Continues while charging.",
            style = MaterialTheme.typography.bodySmall,
            color = c.secondary,
        )
    }
}
