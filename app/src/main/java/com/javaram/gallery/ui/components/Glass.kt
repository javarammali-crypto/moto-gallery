package com.javaram.gallery.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.javaram.gallery.ui.theme.Accent
import com.javaram.gallery.ui.theme.GalleryTheme
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild

@Composable
fun glassStyle(blur: Dp = 34.dp, tint: Color? = null): HazeStyle {
    val c = GalleryTheme.colors
    return HazeStyle(
        backgroundColor = c.background,
        tints = listOf(HazeTint(tint ?: c.glassTint)),
        blurRadius = blur,
        noiseFactor = 0.035f,
    )
}

/** Frosted-glass container that blurs whatever is drawn behind it in [state]'s source. */
@Composable
fun GlassSurface(
    state: HazeState,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    tint: Color? = null,
    blur: Dp = 34.dp,
    border: Boolean = true,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = GalleryTheme.colors
    val style = glassStyle(blur, tint)
    var m = modifier
        .clip(shape)
        .hazeChild(state, style)
    // Specular sheen: a soft light falling across the top of the glass, like iOS / ColorOS 17.
    m = m.background(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (c.dark) 0.07f else 0.18f),
            0.45f to Color.White.copy(alpha = if (c.dark) 0.014f else 0.035f),
            1f to Color.Transparent,
        )
    )
    if (border) {
        m = m.border(
            width = 0.9.dp,
            brush = Brush.linearGradient(
                0f to c.glassBorderTop,
                0.5f to c.glassBorderBottom,
                1f to c.glassBorderTop.copy(alpha = c.glassBorderTop.alpha * 0.45f),
            ),
            shape = shape,
        )
    }
    Box(m, contentAlignment = contentAlignment, content = content)
}

/** Glass bar that is fully frosted at the top edge and fades to clear at the bottom. */
@Composable
fun ProgressiveGlass(
    state: HazeState,
    modifier: Modifier = Modifier,
    fadeDown: Boolean = true,
    visibleAlpha: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    val style = glassStyle(blur = 24.dp)
    Box(
        modifier.hazeChild(state, style) {
            alpha = visibleAlpha
            progressive = if (fadeDown) {
                HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f)
            } else {
                HazeProgressive.verticalGradient(startIntensity = 0f, endIntensity = 1f)
            }
        },
        content = content,
    )
}

fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier = this.then(
    Modifier.clickable(
        interactionSource = MutableInteractionSource(),
        indication = null,
        onClick = onClick,
    )
)

/** Springy scale-down while pressed, like ColorOS buttons. */
@Composable
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
}

@Composable
fun GlassIconButton(
    state: HazeState,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color = GalleryTheme.colors.content,
    onClick: () -> Unit,
) {
    GlassSurface(
        state = state,
        modifier = modifier.size(size).pressable(onClick = onClick),
        shape = CircleShape,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.46f))
    }
}

data class SheetOption(
    val label: String,
    val icon: ImageVector? = null,
    val selected: Boolean = false,
    val destructive: Boolean = false,
    val subtitle: String? = null,
    val onClick: () -> Unit,
)

/** Bottom action sheet made of glass; the screen behind is blurred while it's open. */
@Composable
fun GlassActionSheet(
    visible: Boolean,
    state: HazeState,
    title: String?,
    options: List<SheetOption>,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(200)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .hazeChild(state, glassStyle(blur = 14.dp, tint = Color.Black.copy(alpha = 0.18f)))
                    .noRippleClickable(onDismiss)
            )
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(dampingRatio = 0.82f, stiffness = 420f)) { it / 2 } +
                fadeIn(tween(180)) + scaleIn(initialScale = 0.94f),
            exit = slideOutVertically(tween(200)) { it / 3 } + fadeOut(tween(160)) + scaleOut(targetScale = 0.96f),
        ) {
            GlassSurface(
                state = state,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 14.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                Column(
                    Modifier
                        .heightIn(max = 560.dp)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                        .padding(vertical = 10.dp)
                ) {
                    if (title != null) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            color = GalleryTheme.colors.secondary,
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                        )
                    }
                    options.forEach { opt -> SheetRow(opt, onDismiss) }
                }
            }
        }
    }
}

@Composable
private fun SheetRow(opt: SheetOption, onDismiss: () -> Unit) {
    val c = GalleryTheme.colors
    val color = when {
        opt.destructive -> Color(0xFFFF453A)
        opt.selected -> Accent
        else -> c.content
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { opt.onClick(); onDismiss() }
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (opt.icon != null) {
            Icon(opt.icon, null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(opt.label, color = color, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (opt.subtitle != null) {
                Text(opt.subtitle, color = c.secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (opt.selected) Icon(Icons.Filled.Check, null, tint = Accent, modifier = Modifier.size(20.dp))
    }
}

enum class PopupAnchor { BelowEnd, BelowCenter, AboveStart, AboveEnd, AboveCenter }

/**
 * Small glass menu that pops out of the button that opened it ([anchor] is that
 * button's bounds in root coordinates), scaling from the button's corner.
 */
@Composable
fun GlassPopupMenu(
    visible: Boolean,
    state: HazeState,
    anchor: androidx.compose.ui.geometry.Rect?,
    placement: PopupAnchor,
    options: List<SheetOption>,
    onDismiss: () -> Unit,
    title: String? = null,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gap = with(density) { 10.dp.roundToPx() }
    val margin = with(density) { 12.dp.roundToPx() }
    val origin = when (placement) {
        PopupAnchor.BelowEnd -> androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
        PopupAnchor.BelowCenter -> androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
        PopupAnchor.AboveStart -> androidx.compose.ui.graphics.TransformOrigin(0f, 1f)
        PopupAnchor.AboveEnd -> androidx.compose.ui.graphics.TransformOrigin(1f, 1f)
        PopupAnchor.AboveCenter -> androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
    }
    Box(Modifier.fillMaxSize()) {
        if (visible) Box(Modifier.fillMaxSize().noRippleClickable(onDismiss))
        AnimatedVisibility(
            visible = visible && anchor != null,
            modifier = Modifier.layout { measurable, constraints ->
                val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val a = anchor ?: androidx.compose.ui.geometry.Rect.Zero
                var x = when (placement) {
                    PopupAnchor.BelowEnd, PopupAnchor.AboveEnd -> a.right.toInt() - p.width
                    PopupAnchor.AboveStart -> a.left.toInt()
                    PopupAnchor.AboveCenter, PopupAnchor.BelowCenter -> (a.center.x - p.width / 2f).toInt()
                }
                val below = placement == PopupAnchor.BelowEnd || placement == PopupAnchor.BelowCenter
                var y = if (below) a.bottom.toInt() + gap else a.top.toInt() - gap - p.height
                x = x.coerceIn(margin, (constraints.maxWidth - p.width - margin).coerceAtLeast(margin))
                y = y.coerceIn(margin, (constraints.maxHeight - p.height - margin).coerceAtLeast(margin))
                layout(constraints.maxWidth, constraints.maxHeight) { p.place(x, y) }
            },
            enter = scaleIn(
                spring(dampingRatio = 0.72f, stiffness = 520f),
                initialScale = 0.4f,
                transformOrigin = origin,
            ) + fadeIn(tween(140)),
            exit = scaleOut(tween(160), targetScale = 0.6f, transformOrigin = origin) + fadeOut(tween(140)),
        ) {
            GlassSurface(
                state = state,
                modifier = Modifier.width(236.dp),
                shape = RoundedCornerShape(24.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    if (title != null) {
                        Text(
                            title,
                            style = MaterialTheme.typography.bodySmall,
                            color = GalleryTheme.colors.secondary,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                        )
                    }
                    options.forEach { opt -> PopupRow(opt, onDismiss) }
                }
            }
        }
    }
}

@Composable
private fun PopupRow(opt: SheetOption, onDismiss: () -> Unit) {
    val c = GalleryTheme.colors
    val color = when {
        opt.destructive -> Color(0xFFFF453A)
        opt.selected -> Accent
        else -> c.content
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onDismiss(); opt.onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (opt.icon != null) {
            Icon(opt.icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(opt.label, color = color, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (opt.subtitle != null) Text(opt.subtitle, color = c.secondary, style = MaterialTheme.typography.bodySmall)
        }
        if (opt.selected) Icon(Icons.Filled.Check, null, tint = Accent, modifier = Modifier.size(18.dp))
    }
}

data class ConfirmRequest(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val destructive: Boolean = true,
    val onConfirm: () -> Unit,
)

/** Centered glass confirmation dialog; the screen behind is blurred. */
@Composable
fun GlassDialog(request: ConfirmRequest?, state: HazeState, onDismiss: () -> Unit) {
    val c = GalleryTheme.colors
    // Keep the last request around so the exit animation still has text to show.
    var last by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(request) }
    if (request != null) last = request
    val r = last
    val visible = request != null
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(180))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .hazeChild(state, glassStyle(blur = 18.dp, tint = Color.Black.copy(alpha = 0.28f)))
                    .noRippleClickable(onDismiss)
            )
        }
        AnimatedVisibility(
            visible = visible && r != null,
            modifier = Modifier.align(Alignment.Center),
            enter = scaleIn(spring(dampingRatio = 0.7f, stiffness = 500f), initialScale = 0.8f) + fadeIn(tween(150)),
            exit = scaleOut(tween(150), targetScale = 0.9f) + fadeOut(tween(150)),
        ) {
            if (r == null) return@AnimatedVisibility
            // Dialogs carry text that must be readable over any photo, so their glass is much
            // more frosted than the bars: the photo still shows through, softly.
            GlassSurface(
                state = state,
                modifier = Modifier.padding(horizontal = 36.dp).fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
                tint = if (c.dark) Color(0xC71C1C20) else Color(0xD6F6F6F8),
                blur = 40.dp,
            ) {
                Column(
                    Modifier.padding(top = 24.dp, start = 22.dp, end = 22.dp, bottom = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(r.title, style = MaterialTheme.typography.titleMedium, color = c.content)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        r.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.content.copy(alpha = 0.72f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(46.dp)
                                .background(c.glassHighlight, CircleShape)
                                .pressable(onClick = onDismiss),
                            contentAlignment = Alignment.Center,
                        ) { Text("Cancel", style = MaterialTheme.typography.titleSmall, color = c.content) }
                        Box(
                            Modifier
                                .weight(1f)
                                .height(46.dp)
                                .background(if (r.destructive) Color(0xFFFF453A) else Accent, CircleShape)
                                .pressable { onDismiss(); r.onConfirm() },
                            contentAlignment = Alignment.Center,
                        ) { Text(r.confirmLabel, style = MaterialTheme.typography.titleSmall, color = Color.White) }
                    }
                }
            }
        }
    }
}
