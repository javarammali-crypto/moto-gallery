package com.javaram.gallery.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javaram.gallery.GalleryViewModel
import com.javaram.gallery.ui.components.GlassIconButton
import com.javaram.gallery.ui.theme.GalleryTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

@Composable
fun SettingsScreen(vm: GalleryViewModel, onBack: () -> Unit) {
    val c = GalleryTheme.colors
    val haze = remember { HazeState() }
    val faceRecognition by vm.faceRecognitionEnabled.collectAsStateWithLifecycle()
    val faceUnavailable by vm.faceRecognitionUnavailable.collectAsStateWithLifecycle()
    // Read once, so the row, its semantics and the switch all describe the same value.
    val face = faceRecognition
    val faceStatus = when {
        face != null -> null
        faceUnavailable -> "Unavailable"
        else -> "Loading"
    }

    Box(Modifier.fillMaxSize().background(c.background)) {
        Box(Modifier.fillMaxSize().haze(haze)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .padding(top = 60.dp)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("Settings", style = MaterialTheme.typography.displaySmall, color = c.content)
                Text(
                    "Privacy",
                    style = MaterialTheme.typography.titleSmall,
                    color = c.secondary,
                    modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .background(c.card, RoundedCornerShape(20.dp))
                        .toggleable(
                            value = face == true,
                            enabled = face != null,
                            role = Role.Switch,
                            onValueChange = { vm.setFaceRecognitionEnabled(it) },
                        )
                        .semantics { if (faceStatus != null) stateDescription = faceStatus }
                        .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Face recognition",
                            style = MaterialTheme.typography.titleSmall,
                            color = c.content,
                        )
                        Text(
                            "When off, Gallery stops analysing new photos for faces and hides the " +
                                "People collection. Faces already found stay on this device and " +
                                "reappear when you turn this back on.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.secondary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    // The row carries the toggle semantics; the switch itself is decorative here.
                    // Until the saved choice is read, no switch is drawn, so no position is shown.
                    when {
                        face != null -> Switch(checked = face, onCheckedChange = null)
                        faceUnavailable -> Text(
                            "Unavailable",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.secondary,
                        )
                        else -> CircularProgressIndicator(
                            Modifier.size(24.dp),
                            color = c.secondary,
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        }

        Row(
            Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            GlassIconButton(haze, Icons.AutoMirrored.Outlined.ArrowBack, "Back", size = 42.dp, onClick = onBack)
        }
    }
}
