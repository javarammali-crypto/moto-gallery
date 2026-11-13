package com.javaram.gallery.ui.album

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javaram.gallery.GalleryViewModel
import com.javaram.gallery.data.AlbumOps
import com.javaram.gallery.data.Media
import com.javaram.gallery.ui.components.GlassActionSheet
import com.javaram.gallery.ui.components.GlassSurface
import com.javaram.gallery.ui.components.SheetOption
import com.javaram.gallery.ui.components.rememberMediaRequest
import com.javaram.gallery.ui.components.toast
import com.javaram.gallery.ui.people.NameDialog
import com.javaram.gallery.ui.theme.Accent
import com.javaram.gallery.ui.theme.GalleryTheme
import dev.chrisbanes.haze.HazeState

/** Where the "add to album" flow is. */
sealed interface AlbumStep {
    /** Choose an existing album or "New album". */
    data class Pick(val items: List<Media>) : AlbumStep
    /** Type a name for a new album. [items] is null when photos are chosen afterwards. */
    data class Name(val items: List<Media>?) : AlbumStep
    /** Copy or move [items] into [path]. */
    data class Mode(val items: List<Media>, val path: String, val label: String) : AlbumStep
}

@Stable
class AddToAlbumState {
    var step by mutableStateOf<AlbumStep?>(null)
    fun start(items: List<Media>) { if (items.isNotEmpty()) step = AlbumStep.Pick(items) }
    fun newAlbum() { step = AlbumStep.Name(null) }
    fun choose(items: List<Media>, path: String, label: String) { step = AlbumStep.Mode(items, path, label) }
}

@Composable
fun rememberAddToAlbum() = remember { AddToAlbumState() }

/**
 * Sheets and dialogs for putting photos into an album.
 * [onNamedEmpty] is called when a new album is named before any photos were chosen.
 * [onDone] runs after the copy or move finishes.
 */
@Composable
fun AddToAlbumFlow(
    state: AddToAlbumState,
    vm: GalleryViewModel,
    haze: HazeState,
    onNamedEmpty: (String) -> Unit = {},
    onDone: () -> Unit = {},
) {
    val context = LocalContext.current
    val albums by vm.albums.collectAsStateWithLifecycle()
    val busy by vm.albumBusy.collectAsStateWithLifecycle()
    var pendingMove by remember { mutableStateOf<AlbumStep.Mode?>(null) }

    fun finish(n: Int, total: Int, moved: Boolean, label: String) {
        val verb = if (moved) "Moved" else "Copied"
        toast(
            context,
            when {
                n == total -> "$verb ${if (n == 1) "1 item" else "$n items"} to $label"
                n == 0 -> "Couldn't add to $label"
                else -> "$verb $n of $total items to $label"
            }
        )
        onDone()
    }

    val request = rememberMediaRequest { ok ->
        val p = pendingMove
        pendingMove = null
        if (ok && p != null) vm.addToAlbum(p.items, p.path, move = true) { n -> finish(n, p.items.size, true, p.label) }
    }

    val step = state.step
    BackHandler(step != null) { state.step = null }

    // Step 1: pick an album
    val pick = step as? AlbumStep.Pick
    GlassActionSheet(
        visible = pick != null,
        state = haze,
        title = pick?.let { if (it.items.size == 1) "Add 1 item to" else "Add ${it.items.size} items to" },
        options = if (pick == null) emptyList() else buildList {
            add(SheetOption("New album…", Icons.Outlined.CreateNewFolder) { state.step = AlbumStep.Name(pick.items) })
            albums.forEach { a ->
                val path = vm.albumPath(a.key) ?: return@forEach
                add(SheetOption(a.name, Icons.Outlined.PhotoLibrary, subtitle = "${a.count} items") {
                    state.choose(pick.items, path, a.name)
                })
            }
        },
        onDismiss = { if (state.step is AlbumStep.Pick) state.step = null },
    )

    // Step 2: name a new album
    val naming = step as? AlbumStep.Name
    NameDialog(
        visible = naming != null,
        haze = haze,
        initial = "",
        title = "New album",
        placeholder = "Album name",
        onDismiss = { state.step = null },
        onSave = { raw ->
            val name = raw.trim()
            if (name.isEmpty()) return@NameDialog
            val clean = AlbumOps.sanitize(name)
            val items = naming?.items
            if (items == null) {
                state.step = null
                onNamedEmpty(clean)
            } else {
                state.choose(items, AlbumOps.newAlbumPath(clean), clean)
            }
        },
    )

    // Step 3: copy or move
    val mode = step as? AlbumStep.Mode
    GlassActionSheet(
        visible = mode != null,
        state = haze,
        title = mode?.label,
        options = if (mode == null) emptyList() else listOf(
            SheetOption(
                "Copy here", Icons.Outlined.ContentCopy,
                subtitle = "Keeps the originals where they are",
            ) {
                vm.addToAlbum(mode.items, mode.path, move = false) { n -> finish(n, mode.items.size, false, mode.label) }
            },
            SheetOption(
                "Move here", Icons.Outlined.DriveFileMove,
                subtitle = "Takes them out of their current album",
            ) {
                pendingMove = mode
                request(vm.writeRequest(mode.items))
            },
        ),
        onDismiss = { if (state.step is AlbumStep.Mode) state.step = null },
    )

    // Progress while copying/moving
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(busy != null, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut() + scaleOut(targetScale = 0.9f)) {
            GlassSurface(haze, shape = RoundedCornerShape(24.dp)) {
                Row(Modifier.padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(busy ?: "", style = MaterialTheme.typography.titleSmall, color = GalleryTheme.colors.content)
                }
            }
        }
    }
}
