package com.javaram.gallery.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.javaram.gallery.UpdateState
import com.javaram.gallery.ui.components.GlassSurface
import com.javaram.gallery.ui.components.glassStyle
import com.javaram.gallery.ui.components.noRippleClickable
import com.javaram.gallery.ui.components.pressable
import com.javaram.gallery.ui.components.toast
import com.javaram.gallery.ui.theme.Accent
import com.javaram.gallery.ui.theme.AccentDeep
import com.javaram.gallery.ui.theme.GalleryTheme
import com.javaram.gallery.update.UpdateInfo
import com.javaram.gallery.util.fileSize
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild

@Composable
fun UpdateDialog(
    state: UpdateState,
    haze: HazeState,
    onUpdate: (UpdateInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val c = GalleryTheme.colors

    // Short states become toasts rather than dialogs.
    LaunchedEffect(state) {
        when (state) {
            UpdateState.UpToDate -> { toast(context, "You're on the latest version"); onDismiss() }
            is UpdateState.Error -> { toast(context, state.message); onDismiss() }
            UpdateState.Checking -> toast(context, "Checking for updates…")
            else -> Unit
        }
    }

    val info: UpdateInfo? = when (state) {
        is UpdateState.Available -> state.info
        is UpdateState.NeedsPermission -> state.info
        is UpdateState.Downloading -> state.info
        is UpdateState.Installing -> state.info
        else -> null
    }
    var lastInfo by remember { mutableStateOf(info) }
    if (info != null) lastInfo = info
    val visible = info != null
    val busy = state is UpdateState.Downloading || state is UpdateState.Installing
    val progress by animateFloatAsState(
        ((state as? UpdateState.Downloading)?.progress ?: if (state is UpdateState.Installing) 1f else 0f).coerceAtLeast(0f),
        tween(200), label = "progress",
    )

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .hazeChild(haze, glassStyle(blur = 18.dp, tint = Color.Black.copy(alpha = 0.28f)))
                    .noRippleClickable { if (!busy) onDismiss() }
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.Center),
            enter = scaleIn(spring(dampingRatio = 0.7f, stiffness = 500f), initialScale = 0.8f) + fadeIn(),
            exit = scaleOut(targetScale = 0.9f) + fadeOut(),
        ) {
            val i = lastInfo ?: return@AnimatedVisibility
            GlassSurface(
                haze,
                Modifier.padding(horizontal = 32.dp).fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
            ) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(58.dp)
                            .background(Brush.linearGradient(listOf(Accent, AccentDeep)), RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.SystemUpdate, null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Update available", style = MaterialTheme.typography.titleMedium, color = c.content)
                    Text(
                        "${i.versionName} · ${fileSize(i.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.secondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when (state) {
                            is UpdateState.NeedsPermission ->
                                "Allow Gallery to install updates in the screen that opened, then come back."
                            is UpdateState.Downloading ->
                                if (state.progress < 0f) "Connecting to GitHub…"
                                else "Downloading… ${(progress * 100).toInt()}%"
                            is UpdateState.Installing -> "Installing — Gallery will restart."
                            else -> "A new version of Gallery is ready. It installs right here, no GitHub needed."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.secondary,
                        textAlign = TextAlign.Center,
                    )
                    val news = remember(i.notes) {
                        i.notes.lines().map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ") }.take(9)
                    }
                    if (news.isNotEmpty() && state is UpdateState.Available) {
                        Spacer(Modifier.height(14.dp))
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(c.glassHighlight)
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Text("What's new", style = MaterialTheme.typography.titleSmall, color = c.content)
                            news.forEach { line ->
                                Text(
                                    "•  $line",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.secondary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    if (busy) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(CircleShape)
                                .background(c.glassHighlight)
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                                    .height(8.dp)
                                    .background(Brush.horizontalGradient(listOf(Accent, AccentDeep)), CircleShape)
                            )
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .background(c.glassHighlight, CircleShape)
                                    .pressable(onClick = onDismiss),
                                contentAlignment = Alignment.Center,
                            ) { Text("Later", style = MaterialTheme.typography.titleSmall, color = c.content) }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .background(Brush.horizontalGradient(listOf(Accent, AccentDeep)), CircleShape)
                                    .pressable { onUpdate(i) },
                                contentAlignment = Alignment.Center,
                            ) { Text("Update", style = MaterialTheme.typography.titleSmall, color = Color.White) }
                        }
                    }
                }
            }
        }
    }
}
