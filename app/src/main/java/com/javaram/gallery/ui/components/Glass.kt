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
fun glassStyle(blur: Dp = 28.dp, tint: Color? = null): HazeStyle {
    val c = GalleryTheme.colors
    return HazeStyle(
        backgroundColor = c.background,
        tints = listOf(HazeTint(tint ?: c.glassTint)),
        blurRadius = blur,
        noiseFactor = 0.06f,
    )
}

/** Frosted-glass container that blurs whatever is drawn behind it in [state]'s source. */
@Composable
fun GlassSurface(
    state: HazeState,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    tint: Color? = null,
    blur: Dp = 28.dp,
    border: Boolean = true,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = GalleryTheme.colors
    val style = glassStyle(blur, tint)
    var m = modifier
        .clip(shape)
        .hazeChild(state, style)
    if (border) {
        m = m.border(
            width = 0.8.dp,
            brush = Brush.verticalGradient(listOf(c.glassBorderTop, c.glassBorderBottom)),
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
                Column(Modifier.padding(vertical = 10.dp)) {
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
