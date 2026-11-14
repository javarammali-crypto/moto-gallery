package com.javaram.gallery.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.javaram.gallery.ui.theme.Accent
import com.javaram.gallery.ui.theme.GalleryTheme
import dev.chrisbanes.haze.HazeState
import kotlin.math.abs

/**
 * Settings sheet for how see-through the glass is. Changes apply live to every bar, dock,
 * menu and sheet in the app, so the sheet itself is the preview.
 */
@Composable
fun GlassLookSheet(
    visible: Boolean,
    haze: HazeState,
    value: Float,
    onChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = GalleryTheme.colors
    BackHandler(visible) { onDismiss() }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(Modifier.fillMaxSize().noRippleClickable(onDismiss))
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(220)) + slideInVertically(tween(320)) { it / 2 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(220)) { it / 2 },
        ) {
            GlassSurface(
                haze,
                Modifier
                    .navigationBarsPadding()
                    .padding(12.dp)
                    .fillMaxWidth()
                    .noRippleClickable { },
                shape = RoundedCornerShape(30.dp),
            ) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
                    Text("Glass look", style = MaterialTheme.typography.titleMedium, color = c.content)
                    Text(
                        "How see-through the bars, docks and menus are. Applies everywhere in the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.secondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Slider(
                        value = value,
                        onValueChange = onChange,
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = Accent,
                            activeTrackColor = Accent,
                            inactiveTrackColor = c.content.copy(alpha = 0.18f),
                        ),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Clear", style = MaterialTheme.typography.labelSmall, color = c.secondary)
                        Spacer(Modifier.weight(1f))
                        if (abs(value - 0.5f) > 0.01f) {
                            Text(
                                "Reset",
                                style = MaterialTheme.typography.labelSmall,
                                color = Accent,
                                modifier = Modifier.noRippleClickable { onChange(0.5f) }.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                            Spacer(Modifier.weight(1f))
                        }
                        Text("Frosted", style = MaterialTheme.typography.labelSmall, color = c.secondary)
                    }
                }
            }
        }
    }
}
