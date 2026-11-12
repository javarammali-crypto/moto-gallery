package com.javaram.gallery.ui.safe

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.CancellationSignal
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.javaram.gallery.GalleryViewModel
import com.javaram.gallery.data.Media
import com.javaram.gallery.ui.components.ConfirmRequest
import com.javaram.gallery.ui.components.GlassDialog
import com.javaram.gallery.ui.components.GlassIconButton
import com.javaram.gallery.ui.components.GlassSurface
import com.javaram.gallery.ui.components.ProgressiveGlass
import com.javaram.gallery.ui.components.pressable
import com.javaram.gallery.ui.components.toast
import com.javaram.gallery.ui.home.BarAction
import com.javaram.gallery.ui.home.LargeHeader
import com.javaram.gallery.ui.theme.Accent
import com.javaram.gallery.ui.theme.AccentDeep
import com.javaram.gallery.ui.theme.GalleryTheme
import com.javaram.gallery.ui.viewer.VideoPage
import com.javaram.gallery.util.duration
import com.javaram.gallery.vault.Vault
import com.javaram.gallery.vault.VaultImage
import com.javaram.gallery.vault.VaultItem
import com.javaram.gallery.vault.VaultThumb
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage

/** Asks for fingerprint, face or the phone's PIN/pattern. */
fun authenticate(context: Context, onSuccess: () -> Unit, onFail: (String) -> Unit) {
    val auth = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val bm = context.getSystemService(BiometricManager::class.java)
    if (bm != null && bm.canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
        onFail("Set up a screen lock (PIN, pattern or fingerprint) to use the Private safe.")
        return
    }
    val prompt = BiometricPrompt.Builder(context)
        .setTitle("Private safe")
        .setSubtitle("Unlock to see your hidden photos")
        .setAllowedAuthenticators(auth)
        .build()
    prompt.authenticate(CancellationSignal(), context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onSuccess()
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
            onFail(errString?.toString() ?: "Couldn't unlock")
        }
    })
}

@Composable
fun SafeScreen(vm: GalleryViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val c = GalleryTheme.colors
    val haze = remember { HazeState() }
    val unlocked by vm.safeUnlocked.collectAsStateWithLifecycle()
    val items by vm.safe.collectAsStateWithLifecycle()
    val busy by vm.safeBusy.collectAsStateWithLifecycle()
    var selectionMode by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    var viewing by remember { mutableStateOf<Long?>(null) }
    val state = rememberLazyGridState()
    val collapsed by remember { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 160 } }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 110.dp

    fun unlock() = authenticate(context, onSuccess = { vm.unlockSafe() }, onFail = { toast(context, it) })
    LaunchedEffect(Unit) { if (!unlocked) unlock() else vm.reloadSafe() }

    fun exitSelection() { selectionMode = false; selection = emptySet() }
    BackHandler(selectionMode) { exitSelection() }

    val selected = remember(selection, items) { items.filter { it.id in selection } }

    Box(Modifier.fillMaxSize().background(c.background)) {
        if (!unlocked) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(84.dp).background(Brush.linearGradient(listOf(Accent, AccentDeep)), RoundedCornerShape(26.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.size(40.dp)) }
                Spacer(Modifier.height(18.dp))
                Text("Private safe", style = MaterialTheme.typography.headlineSmall, color = c.content)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Hidden photos are encrypted and kept only on this phone.",
                    style = MaterialTheme.typography.bodyMedium, color = c.secondary, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    Modifier.height(52.dp).background(Accent, CircleShape).pressable { unlock() }.padding(horizontal = 26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Fingerprint, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Unlock", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
            Box(Modifier.statusBarsPadding().padding(12.dp)) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(c.card).pressable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = c.content) }
            }
        } else {

        Box(Modifier.fillMaxSize().haze(haze)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = state,
                contentPadding = PaddingValues(bottom = bottom),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        LargeHeader("Private safe", "${items.size} hidden · encrypted on this phone")
                        Row(
                            Modifier
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(c.card)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Lock, null, tint = Accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Restore photos before uninstalling Gallery or resetting your phone — the safe can't be recovered otherwise.",
                                style = MaterialTheme.typography.bodySmall, color = c.secondary,
                            )
                        }
                        if (items.isEmpty()) {
                            Text(
                                "Nothing here yet. Select photos anywhere in Gallery and choose Hide to move them in.",
                                style = MaterialTheme.typography.bodyMedium, color = c.secondary, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                            )
                        }
                    }
                }
                items(items, key = { it.id }) { item ->
                    val sel = item.id in selection
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    if (selectionMode) selection = if (sel) selection - item.id else selection + item.id
                                    else viewing = item.id
                                },
                                onLongClick = { selectionMode = true; selection = selection + item.id },
                            )
                    ) {
                        AsyncImage(
                            model = VaultThumb(item.thumb),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().background(c.card),
                        )
                        if (item.isVideo) {
                            Row(Modifier.align(Alignment.BottomEnd).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
                                Text(duration(item.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (selectionMode) {
                            if (sel) Icon(
                                Icons.Filled.CheckCircle, null, tint = Accent,
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).background(Color.White, CircleShape),
                            ) else Box(
                                Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).border(1.5.dp, Color.White, CircleShape)
                            )
                        }
                    }
                }
            }
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
                    "${selection.size} selected", style = MaterialTheme.typography.titleMedium, color = c.content,
                    modifier = Modifier.weight(1f).padding(start = 14.dp),
                )
            } else {
                GlassIconButton(haze, Icons.AutoMirrored.Outlined.ArrowBack, "Back", size = 42.dp, onClick = onBack)
                Spacer(Modifier.weight(1f))
                GlassIconButton(haze, Icons.Filled.Lock, "Lock", size = 42.dp) { vm.lockSafe(); onBack() }
            }
        }

        AnimatedVisibility(
            visible = selectionMode,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            GlassSurface(haze, Modifier.height(66.dp), shape = CircleShape) {
                Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val enabled = selection.isNotEmpty()
                    BarAction(Icons.Outlined.LockOpen, "Unhide", enabled, {
                        val targets = selected
                        confirm = ConfirmRequest(
                            "Unhide ${targets.size} item${if (targets.size == 1) "" else "s"}?",
                            "They'll go back to their original albums.", "Unhide", destructive = false,
                        ) {
                            vm.restoreFromSafe(targets) { n -> toast(context, "$n restored") }
                            exitSelection()
                        }
                    })
                    BarAction(Icons.Outlined.DeleteForever, "Delete", enabled, {
                        val targets = selected
                        confirm = ConfirmRequest(
                            "Delete permanently?", "These items will be erased from the safe and can't be recovered.", "Delete",
                        ) {
                            vm.deleteFromSafe(targets)
                            exitSelection()
                        }
                    }, tint = Color(0xFFFF453A))
                }
            }
        }

        val v = viewing
        if (v != null) {
            SafeViewer(
                items = items,
                startId = v,
                onClose = { viewing = null; Vault.clearPlayback(context) },
                onUnhide = { item ->
                    confirm = ConfirmRequest("Unhide this item?", "It will go back to its original album.", "Unhide", destructive = false) {
                        vm.restoreFromSafe(listOf(item)) { n -> if (n > 0) toast(context, "Restored") }
                    }
                },
                onDelete = { item ->
                    confirm = ConfirmRequest("Delete permanently?", "This item will be erased and can't be recovered.", "Delete") {
                        vm.deleteFromSafe(listOf(item))
                    }
                },
            )
        }

        busy?.let { text ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent)
                    Spacer(Modifier.height(10.dp))
                    Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        GlassDialog(request = confirm, state = haze, onDismiss = { confirm = null })
        }
    }
}

@Composable
private fun SafeViewer(
    items: List<VaultItem>,
    startId: Long,
    onClose: () -> Unit,
    onUnhide: (VaultItem) -> Unit,
    onDelete: (VaultItem) -> Unit,
) {
    val context = LocalContext.current
    val haze = remember { HazeState() }
    val start = remember { items.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
    val pager = rememberPagerState(start) { items.size }
    var chrome by remember { mutableStateOf(true) }
    BackHandler { onClose() }
    LaunchedEffect(items.isEmpty()) { if (items.isEmpty()) onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, key = { items.getOrNull(it)?.id ?: it }, modifier = Modifier.fillMaxSize().haze(haze)) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            if (item.isVideo) {
                val file by produceState<Uri?>(null, item.id, pager.currentPage) {
                    value = if (pager.currentPage == page) Uri.fromFile(Vault.playable(context, item)) else null
                }
                val f = file
                if (f == null) {
                    AsyncImage(VaultThumb(item.thumb), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else {
                    VideoPage(
                        media = Media(
                            id = item.id, uri = f, name = item.name, mimeType = item.mime, dateTaken = item.dateTaken,
                            dateModified = 0, size = item.size, width = item.width, height = item.height,
                            durationMs = item.durationMs, bucketId = 0, bucketName = "", relativePath = "", isFavorite = false,
                        ),
                        isCurrent = true,
                        chromeVisible = chrome,
                        onTap = { chrome = !chrome },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                ZoomableAsyncImage(
                    model = VaultImage(item.file),
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(),
                    onClick = { chrome = !chrome },
                )
            }
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.statusBarsPadding().padding(12.dp)) {
                GlassIconButton(haze, Icons.AutoMirrored.Outlined.ArrowBack, "Back", size = 44.dp, tint = Color.White, onClick = onClose)
            }
        }
        AnimatedVisibility(
            chrome,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            GlassSurface(haze, Modifier.navigationBarsPadding().padding(bottom = 14.dp).height(66.dp), shape = CircleShape) {
                Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val cur = items.getOrNull(pager.currentPage)
                    BarAction(Icons.Outlined.LockOpen, "Unhide", cur != null, { cur?.let(onUnhide) }, tint = Color.White)
                    BarAction(Icons.Outlined.DeleteForever, "Delete", cur != null, { cur?.let(onDelete) }, tint = Color(0xFFFF453A))
                }
            }
        }
    }
}

/**
 * Returns an action that moves photos into the Private safe: encrypt copies first, then
 * delete the originals (undoing the copies if the deletion is refused).
 */
@Composable
fun rememberHideInSafe(vm: GalleryViewModel, onDone: () -> Unit = {}): (List<Media>) -> Unit {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<Long>>(emptyList()) }
    val request = com.javaram.gallery.ui.components.rememberMediaRequest { ok ->
        if (ok) toast(context, "Moved to Private safe")
        else {
            vm.undoAddToSafe(pending)
            toast(context, "Kept in your gallery")
        }
        pending = emptyList()
        vm.scheduleRefresh(0)
        onDone()
    }
    return remember(vm) {
        { items: List<Media> ->
            if (items.isNotEmpty()) {
                toast(context, "Moving ${items.size} to Private safe…")
                scope.launch {
                    val ids = vm.addToSafe(items)
                    if (ids.isEmpty()) {
                        toast(context, "Couldn't move these to the safe")
                    } else {
                        pending = ids
                        val moved = items.take(ids.size)
                        request(vm.deleteForeverRequest(moved))
                    }
                }
            }
        }
    }
}
