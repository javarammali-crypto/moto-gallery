package com.javaram.gallery.ui.viewer

import android.graphics.Color as AndroidColor
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.javaram.gallery.data.Media
import com.javaram.gallery.data.thumb
import com.javaram.gallery.ui.components.noRippleClickable
import com.javaram.gallery.ui.components.pressable
import com.javaram.gallery.ui.theme.Accent
import com.javaram.gallery.util.duration
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun VideoPage(
    media: Media,
    isCurrent: Boolean,
    chromeVisible: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isCurrent) {
        Box(modifier, contentAlignment = Alignment.Center) {
            AsyncImage(
                model = media.thumb(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Icon(
                Icons.Filled.PlayArrow, null, tint = Color.White,
                modifier = Modifier.size(64.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape).padding(14.dp),
            )
        }
        return
    }

    val context = LocalContext.current
    val player = remember(media.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoItem.fromUri(media.uri))
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var length by remember { mutableLongStateOf(media.durationMs) }
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(player) {
        while (true) {
            if (!seeking) position = player.currentPosition
            if (player.duration > 0) length = player.duration
            playing = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
            if (player.playbackState == Player.STATE_ENDED) playing = false
            delay(120)
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().noRippleClickable(onTap))

        AnimatedVisibility(
            visible = chromeVisible || !playing,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (playing) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier
                    .size(68.dp)
                    .background(Color.Black.copy(alpha = 0.38f), CircleShape)
                    .pressable {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                        if (player.isPlaying) player.pause() else player.play()
                    }
                    .padding(16.dp),
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(
                Modifier
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 92.dp)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.38f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(duration(if (seeking) seekValue.toLong() else position), color = Color.White, fontSize = 12.sp)
                Slider(
                    value = (if (seeking) seekValue else position.toFloat()).coerceIn(0f, length.coerceAtLeast(1L).toFloat()),
                    onValueChange = { seeking = true; seekValue = it },
                    onValueChangeFinished = {
                        player.seekTo(seekValue.toLong())
                        position = seekValue.toLong()
                        seeking = false
                    },
                    valueRange = 0f..length.coerceAtLeast(1L).toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Accent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                Text(duration(length), color = Color.White, fontSize = 12.sp)
            }
        }
    }
}
