package com.javaram.gallery.ui.permission

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.javaram.gallery.ui.components.GlassSurface
import com.javaram.gallery.ui.components.pressable
import com.javaram.gallery.ui.theme.Accent
import com.javaram.gallery.ui.theme.AccentDeep
import com.javaram.gallery.ui.theme.GalleryTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

@Composable
fun PermissionScreen(permissions: Array<String>, onResult: () -> Unit) {
    val c = GalleryTheme.colors
    val context = LocalContext.current
    val haze = remember { HazeState() }
    var denials by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.none { it }) denials++
        onResult()
    }
    val t = rememberInfiniteTransition(label = "blobs")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(7000), RepeatMode.Reverse), label = "drift")

    Box(Modifier.fillMaxSize().background(c.background)) {
        // Soft colourful blobs behind the glass card.
        Canvas(Modifier.fillMaxSize().haze(haze)) {
            drawCircle(
                Brush.radialGradient(listOf(Accent, Color.Transparent)),
                radius = size.width * 0.75f,
                center = Offset(size.width * (0.15f + drift * 0.2f), size.height * 0.28f),
            )
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFF7B61FF), Color.Transparent)),
                radius = size.width * 0.7f,
                center = Offset(size.width * (0.95f - drift * 0.25f), size.height * 0.5f),
            )
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFF00C2FF), Color.Transparent)),
                radius = size.width * 0.6f,
                center = Offset(size.width * 0.3f, size.height * (0.82f - drift * 0.1f)),
            )
        }
        GlassSurface(
            state = haze,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(34.dp),
        ) {
            Column(
                Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier
                        .size(72.dp)
                        .background(Brush.linearGradient(listOf(Accent, AccentDeep)), RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, null, tint = Color.White, modifier = Modifier.size(38.dp))
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    "Your memories, beautifully kept",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = c.content,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Gallery needs access to your photos and videos. Everything stays on your phone — nothing is uploaded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.secondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .background(Brush.horizontalGradient(listOf(Accent, AccentDeep)), CircleShape)
                        .pressable {
                            if (denials >= 2) {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                )
                            } else launcher.launch(permissions)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (denials >= 2) "Open settings" else "Allow access",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (denials >= 1) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Choose \"Allow all\" so every photo shows up.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.secondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
