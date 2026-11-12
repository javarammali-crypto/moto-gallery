package com.javaram.gallery.ui.search

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.util.Size
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javaram.gallery.GalleryViewModel
import com.javaram.gallery.search.PlaceCluster
import com.javaram.gallery.search.SearchEngine
import com.javaram.gallery.ui.components.GlassIconButton
import com.javaram.gallery.ui.components.GlassSurface
import com.javaram.gallery.ui.theme.GalleryTheme
import com.javaram.gallery.util.countLabel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay

@Composable
fun PlacesMapScreen(vm: GalleryViewModel, onBack: () -> Unit, onPlace: (String) -> Unit) {
    val context = LocalContext.current
    val c = GalleryTheme.colors
    val haze = remember { HazeState() }
    val all by vm.all.collectAsStateWithLifecycle()
    val index by vm.index.collectAsStateWithLifecycle()
    val clusters by produceState(emptyList<PlaceCluster>(), all, index) {
        value = withContext(Dispatchers.Default) { SearchEngine.places(all, index) }
    }
    val markerIcons by produceState(emptyMap<String, Bitmap>(), clusters) {
        value = withContext(Dispatchers.IO) {
            clusters.take(80).associate { it.name to markerBitmap(context, it) }.filterValues { it != null }
                .mapValues { it.value!! }
        }
    }
    var map by remember { mutableStateOf<MapView?>(null) }
    var framed by remember { mutableStateOf(false) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, map) {
        val m = map
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> m?.onResume()
                Lifecycle.Event.ON_PAUSE -> m?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
        }
    }
    DisposableEffect(Unit) { onDispose { map?.onDetach() } }

    // Place a photo marker for every place.
    LaunchedEffect(map, clusters, markerIcons) {
        val m = map ?: return@LaunchedEffect
        m.overlays.removeAll { it is Marker }
        clusters.take(80).forEach { cl ->
            val marker = Marker(m).apply {
                position = GeoPoint(cl.lat, cl.lng)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = cl.name
                markerIcons[cl.name]?.let { icon = BitmapDrawable(context.resources, it) }
                setOnMarkerClickListener { _, _ -> onPlace(cl.name); true }
            }
            m.overlays.add(marker)
        }
        if (!framed && clusters.isNotEmpty()) {
            framed = true
            if (clusters.size == 1) {
                m.controller.setZoom(11.0)
                m.controller.setCenter(GeoPoint(clusters[0].lat, clusters[0].lng))
            } else {
                val box = BoundingBox.fromGeoPoints(clusters.map { GeoPoint(it.lat, it.lng) })
                m.post { runCatching { m.zoomToBoundingBox(box.increaseByScale(1.4f), false, 80) } }
            }
        }
        m.invalidate()
    }

    Box(Modifier.fillMaxSize().background(c.background)) {
        Box(Modifier.fillMaxSize().haze(haze)) {
            AndroidView(
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                        minZoomLevel = 2.5
                        isTilesScaledToDpi = true
                        controller.setZoom(4.0)
                        controller.setCenter(GeoPoint(21.0, 78.0))
                        if (c.dark) overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
                        map = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(
            Modifier.statusBarsPadding().fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(haze, Icons.AutoMirrored.Outlined.ArrowBack, "Back", size = 44.dp, onClick = onBack)
            Spacer(Modifier.width(10.dp))
            GlassSurface(haze, Modifier.height(44.dp), shape = CircleShape) {
                Text(
                    "Places",
                    style = MaterialTheme.typography.titleMedium,
                    color = c.content,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
        }

        GlassSurface(
            haze,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                val photos = clusters.sumOf { it.count }
                Text(
                    if (clusters.isEmpty()) "No places yet"
                    else "${clusters.size} places · ${countLabel(photos)} photos",
                    style = MaterialTheme.typography.titleSmall,
                    color = c.content,
                )
                Text(
                    if (clusters.isEmpty()) "Places show up as photos with location are analysed."
                    else "Tap a photo pin to see everything from that place.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.secondary,
                )
                Text(
                    "Map data © OpenStreetMap contributors",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.secondary.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Rounded photo pin with a count badge, drawn once per place. */
private fun markerBitmap(context: Context, cl: PlaceCluster): Bitmap? {
    val density = context.resources.displayMetrics.density
    val size = (64 * density).toInt()
    val border = 3 * density
    val thumb = runCatching {
        context.contentResolver.loadThumbnail(cl.cover.uri, Size(size, size), null)
    }.getOrNull() ?: return null
    val out = Bitmap.createBitmap(size, (size + 10 * density).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
    val radius = 16 * density

    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    canvas.drawRoundRect(rect, radius, radius, white)
    // little pointer under the card
    val path = android.graphics.Path().apply {
        moveTo(size / 2f - 8 * density, size.toFloat() - 1)
        lineTo(size / 2f, size + 9 * density)
        lineTo(size / 2f + 8 * density, size.toFloat() - 1)
        close()
    }
    canvas.drawPath(path, white)

    val scaled = Bitmap.createScaledBitmap(centerCrop(thumb), (size - 2 * border).toInt(), (size - 2 * border).toInt(), true)
    val shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    val img = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
    canvas.save()
    canvas.translate(border, border)
    canvas.drawRoundRect(RectF(0f, 0f, scaled.width.toFloat(), scaled.height.toFloat()), radius - border, radius - border, img)
    canvas.restore()

    val badgeText = if (cl.count > 999) "999+" else cl.count.toString()
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 11 * density
        typeface = Typeface.DEFAULT_BOLD
    }
    val tw = textPaint.measureText(badgeText)
    val bw = tw + 12 * density
    val bh = 18 * density
    val badge = RectF(size - bw - 4 * density, 4 * density, size - 4 * density, 4 * density + bh)
    canvas.drawRoundRect(badge, bh / 2, bh / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF7A1F.toInt() })
    canvas.drawText(badgeText, badge.left + 6 * density, badge.bottom - 5 * density, textPaint)
    return out
}

private fun centerCrop(b: Bitmap): Bitmap {
    val s = minOf(b.width, b.height)
    return Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s)
}
