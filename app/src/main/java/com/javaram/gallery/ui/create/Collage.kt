package com.javaram.gallery.ui.create

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.javaram.gallery.GalleryViewModel
import com.javaram.gallery.data.Media
import com.javaram.gallery.data.thumb
import com.javaram.gallery.data.thumbKey
import com.javaram.gallery.editor.EditEngine
import com.javaram.gallery.ui.components.GlassIconButton
import com.javaram.gallery.ui.components.GlassSurface
import com.javaram.gallery.ui.components.GroupMode
import com.javaram.gallery.ui.components.MediaGrid
import com.javaram.gallery.ui.components.ProgressiveGlass
import com.javaram.gallery.ui.components.pressable
import com.javaram.gallery.ui.components.toast
import com.javaram.gallery.ui.home.LargeHeader
import com.javaram.gallery.ui.theme.Accent
import com.javaram.gallery.ui.theme.GalleryTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Normalised (0..1) cell rectangles. */
private typealias Layout = List<RectF>

private fun rows(vararg counts: Int): Layout {
    val out = ArrayList<RectF>()
    val h = 1f / counts.size
    counts.forEachIndexed { r, n ->
        val w = 1f / n
        for (c in 0 until n) out += RectF(c * w, r * h, (c + 1) * w, (r + 1) * h)
    }
    return out
}

private fun cols(vararg counts: Int): Layout = rows(*counts).map { RectF(it.top, it.left, it.bottom, it.right) }

private fun bigLeft(n: Int): Layout {
    val rest = n - 1
    val out = arrayListOf(RectF(0f, 0f, 0.6f, 1f))
    val h = 1f / rest
    for (i in 0 until rest) out += RectF(0.6f, i * h, 1f, (i + 1) * h)
    return out
}

private fun bigTop(n: Int): Layout {
    val rest = n - 1
    val out = arrayListOf(RectF(0f, 0f, 1f, 0.6f))
    val w = 1f / rest
    for (i in 0 until rest) out += RectF(i * w, 0.6f, (i + 1) * w, 1f)
    return out
}

private fun grid(n: Int): Layout {
    val c = ceil(sqrt(n.toDouble())).toInt()
    val r = ceil(n / c.toDouble()).toInt()
    val counts = IntArray(r) { c }
    counts[r - 1] = n - c * (r - 1)
    return rows(*counts)
}

fun collageLayouts(n: Int): List<Layout> = when (n) {
    1 -> listOf(rows(1))
    2 -> listOf(rows(2), rows(1, 1), bigLeft(2))
    3 -> listOf(bigLeft(3), rows(3), bigTop(3), rows(1, 1, 1))
    4 -> listOf(rows(2, 2), bigTop(4), bigLeft(4), rows(1, 3))
    5 -> listOf(rows(2, 3), rows(3, 2), bigTop(5), cols(2, 3))
    6 -> listOf(rows(3, 3), rows(2, 2, 2), rows(1, 2, 3), cols(3, 3))
    7 -> listOf(rows(3, 4), rows(2, 3, 2), rows(1, 3, 3), grid(7))
    8 -> listOf(rows(4, 4), rows(3, 2, 3), rows(2, 2, 2, 2), grid(8))
    else -> listOf(rows(3, 3, 3), grid(n), cols(3, 3, 3))
}.map { it.take(n) }

private data class Ratio(val label: String, val value: Float)
private val ratios = listOf(Ratio("1:1", 1f), Ratio("4:5", 0.8f), Ratio("3:4", 0.75f), Ratio("9:16", 9f / 16f), Ratio("16:9", 16f / 9f))
private val bgColors = listOf(Color.White, Color(0xFF111114), Color(0xFFFFE9D6), Color(0xFFDDEBFF), Color(0xFFE6F6EC), Color(0xFFFFE1EA), Accent)

@Composable
fun CollageScreen(vm: GalleryViewModel, onBack: () -> Unit) {
    val all by vm.all.collectAsStateWithLifecycle()
    val photos = remember(all) { all.filter { !it.isVideo } }
    var picked by remember { mutableStateOf<List<Long>>(emptyList()) }
    var step by remember { mutableIntStateOf(0) }
    val byId = remember(all) { all.associateBy { it.id } }

    if (step == 0) {
        CollagePicker(
            photos = photos,
            picked = picked,
            columns = vm.columns.collectAsStateWithLifecycle().value,
            onPick = { picked = it },
            onBack = onBack,
            onNext = { step = 1 },
        )
    } else {
        BackHandler { step = 0 }
        CollageEditor(items = picked.mapNotNull { byId[it] }, vm = vm, onBack = { step = 0 }, onDone = onBack)
    }
}

@Composable
private fun CollagePicker(
    photos: List<Media>,
    picked: List<Long>,
    columns: Int,
    onPick: (List<Long>) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    val c = GalleryTheme.colors
    val haze = remember { HazeState() }
    val state = rememberLazyGridState()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 110.dp
    Box(Modifier.fillMaxSize().background(c.background)) {
        Box(Modifier.fillMaxSize().haze(haze)) {
            MediaGrid(
                items = photos,
                columns = columns,
                group = GroupMode.Day,
                state = state,
                selection = picked.toSet(),
                selectionMode = true,
                onSelectionChange = { sel ->
                    // Keep pick order; cap at 9 photos.
                    val kept = picked.filter { it in sel }
                    val added = sel.filter { it !in picked }
                    val next = (kept + added)
                    if (next.size > 9) toast(context, "A collage can have up to 9 photos")
                    onPick(next.take(9))
                },
                onEnterSelection = {},
                onOpen = {},
                contentPadding = PaddingValues(bottom = bottom),
                header = { LargeHeader("New collage", "Choose 2 to 9 photos") },
            )
        }
        ProgressiveGlass(haze, Modifier.fillMaxWidth().height(110.dp)) {}
        Row(Modifier.statusBarsPadding().padding(12.dp)) {
            GlassIconButton(haze, Icons.AutoMirrored.Outlined.ArrowBack, "Back", size = 42.dp, onClick = onBack)
        }
        AnimatedVisibility(
            picked.size >= 2,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            Box(
                Modifier.height(56.dp).background(Accent, CircleShape).pressable(onClick = onNext).padding(horizontal = 30.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Next · ${picked.size} photos", color = Color.White, style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun CollageEditor(items: List<Media>, vm: GalleryViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val c = GalleryTheme.colors
    val scope = rememberCoroutineScope()
    val haze = remember { HazeState() }
    val layouts = remember(items.size) { collageLayouts(items.size) }
    var layoutIdx by remember { mutableIntStateOf(0) }
    var ratio by remember { mutableStateOf(ratios[0]) }
    var spacing by remember { mutableFloatStateOf(0.35f) }
    var corner by remember { mutableFloatStateOf(0.35f) }
    var bg by remember { mutableStateOf(Color.White) }
    var saving by remember { mutableStateOf(false) }
    val layout = layouts[layoutIdx.coerceIn(0, layouts.lastIndex)]

    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize().haze(haze).statusBarsPadding().navigationBarsPadding()) {
            Spacer(Modifier.height(64.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                val maxW = constraints.maxWidth.toFloat()
                val maxH = constraints.maxHeight.toFloat()
                val (w, h) = EditEngine.fitInside((ratio.value * 1000).roundToInt(), 1000, maxW, maxH)
                val density = LocalDensity.current
                with(density) {
                    Box(Modifier.size(w.toDp(), h.toDp()).background(bg)) {
                        val gap = spacing * 0.04f * max(w, h)
                        val rad = corner * 0.06f * max(w, h)
                        layout.zip(items).forEach { (r, m) ->
                            val x = gap + r.left * (w - gap)
                            val y = gap + r.top * (h - gap)
                            val cw = r.width() * (w - gap) - gap
                            val ch = r.height() * (h - gap) - gap
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(m.uri).size(900)
                                    .placeholderMemoryCacheKey(thumbKey(m.thumb())).build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .offset(x.toDp(), y.toDp())
                                    .size(cw.coerceAtLeast(1f).toDp(), ch.coerceAtLeast(1f).toDp())
                                    .clip(RoundedCornerShape(rad.toDp())),
                            )
                        }
                    }
                }
            }
            // Controls
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(layouts) { i, l ->
                        val sel = i == layoutIdx
                        Canvas(
                            Modifier
                                .size(54.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(c.card)
                                .border(if (sel) 2.dp else 0.dp, if (sel) Accent else Color.Transparent, RoundedCornerShape(12.dp))
                                .pressable { layoutIdx = i }
                        ) {
                            val p = 6.dp.toPx()
                            l.forEach { r ->
                                drawRoundRect(
                                    if (sel) Accent else c.secondary,
                                    Offset(p + r.left * (size.width - 2 * p) + 1.5f, p + r.top * (size.height - 2 * p) + 1.5f),
                                    Size(r.width() * (size.width - 2 * p) - 3f, r.height() * (size.height - 2 * p) - 3f),
                                    CornerRadius(3f, 3f),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ratios) { r ->
                        val sel = r == ratio
                        Text(
                            r.label,
                            color = if (sel) Color.White else c.content,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.clip(CircleShape).background(if (sel) Accent else c.card).pressable { ratio = r }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                    items(bgColors) { col ->
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(col)
                                .border(if (col == bg) 2.5.dp else 1.dp, if (col == bg) Accent else c.divider, CircleShape)
                                .pressable { bg = col }
                        )
                    }
                }
                LabeledSlider("Spacing", spacing) { spacing = it }
                LabeledSlider("Corners", corner) { corner = it }
            }
        }

        Row(
            Modifier.statusBarsPadding().fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(haze, Icons.AutoMirrored.Outlined.ArrowBack, "Back", size = 42.dp, onClick = onBack)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.height(42.dp).background(Accent, CircleShape).pressable(enabled = !saving) {
                    saving = true
                    scope.launch {
                        val ok = runCatching {
                            renderCollage(context, items, layout, ratio.value, spacing, corner, bg.toArgb())
                        }.getOrDefault(false)
                        saving = false
                        toast(context, if (ok) "Collage saved to Pictures › Collages" else "Couldn't save the collage")
                        if (ok) { vm.scheduleRefresh(0); onDone() }
                    }
                }.padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (saving) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else Text("Save", color = Color.White, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    val c = GalleryTheme.colors
    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.secondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(70.dp))
        Slider(
            value = value, onValueChange = onChange,
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
            modifier = Modifier.weight(1f),
        )
    }
}

private suspend fun renderCollage(
    context: Context,
    items: List<Media>,
    layout: List<RectF>,
    ratio: Float,
    spacing: Float,
    corner: Float,
    bg: Int,
): Boolean = withContext(Dispatchers.Default) {
    val w = if (ratio >= 1f) 3000 else (3000 * ratio).roundToInt()
    val h = if (ratio >= 1f) (3000 / ratio).roundToInt() else 3000
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(bg)
    val gap = spacing * 0.04f * max(w, h)
    val rad = corner * 0.06f * max(w, h)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    layout.forEachIndexed { i, r ->
        val m = items.getOrNull(i) ?: return@forEachIndexed
        val dst = RectF(
            gap + r.left * (w - gap), gap + r.top * (h - gap),
            gap + r.left * (w - gap) + r.width() * (w - gap) - gap, gap + r.top * (h - gap) + r.height() * (h - gap) - gap,
        )
        val bmp = EditEngine.load(context, m.uri, (max(dst.width(), dst.height()) * 1.2f).roundToInt().coerceAtLeast(400))
        // Centre-crop into the cell.
        val cellRatio = dst.width() / dst.height()
        val srcRatio = bmp.width.toFloat() / bmp.height
        val src = if (srcRatio > cellRatio) {
            val sw = (bmp.height * cellRatio).roundToInt()
            Rect((bmp.width - sw) / 2, 0, (bmp.width + sw) / 2, bmp.height)
        } else {
            val sh = (bmp.width / cellRatio).roundToInt()
            Rect(0, (bmp.height - sh) / 2, bmp.width, (bmp.height + sh) / 2)
        }
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(dst, rad, rad, Path.Direction.CW) })
        canvas.drawBitmap(bmp, src, dst, paint)
        canvas.restore()
        bmp.recycle()
    }
    saveNewImage(context, out, "Collage_${System.currentTimeMillis() / 1000}.jpg", "Pictures/Collages/")
}

suspend fun saveNewImage(context: Context, bmp: Bitmap, name: String, path: String): Boolean = withContext(Dispatchers.IO) {
    val uri = context.contentResolver.insert(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        },
    ) ?: return@withContext false
    context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    true
}
