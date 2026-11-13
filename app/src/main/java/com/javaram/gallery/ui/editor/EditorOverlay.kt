package com.javaram.gallery.ui.editor

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.RectF
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.material.icons.outlined.HdrStrong
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Exposure
import androidx.compose.material.icons.outlined.Filter
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Tonality
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Vignette
import androidx.compose.material.icons.outlined.WbShade
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.javaram.gallery.GalleryViewModel
import com.javaram.gallery.data.Media
import com.javaram.gallery.editor.Adjust
import com.javaram.gallery.editor.Background
import com.javaram.gallery.editor.EditEngine
import com.javaram.gallery.editor.EditParams
import com.javaram.gallery.editor.Geometry
import com.javaram.gallery.ui.components.ConfirmRequest
import com.javaram.gallery.ui.components.GlassDialog
import com.javaram.gallery.ui.components.GlassPopupMenu
import com.javaram.gallery.ui.components.GlassSurface
import com.javaram.gallery.ui.components.PopupAnchor
import com.javaram.gallery.ui.components.SheetOption
import com.javaram.gallery.ui.components.noRippleClickable
import com.javaram.gallery.ui.components.pressable
import com.javaram.gallery.ui.components.rememberMediaRequest
import com.javaram.gallery.ui.components.toast
import com.javaram.gallery.ui.theme.Accent
import com.javaram.gallery.ui.theme.DarkGallery
import com.javaram.gallery.ui.theme.LocalGalleryColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class Tool(val label: String, val icon: ImageVector) {
    Crop("Crop", Icons.Outlined.Crop),
    Adjust("Adjust", Icons.Outlined.Tune),
    Filter("Filter", Icons.Outlined.Filter),
    Cutout("Background", Icons.Outlined.AutoFixHigh),
}

private enum class AdjustParam(val label: String, val icon: ImageVector, val signed: Boolean = true) {
    Exposure("Exposure", Icons.Outlined.Exposure),
    Brightness("Brightness", Icons.Outlined.LightMode),
    Contrast("Contrast", Icons.Outlined.Contrast),
    Saturation("Saturation", Icons.Outlined.Palette),
    Warmth("Warmth", Icons.Outlined.Thermostat),
    Tint("Tint", Icons.Outlined.InvertColors),
    Highlights("Highlights", Icons.Outlined.HdrStrong),
    Shadows("Shadows", Icons.Outlined.WbShade),
    Fade("Fade", Icons.Outlined.Gradient, signed = false),
    Vignette("Vignette", Icons.Outlined.Vignette, signed = false),
}

private fun Adjust.get(p: AdjustParam) = when (p) {
    AdjustParam.Exposure -> exposure
    AdjustParam.Brightness -> brightness
    AdjustParam.Contrast -> contrast
    AdjustParam.Saturation -> saturation
    AdjustParam.Warmth -> warmth
    AdjustParam.Tint -> tint
    AdjustParam.Highlights -> highlights
    AdjustParam.Shadows -> shadows
    AdjustParam.Fade -> fade
    AdjustParam.Vignette -> vignette
}

private fun Adjust.with(p: AdjustParam, v: Float) = when (p) {
    AdjustParam.Exposure -> copy(exposure = v)
    AdjustParam.Brightness -> copy(brightness = v)
    AdjustParam.Contrast -> copy(contrast = v)
    AdjustParam.Saturation -> copy(saturation = v)
    AdjustParam.Warmth -> copy(warmth = v)
    AdjustParam.Tint -> copy(tint = v)
    AdjustParam.Highlights -> copy(highlights = v)
    AdjustParam.Shadows -> copy(shadows = v)
    AdjustParam.Fade -> copy(fade = v)
    AdjustParam.Vignette -> copy(vignette = v)
}

private data class Aspect(val label: String, val ratio: Float?)

private data class BgOption(val label: String, val value: Background, val swatch: Color?)

private val bgOptions = listOf(
    BgOption("Original", Background.Keep, null),
    BgOption("Remove", Background.Transparent, null),
    BgOption("Blur", Background.Blur, null),
    BgOption("White", Background.Solid(0xFFFFFFFF.toInt()), Color.White),
    BgOption("Black", Background.Solid(0xFF000000.toInt()), Color.Black),
    BgOption("Peach", Background.Solid(0xFFFFC9A8.toInt()), Color(0xFFFFC9A8)),
    BgOption("Sky", Background.Solid(0xFFAFD8FF.toInt()), Color(0xFFAFD8FF)),
    BgOption("Mint", Background.Solid(0xFFBDF2D5.toInt()), Color(0xFFBDF2D5)),
    BgOption("Rose", Background.Solid(0xFFF7B8C9.toInt()), Color(0xFFF7B8C9)),
)

@Composable
fun EditorOverlay(media: Media, vm: GalleryViewModel, onClose: () -> Unit) {
    CompositionLocalProvider(LocalGalleryColors provides DarkGallery) {
        EditorContent(media, vm, onClose)
    }
}

@Composable
private fun EditorContent(media: Media, vm: GalleryViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haze = remember { HazeState() }

    var source by remember { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var params by remember { mutableStateOf(EditParams()) }
    var tool by remember { mutableStateOf(Tool.Adjust) }
    var param by remember { mutableStateOf(AdjustParam.Exposure) }
    var oriented by remember { mutableStateOf<Bitmap?>(null) }
    var subject by remember { mutableStateOf<Bitmap?>(null) }
    var subjectKey by remember { mutableStateOf<Geometry?>(null) }
    var finding by remember { mutableStateOf(false) }
    var base by remember { mutableStateOf<Bitmap?>(null) }
    var plain by remember { mutableStateOf<Bitmap?>(null) }
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    var comparing by remember { mutableStateOf(false) }
    var aspect by remember { mutableStateOf<Float?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }
    var saveAnchor by remember { mutableStateOf<Rect?>(null) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }

    val changed = params != EditParams()

    LaunchedEffect(media.id) {
        runCatching {
            val b = EditEngine.load(context, media.uri, 1800)
            source = b
            thumb = withContext(Dispatchers.Default) {
                val s = 180f / max(b.width, b.height)
                Bitmap.createScaledBitmap(b, (b.width * s).roundToInt().coerceAtLeast(1), (b.height * s).roundToInt().coerceAtLeast(1), true)
            }
        }.onFailure { loadFailed = true }
    }
    if (loadFailed) {
        LaunchedEffect(Unit) { toast(context, "This photo can't be edited"); onClose() }
    }

    // Oriented (rotation/flip/straighten) image for the crop tool.
    val g = params.geometry
    LaunchedEffect(source, g.rotation, g.flipH, g.straighten) {
        val s = source ?: return@LaunchedEffect
        oriented = withContext(Dispatchers.Default) { EditEngine.orient(s, g.copy(crop = RectF(0f, 0f, 1f, 1f))) }
    }

    // Subject cut-out, recomputed when geometry changes and a background mode is on.
    LaunchedEffect(source, params.background, g) {
        val s = source ?: return@LaunchedEffect
        if (params.background == Background.Keep) return@LaunchedEffect
        if (subjectKey == g && subject != null) return@LaunchedEffect
        finding = true
        val cut = runCatching {
            EditEngine.subject(withContext(Dispatchers.Default) { EditEngine.geometry(s, g) })
        }.getOrNull()
        finding = false
        if (cut == null) {
            toast(context, "Couldn't find a subject in this photo")
            params = params.copy(background = Background.Keep)
        } else {
            subject = cut
            subjectKey = g
        }
    }

    // Base render (everything except colour, which is applied live).
    LaunchedEffect(source, g, params.adjust.highlights, params.adjust.shadows, params.adjust.fade, params.adjust.vignette, params.background, subject) {
        val s = source ?: return@LaunchedEffect
        delay(30)
        val fg = if (params.background != Background.Keep && subjectKey == g) subject else null
        val p = params
        base = withContext(Dispatchers.Default) { EditEngine.renderBase(s, p, fg) }
        plain = withContext(Dispatchers.Default) { EditEngine.geometry(s, g) }
    }

    val liveMatrix = remember(params) { ColorMatrix(EditEngine.colorMatrix(params).array) }

    val writeRequest = rememberMediaRequest { ok ->
        if (ok) scope.launch { doSave(context, media, params, overwrite = true, vm = vm, setSaving = { saving = it }, done = onClose) }
    }

    fun close() {
        if (changed && !saving) {
            confirm = ConfirmRequest("Discard changes?", "Your edits to this photo will be lost.", "Discard") { onClose() }
        } else onClose()
    }
    BackHandler(enabled = !saving) {
        when {
            confirm != null -> confirm = null
            saveMenu -> saveMenu = false
            else -> close()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).noRippleClickable { }) {
        // ---------- Image area (blur source) ----------
        Box(
            Modifier
                .fillMaxSize()
                .haze(haze)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(top = 70.dp, bottom = 236.dp, start = 12.dp, end = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val o = oriented
            val b = base
            when {
                source == null -> CircularProgressIndicator(color = Accent, strokeWidth = 2.5.dp)
                tool == Tool.Crop && o != null -> CropView(
                    bitmap = o,
                    crop = g.crop,
                    aspect = aspect,
                    onCrop = { params = params.copy(geometry = params.geometry.copy(crop = it)) },
                )
                b != null -> {
                    val show = if (comparing) plain ?: b else b
                    Box(contentAlignment = Alignment.Center) {
                        if (params.background == Background.Transparent && !comparing) {
                            Checkerboard(show.width.toFloat() / show.height)
                        }
                        Image(
                            bitmap = show.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            colorFilter = if (comparing) null else ColorFilter.colorMatrix(liveMatrix),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            if (finding) {
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Finding the subject…", color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ---------- Top bar ----------
        Row(
            Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassSurface(haze, Modifier.height(40.dp).pressable(enabled = !saving) { close() }, shape = CircleShape) {
                Text("Cancel", color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 18.dp))
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(pass = PointerEventPass.Initial)
                            comparing = true
                            do {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                            } while (e.changes.any { it.pressed })
                            comparing = false
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Compare, "Hold to compare", tint = Color.White.copy(alpha = if (changed) 1f else 0.4f)) }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(40.dp).clip(CircleShape).pressable(enabled = changed) { params = EditParams(); aspect = null },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.RestartAlt, "Reset", tint = Color.White.copy(alpha = if (changed) 1f else 0.4f)) }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .onGloballyPositioned { saveAnchor = it.boundsInRoot() }
                    .height(40.dp)
                    .background(if (changed) Accent else Accent.copy(alpha = 0.35f), CircleShape)
                    .pressable(enabled = changed && !saving) { saveMenu = true },
                contentAlignment = Alignment.Center,
            ) {
                Text("Save", color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 22.dp))
            }
        }

        // ---------- Tool panel + dock ----------
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = tool,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "panel",
                ) { t ->
                    when (t) {
                        Tool.Crop -> CropPanel(
                            geometry = g,
                            aspect = aspect,
                            originalRatio = oriented?.let { it.width.toFloat() / it.height } ?: 1f,
                            onAspect = { r ->
                                aspect = r
                                val o = oriented
                                if (r != null && o != null) {
                                    params = params.copy(geometry = g.copy(crop = centeredCrop(o.width, o.height, r)))
                                }
                            },
                            onRotate = {
                                params = params.copy(geometry = g.copy(rotation = (g.rotation + 90) % 360, crop = RectF(0f, 0f, 1f, 1f)))
                                aspect = null
                            },
                            onFlip = {
                                val c = g.crop
                                params = params.copy(geometry = g.copy(flipH = !g.flipH, crop = RectF(1f - c.right, c.top, 1f - c.left, c.bottom)))
                            },
                            onStraighten = { params = params.copy(geometry = g.copy(straighten = it)) },
                        )
                        Tool.Adjust -> AdjustPanel(
                            adjust = params.adjust,
                            selected = param,
                            onSelect = { param = it },
                            onChange = { v -> params = params.copy(adjust = params.adjust.with(param, v)) },
                        )
                        Tool.Filter -> FilterPanel(
                            thumb = thumb,
                            selected = params.filter,
                            amount = params.filterAmount,
                            onSelect = { params = params.copy(filter = it, filterAmount = 1f) },
                            onAmount = { params = params.copy(filterAmount = it) },
                        )
                        Tool.Cutout -> BackgroundPanel(
                            selected = params.background,
                            busy = finding,
                            onSelect = { params = params.copy(background = it) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            GlassSurface(haze, Modifier.height(66.dp), shape = CircleShape) {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tool.entries.forEach { t ->
                        val sel = t == tool
                        Column(
                            Modifier.width(80.dp).pressable { tool = t },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(t.icon, t.label, tint = if (sel) Accent else Color.White, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(3.dp))
                            Text(
                                t.label, fontSize = 11.sp,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (sel) Accent else Color.White,
                            )
                        }
                    }
                }
            }
        }

        val canOverwrite = media.mimeType == "image/jpeg" && params.background != Background.Transparent
        GlassPopupMenu(
            visible = saveMenu,
            state = haze,
            anchor = saveAnchor,
            placement = PopupAnchor.BelowEnd,
            options = buildList {
                add(SheetOption("Save as copy", Icons.Outlined.Filter, subtitle = "Keeps the original") {
                    scope.launch { doSave(context, media, params, overwrite = false, vm = vm, setSaving = { saving = it }, done = onClose) }
                })
                if (canOverwrite) {
                    add(SheetOption("Replace original", Icons.Outlined.Brightness6, subtitle = "Date and location kept") {
                        runCatching {
                            writeRequest(MediaStore.createWriteRequest(context.contentResolver, listOf(media.uri)))
                        }
                    })
                }
            },
            onDismiss = { saveMenu = false },
        )

        if (saving) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).noRippleClickable { },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 3.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("Saving in full quality…", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        GlassDialog(request = confirm, state = haze, onDismiss = { confirm = null })
    }
}

private suspend fun doSave(
    context: android.content.Context,
    media: Media,
    params: EditParams,
    overwrite: Boolean,
    vm: GalleryViewModel,
    setSaving: (Boolean) -> Unit,
    done: () -> Unit,
) {
    setSaving(true)
    val ok = runCatching {
        val full = EditEngine.load(context, media.uri, 4096)
        val fg = if (params.background != Background.Keep) {
            EditEngine.subject(withContext(Dispatchers.Default) { EditEngine.geometry(full, params.geometry) })
        } else null
        val out = withContext(Dispatchers.Default) { EditEngine.renderFull(full, params, fg) }
        if (overwrite) EditEngine.overwrite(context, media, out)
        else EditEngine.saveCopy(context, media, out, png = params.background == Background.Transparent) != null
    }.getOrDefault(false)
    setSaving(false)
    toast(context, if (ok) (if (overwrite) "Photo updated" else "Saved as a copy") else "Couldn't save the photo")
    vm.scheduleRefresh(0)
    if (ok) done()
}

private fun centeredCrop(w: Int, h: Int, ratio: Float): RectF {
    val imgRatio = w.toFloat() / h
    return if (ratio > imgRatio) {
        val ch = imgRatio / ratio
        RectF(0f, (1 - ch) / 2, 1f, (1 + ch) / 2)
    } else {
        val cw = ratio / imgRatio
        RectF((1 - cw) / 2, 0f, (1 + cw) / 2, 1f)
    }
}

@Composable
private fun Checkerboard(ratio: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val (w, h) = if (size.width / size.height > ratio) size.height * ratio to size.height else size.width to size.width / ratio
        val left = (size.width - w) / 2
        val top = (size.height - h) / 2
        val cell = 16.dp.toPx()
        var y = 0f
        var row = 0
        while (y < h) {
            var x = 0f
            var col = 0
            while (x < w) {
                drawRect(
                    if ((row + col) % 2 == 0) Color(0xFF3A3A3E) else Color(0xFF26262A),
                    Offset(left + x, top + y),
                    Size(min(cell, w - x), min(cell, h - y)),
                )
                x += cell; col++
            }
            y += cell; row++
        }
    }
}

// ---------------- Crop ----------------

private enum class Handle { None, Move, TL, TR, BL, BR }

@Composable
private fun CropView(bitmap: Bitmap, crop: RectF, aspect: Float?, onCrop: (RectF) -> Unit) {
    val density = LocalDensity.current
    val currentCrop by rememberUpdatedState(crop)
    val currentAspect by rememberUpdatedState(aspect)
    val onCropUpdated by rememberUpdatedState(onCrop)
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val (iw, ih) = EditEngine.fitInside(bitmap.width, bitmap.height, maxW, maxH)
        val left = (maxW - iw) / 2
        val top = (maxH - ih) / 2
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        val touch = with(density) { 36.dp.toPx() }
        val minSize = with(density) { 60.dp.toPx() }
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(bitmap) {
                    var handle = Handle.None
                    var rect = Rect.Zero
                    detectDragGestures(
                        onDragStart = { p ->
                            val c = currentCrop
                            rect = Rect(left + c.left * iw, top + c.top * ih, left + c.right * iw, top + c.bottom * ih)
                            handle = when {
                                (p - rect.topLeft).getDistance() < touch -> Handle.TL
                                (p - rect.topRight).getDistance() < touch -> Handle.TR
                                (p - rect.bottomLeft).getDistance() < touch -> Handle.BL
                                (p - rect.bottomRight).getDistance() < touch -> Handle.BR
                                rect.contains(p) -> Handle.Move
                                else -> Handle.None
                            }
                        },
                        onDrag = { change, d ->
                            change.consume()
                            val bounds = Rect(left, top, left + iw, top + ih)
                            var r = rect
                            when (handle) {
                                Handle.Move -> {
                                    val dx = d.x.coerceIn(bounds.left - r.left, bounds.right - r.right)
                                    val dy = d.y.coerceIn(bounds.top - r.top, bounds.bottom - r.bottom)
                                    r = r.translate(dx, dy)
                                }
                                Handle.None -> return@detectDragGestures
                                else -> {
                                    var l = r.left; var t = r.top; var rr = r.right; var b = r.bottom
                                    when (handle) {
                                        Handle.TL -> { l += d.x; t += d.y }
                                        Handle.TR -> { rr += d.x; t += d.y }
                                        Handle.BL -> { l += d.x; b += d.y }
                                        else -> { rr += d.x; b += d.y }
                                    }
                                    l = l.coerceIn(bounds.left, rr - minSize)
                                    rr = rr.coerceIn(l + minSize, bounds.right)
                                    t = t.coerceIn(bounds.top, b - minSize)
                                    b = b.coerceIn(t + minSize, bounds.bottom)
                                    val a = currentAspect
                                    if (a != null) {
                                        // Keep the chosen shape: height follows width, anchored at the opposite corner.
                                        // The photo is shown at a uniform scale, so on-screen shape = photo shape.
                                        val pxRatio = a
                                        val wNow = rr - l
                                        var hNow = wNow / pxRatio
                                        when (handle) {
                                            Handle.TL, Handle.TR -> { t = b - hNow; if (t < bounds.top) { t = bounds.top; hNow = b - t } }
                                            else -> { b = t + hNow; if (b > bounds.bottom) { b = bounds.bottom; hNow = b - t } }
                                        }
                                        val wFix = hNow * pxRatio
                                        when (handle) {
                                            Handle.TL, Handle.BL -> l = rr - wFix
                                            else -> rr = l + wFix
                                        }
                                    }
                                    r = Rect(l, t, rr, b)
                                }
                            }
                            rect = r
                            onCropUpdated(
                                RectF(
                                    ((r.left - left) / iw).coerceIn(0f, 1f),
                                    ((r.top - top) / ih).coerceIn(0f, 1f),
                                    ((r.right - left) / iw).coerceIn(0f, 1f),
                                    ((r.bottom - top) / ih).coerceIn(0f, 1f),
                                )
                            )
                        },
                    )
                }
        ) {
            val c = currentCrop
            val r = Rect(left + c.left * iw, top + c.top * ih, left + c.right * iw, top + c.bottom * ih)
            val dim = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(left, top, left + iw, top + ih))
                addRect(r)
            }
            drawPath(dim, Color.Black.copy(alpha = 0.55f))
            drawRect(Color.White, r.topLeft, r.size, style = Stroke(1.5.dp.toPx()))
            for (i in 1..2) {
                val x = r.left + r.width * i / 3
                val y = r.top + r.height * i / 3
                drawLine(Color.White.copy(alpha = 0.45f), Offset(x, r.top), Offset(x, r.bottom), 0.8.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.45f), Offset(r.left, y), Offset(r.right, y), 0.8.dp.toPx())
            }
            val len = 18.dp.toPx()
            val sw = 4.dp.toPx()
            listOf(r.topLeft to Offset(1f, 1f), r.topRight to Offset(-1f, 1f), r.bottomLeft to Offset(1f, -1f), r.bottomRight to Offset(-1f, -1f))
                .forEach { (p, dir) ->
                    drawLine(Color.White, p, p + Offset(len * dir.x, 0f), sw)
                    drawLine(Color.White, p, p + Offset(0f, len * dir.y), sw)
                }
        }
    }
}

@Composable
private fun CropPanel(
    geometry: Geometry,
    aspect: Float?,
    originalRatio: Float,
    onAspect: (Float?) -> Unit,
    onRotate: () -> Unit,
    onFlip: () -> Unit,
    onStraighten: (Float) -> Unit,
) {
    val aspects = listOf(
        Aspect("Free", null), Aspect("Original", originalRatio), Aspect("1:1", 1f), Aspect("4:5", 0.8f),
        Aspect("3:4", 0.75f), Aspect("4:3", 4f / 3f), Aspect("9:16", 9f / 16f), Aspect("16:9", 16f / 9f),
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp)) {
            Text("Straighten", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.width(76.dp))
            EditorSlider(geometry.straighten / 45f, signed = true, onChange = { onStraighten(it * 45f) }, modifier = Modifier.weight(1f))
            Text("${geometry.straighten.roundToInt()}°", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(36.dp))
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item { RoundTool(Icons.Outlined.RotateRight, "Rotate", onRotate) }
            item { RoundTool(Icons.Outlined.Flip, "Flip", onFlip) }
            items(aspects) { a ->
                val sel = (a.ratio == null && aspect == null) || (a.ratio != null && aspect != null && abs(a.ratio - aspect) < 0.001f)
                Text(
                    a.label,
                    color = if (sel) Color.Black else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (sel) Color.White else Color.White.copy(alpha = 0.12f))
                        .pressable { onAspect(a.ratio) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
    }
}

@Composable
private fun RoundTool(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(20.dp)) }
}

// ---------------- Adjust ----------------

@Composable
private fun AdjustPanel(adjust: Adjust, selected: AdjustParam, onSelect: (AdjustParam) -> Unit, onChange: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val v = adjust.get(selected)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(selected.label, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.width(76.dp))
            EditorSlider(v, signed = selected.signed, onChange = onChange, modifier = Modifier.weight(1f))
            Text("${(v * 100).roundToInt()}", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(36.dp))
        }
        Spacer(Modifier.height(10.dp))
        val row = rememberLazyListState()
        val scope = rememberCoroutineScope()
        LazyRow(
            state = row,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(AdjustParam.entries) { p ->
                val sel = p == selected
                val value = adjust.get(p)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.pressable {
                    if (!sel) scope.launch { row.advancePast(p.ordinal) }
                    onSelect(p)
                }) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (sel) Color.White else Color.White.copy(alpha = 0.12f))
                            .then(if (value != 0f && !sel) Modifier.border(1.5.dp, Accent, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) { Icon(p.icon, p.label, tint = if (sel) Color.Black else Color.White, modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.height(4.dp))
                    Text(p.label, color = if (sel) Color.White else Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun EditorSlider(value: Float, signed: Boolean, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = if (signed) -1f..1f else 0f..1f,
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Accent,
            inactiveTrackColor = Color.White.copy(alpha = 0.25f),
        ),
        modifier = modifier,
    )
}

// ---------------- Filters ----------------

@Composable
private fun FilterPanel(thumb: Bitmap?, selected: String, amount: Float, onSelect: (String) -> Unit, onAmount: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp).height(40.dp),
        ) {
            if (selected != "Original") {
                Text("Strength", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.width(76.dp))
                EditorSlider(amount, signed = false, onChange = onAmount, modifier = Modifier.weight(1f))
                Text("${(amount * 100).roundToInt()}", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(36.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        val row = rememberLazyListState()
        val scope = rememberCoroutineScope()
        LazyRow(
            state = row,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(EditEngine.filterNames) { i, name ->
                val sel = name == selected
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.pressable {
                    if (!sel) scope.launch { row.advancePast(i) }
                    onSelect(name)
                }) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.1f))
                            .then(if (sel) Modifier.border(2.dp, Accent, RoundedCornerShape(14.dp)) else Modifier),
                    ) {
                        if (thumb != null) {
                            Image(
                                bitmap = thumb.asImageBitmap(),
                                contentDescription = name,
                                contentScale = ContentScale.Crop,
                                colorFilter = ColorFilter.colorMatrix(ColorMatrix(EditEngine.filterMatrix(name).array)),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(name, color = if (sel) Accent else Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                }
            }
        }
    }
}

// ---------------- Background ----------------

@Composable
private fun BackgroundPanel(selected: Background, busy: Boolean, onSelect: (Background) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (busy) "Separating the subject on your phone…" else "Remove or replace the background behind people, pets and objects",
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(6.dp))
        val row = rememberLazyListState()
        val scope = rememberCoroutineScope()
        LazyRow(
            state = row,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(bgOptions) { i, o ->
                val sel = o.value == selected
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.pressable(enabled = !busy) {
                    if (!sel) scope.launch { row.advancePast(i) }
                    onSelect(o.value)
                }) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(o.swatch ?: Color.White.copy(alpha = 0.12f))
                            .border(if (sel) 2.5.dp else 1.dp, if (sel) Accent else Color.White.copy(alpha = 0.25f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (o.value) {
                            Background.Keep -> Icon(Icons.Outlined.Tonality, null, tint = Color.White, modifier = Modifier.size(22.dp))
                            Background.Transparent -> Icon(Icons.Outlined.AutoFixHigh, null, tint = Color.White, modifier = Modifier.size(22.dp))
                            Background.Blur -> Icon(Icons.Outlined.BlurOn, null, tint = Color.White, modifier = Modifier.size(22.dp))
                            else -> Unit
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(o.label, color = if (sel) Accent else Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * After tapping an item, slide the row by one item so the next option lands under the
 * finger: tapping through options doesn't need the finger to move.
 */
private suspend fun androidx.compose.foundation.lazy.LazyListState.advancePast(index: Int) {
    val info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    if (index >= layoutInfo.totalItemsCount - 1) return
    animateScrollBy(
        (info.size + layoutInfo.mainAxisItemSpacing).toFloat(),
        androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 380f),
    )
}
