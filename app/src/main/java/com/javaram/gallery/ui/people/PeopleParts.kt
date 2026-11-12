package com.javaram.gallery.ui.people

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.javaram.gallery.data.FaceThumb
import com.javaram.gallery.data.Media
import com.javaram.gallery.search.Person
import com.javaram.gallery.ui.components.GlassSurface
import com.javaram.gallery.ui.components.glassStyle
import com.javaram.gallery.ui.components.noRippleClickable
import com.javaram.gallery.ui.components.pressable
import com.javaram.gallery.ui.theme.Accent
import com.javaram.gallery.ui.theme.GalleryTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild

/** Round face picture for a person. */
@Composable
fun FaceAvatar(person: Person, byId: Map<Long, Media>, size: Dp, modifier: Modifier = Modifier) {
    val c = GalleryTheme.colors
    val media = person.coverMediaId?.let { byId[it] }
    val box = person.coverBox
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(c.card)
            .border(1.dp, c.divider, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (media != null && box != null) {
            AsyncImage(
                model = FaceThumb(media.uri, box[0], box[1], box[2], box[3]),
                contentDescription = person.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(Icons.Outlined.Person, null, tint = c.secondary, modifier = Modifier.size(size * 0.5f))
        }
    }
}

/** Glass dialog with a text box for naming a person. */
@Composable
fun NameDialog(
    visible: Boolean,
    haze: HazeState,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val c = GalleryTheme.colors
    var text by remember(visible) { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(180))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .hazeChild(haze, glassStyle(blur = 18.dp, tint = Color.Black.copy(alpha = 0.28f)))
                    .noRippleClickable(onDismiss)
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.Center).imePadding(),
            enter = scaleIn(spring(dampingRatio = 0.7f, stiffness = 500f), initialScale = 0.8f) + fadeIn(),
            exit = scaleOut(targetScale = 0.9f) + fadeOut(),
        ) {
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            GlassSurface(
                haze,
                Modifier.padding(horizontal = 32.dp).fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
            ) {
                Column(Modifier.padding(22.dp)) {
                    Text("Who is this?", style = MaterialTheme.typography.titleMedium, color = c.content)
                    Spacer(Modifier.height(14.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(c.glassHighlight)
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                    ) {
                        if (text.isEmpty()) Text("Name", color = c.secondary, style = MaterialTheme.typography.bodyMedium)
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.content),
                            cursorBrush = SolidColor(Accent),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { onSave(text) }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier.weight(1f).height(46.dp).background(c.glassHighlight, CircleShape).pressable(onClick = onDismiss),
                            contentAlignment = Alignment.Center,
                        ) { Text("Cancel", style = MaterialTheme.typography.titleSmall, color = c.content) }
                        Box(
                            Modifier.weight(1f).height(46.dp).background(Accent, CircleShape).pressable { onSave(text) },
                            contentAlignment = Alignment.Center,
                        ) { Text("Save", style = MaterialTheme.typography.titleSmall, color = Color.White) }
                    }
                }
            }
        }
    }
}
