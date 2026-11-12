package com.javaram.gallery.ui.external

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.javaram.gallery.data.Media
import com.javaram.gallery.ui.components.GlassIconButton
import com.javaram.gallery.ui.viewer.VideoPage
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage

/** Opens a single photo or video handed over by another app (camera preview, file manager, chat). */
@Composable
fun ExternalViewer(uri: Uri, mime: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val haze = remember { HazeState() }
    var chrome by remember { mutableStateOf(true) }
    val type = mime ?: context.contentResolver.getType(uri) ?: "image/*"
    val isVideo = type.startsWith("video")

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().haze(haze)) {
            if (isVideo) {
                val media = remember(uri) {
                    Media(
                        id = uri.hashCode().toLong(), uri = uri, name = "", mimeType = type,
                        dateTaken = 0, dateModified = 0, size = 0, width = 0, height = 0, durationMs = 0,
                        bucketId = 0, bucketName = "", relativePath = "", isFavorite = false,
                    )
                }
                VideoPage(media, isCurrent = true, chromeVisible = chrome, onTap = { chrome = !chrome }, modifier = Modifier.fillMaxSize())
            } else {
                ZoomableAsyncImage(
                    model = uri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    onClick = { chrome = !chrome },
                )
            }
        }
        if (chrome) {
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(12.dp)) {
                GlassIconButton(haze, Icons.AutoMirrored.Outlined.ArrowBack, "Close", size = 44.dp, onClick = onClose)
                Spacer(Modifier.weight(1f))
                GlassIconButton(haze, Icons.Outlined.Share, "Share", size = 44.dp) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        this.type = type
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(Intent.createChooser(send, null)) }
                }
            }
        }
    }
}
