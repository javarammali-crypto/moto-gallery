package com.javaram.gallery.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Accent = Color(0xFFFF7A1F)
val AccentDeep = Color(0xFFFF5A1F)

@Immutable
data class GalleryColors(
    val dark: Boolean,
    val background: Color,
    val content: Color,
    val secondary: Color,
    val glassTint: Color,
    val glassBorderTop: Color,
    val glassBorderBottom: Color,
    val glassHighlight: Color,
    val card: Color,
    val divider: Color,
)

private val LightGallery = GalleryColors(
    dark = false,
    background = Color(0xFFF7F7F8),
    content = Color(0xFF111114),
    secondary = Color(0xFF6E6E76),
    glassTint = Color(0x27FFFFFF),
    glassBorderTop = Color(0xF2FFFFFF),
    glassBorderBottom = Color(0x26FFFFFF),
    glassHighlight = Color(0x5AFFFFFF),
    card = Color(0xFFFFFFFF),
    divider = Color(0x14000000),
)

val DarkGallery = GalleryColors(
    dark = true,
    background = Color(0xFF000000),
    content = Color(0xFFF4F4F6),
    secondary = Color(0xFF9A9AA2),
    glassTint = Color(0x201C1C20),
    glassBorderTop = Color(0x59FFFFFF),
    glassBorderBottom = Color(0x0AFFFFFF),
    glassHighlight = Color(0x19FFFFFF),
    card = Color(0xFF1A1A1D),
    divider = Color(0x1FFFFFFF),
)

val LocalGalleryColors = staticCompositionLocalOf { LightGallery }

object GalleryTheme {
    val colors: GalleryColors
        @Composable get() = LocalGalleryColors.current
}

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun GalleryTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val g = if (dark) DarkGallery else LightGallery
    val scheme = if (dark) {
        darkColorScheme(
            primary = Accent, onPrimary = Color.White,
            background = g.background, onBackground = g.content,
            surface = g.card, onSurface = g.content,
        )
    } else {
        lightColorScheme(
            primary = Accent, onPrimary = Color.White,
            background = g.background, onBackground = g.content,
            surface = g.card, onSurface = g.content,
        )
    }
    CompositionLocalProvider(LocalGalleryColors provides g) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
