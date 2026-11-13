package com.javaram.gallery.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.javaram.gallery.GalleryViewModel
import com.javaram.gallery.data.ExifDetails
import com.javaram.gallery.data.Media
import com.javaram.gallery.ui.components.GlassSurface
import com.javaram.gallery.ui.components.glassStyle
import com.javaram.gallery.ui.components.noRippleClickable
import com.javaram.gallery.ui.components.openMaps
import com.javaram.gallery.ui.theme.Accent
import com.javaram.gallery.ui.theme.GalleryTheme
import com.javaram.gallery.util.duration
import com.javaram.gallery.util.fileSize
import com.javaram.gallery.util.fullDateTime
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import java.util.Locale
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Details sheet that rises from the bottom. [progress] runs from 0 (hidden) to 1 (open) and
 * follows the finger while swiping, so the photo and the sheet move together.
 */
@Composable
fun InfoPanel(
    progress: Float,
    media: Media?,
    vm: GalleryViewModel,
    haze: HazeState,
    onHeight: (Int) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
) {
    val context = LocalContext.current
    val c = GalleryTheme.colors
    val density = androidx.compose.ui.platform.LocalDensity.current
    var height by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val drop = with(density) { 40.dp.toPx() }
    Box(Modifier.fillMaxSize()) {
        if (progress > 0.001f) {
            val m = media ?: return@Box
            val exif by produceState<ExifDetails?>(null, m.id) { value = vm.exif(m) }
            GlassSurface(
                haze,
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(12.dp)
                    .fillMaxWidth()
                    .onSizeChanged { height = it.height; onHeight(it.height) }
                    .graphicsLayer {
                        val p = progress.coerceIn(0f, 1f)
                        translationY = (1f - p) * (height + drop)
                        alpha = (p * 2.5f).coerceAtMost(1f)
                    }
                    .pointerInput(Unit) {
                        val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                        detectVerticalDragGestures(
                            onDragStart = { tracker.resetTracking() },
                            onDragEnd = { onDragEnd(tracker.calculateVelocity().y) },
                            onDragCancel = { onDragEnd(0f) },
                        ) { change, dy ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            onDrag(dy)
                        }
                    },
                shape = RoundedCornerShape(30.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(36.dp)
                            .height(4.dp)
                            .background(c.secondary.copy(alpha = 0.4f), RoundedCornerShape(50))
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("Details", style = MaterialTheme.typography.headlineSmall, color = c.content)
                    Spacer(Modifier.height(8.dp))

                    InfoRow(Icons.Outlined.Schedule, fullDateTime(m.dateTaken), null)
                    val dims = if (m.width > 0 && m.height > 0) {
                        val mp = m.width.toLong() * m.height / 1_000_000.0
                        "${m.width} × ${m.height}" + if (!m.isVideo && mp >= 0.5) String.format(Locale.US, " · %.1f MP", mp) else ""
                    } else null
                    InfoRow(
                        Icons.Outlined.Image,
                        m.name,
                        listOfNotNull(dims, fileSize(m.size), if (m.isVideo) duration(m.durationMs) else null).joinToString(" · "),
                    )
                    val e = exif
                    if (e != null && (e.model != null || e.make != null)) {
                        val camera = listOfNotNull(e.make, e.model).distinct().joinToString(" ")
                        val settings = listOfNotNull(
                            e.fNumber?.let { "ƒ/$it" },
                            e.exposure?.toDoubleOrNull()?.let { if (it < 1) "1/${(1 / it).toInt()}s" else "${it}s" },
                            e.iso?.let { "ISO $it" },
                            e.focalLength?.let { String.format(Locale.US, "%.1f mm", it) },
                        ).joinToString(" · ").ifEmpty { null }
                        InfoRow(Icons.Outlined.CameraAlt, camera, settings)
                    }
                    InfoRow(Icons.Outlined.Folder, m.bucketName, m.relativePath.ifEmpty { null })
                    val ll = e?.latLong
                    if (ll != null) {
                        InfoRow(
                            Icons.Outlined.LocationOn,
                            String.format(Locale.US, "%.5f, %.5f", ll.first, ll.second),
                            "Tap to open in Maps",
                            highlight = true,
                            onClick = { openMaps(context, ll.first, ll.second) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    highlight: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = GalleryTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.noRippleClickable(onClick) else Modifier)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (highlight) Accent else c.secondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.content)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (highlight) Accent else c.secondary)
        }
    }
}
