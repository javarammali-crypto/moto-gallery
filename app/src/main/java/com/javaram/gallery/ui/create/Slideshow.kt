package com.javaram.gallery.ui.create

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.javaram.gallery.data.Media
import com.javaram.gallery.data.thumb
import com.javaram.gallery.data.thumbKey
import com.javaram.gallery.ui.components.GlassIconButton
import com.javaram.gallery.ui.components.pressable
import com.javaram.gallery.util.fullDateTime
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

private const val SLIDE_MS = 3800L

/** Full-screen story-style slideshow with a slow zoom-and-drift on every photo. */
@Composable
fun SlideshowScreen(title: String, items: List<Media>, onClose: () -> Unit) {
    val context = LocalContext.current
    val haze = remember { HazeState() }
    var index by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var elapsed by remember { mutableFloatStateOf(0f) }
    BackHandler { onClose() }

    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    // Timer: advances slides and drives the progress bar.
    LaunchedEffect(index, paused) {
        if (paused) return@LaunchedEffect
        val start = withFrameMillis { it } - (elapsed * SLIDE_MS).toLong()
        while (true) {
            val now = withFrameMillis { it }
            elapsed = ((now - start).toFloat() / SLIDE_MS).coerceIn(0f, 1f)
            if (elapsed >= 1f) {
                elapsed = 0f
                if (index < items.lastIndex) index++ else { paused = true; chrome = true }
                break
            }
        }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(2500); chrome = false }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(items.size) {
                detectTapGestures { p ->
                    when {
                        p.x < size.width * 0.28f -> { if (index > 0) index--; elapsed = 0f }
                        p.x > size.width * 0.72f -> { if (index < items.lastIndex) index++; elapsed = 0f }
                        else -> chrome = !chrome
                    }
                }
            }
    ) {
        Box(Modifier.fillMaxSize().haze(haze)) {
            Crossfade(targetState = index, animationSpec = tween(900), label = "slide") { i ->
                val m = items[i]
                val zoom = remember(i) { Animatable(1f) }
                LaunchedEffect(i) { zoom.animateTo(1.14f, tween((SLIDE_MS + 1200).toInt(), easing = LinearEasing)) }
                val model = remember(m.id) {
                    ImageRequest.Builder(context).data(m.uri).size(1600).placeholderMemoryCacheKey(thumbKey(m.thumb())).build()
                }
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom.value
                            scaleY = zoom.value
                            val drift = (zoom.value - 1f) * size.width * 0.35f
                            translationX = if (i % 2 == 0) drift else -drift
                            translationY = if (i % 3 == 0) -drift * 0.5f else drift * 0.3f
                        },
                )
            }
            // Warm up the next photo so it's ready.
            items.getOrNull(index + 1)?.let { next ->
                AsyncImage(
                    model = ImageRequest.Builder(context).data(next.uri).size(1600).build(),
                    contentDescription = null,
                    modifier = Modifier.size(1.dp).graphicsLayer { alpha = 0f },
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
        )

        Column(Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Story-style progress segments.
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
                val segments = items.size.coerceAtMost(40)
                val per = items.size.toFloat() / segments
                for (s in 0 until segments) {
                    val segStart = s * per
                    val pos = index + elapsed
                    val fill = ((pos - segStart) / per).coerceIn(0f, 1f)
                    Box(
                        Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))
                    ) {
                        Box(Modifier.fillMaxWidth(fill).height(3.dp).background(Color.White))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(fullDateTime(items[index].dateTaken), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
        }

        AnimatedVisibility(
            chrome,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(haze, Icons.Filled.Close, "Close", size = 54.dp, tint = Color.White, onClick = onClose)
                GlassIconButton(
                    haze,
                    if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    if (paused) "Play" else "Pause",
                    size = 64.dp,
                    tint = Color.White,
                ) {
                    if (paused && index == items.lastIndex && elapsed == 0f) index = 0
                    paused = !paused
                }
                Spacer(Modifier.width(54.dp))
            }
        }
    }
}
