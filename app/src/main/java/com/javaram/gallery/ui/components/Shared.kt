package com.javaram.gallery.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect

val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalNavScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

private val mediaBounds = BoundsTransform { _: Rect, _: Rect ->
    spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
}

/** Photo morphs between the grid and the viewer with a soft spring. */
@Composable
fun Modifier.sharedMedia(id: Long): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val nav = LocalNavScope.current ?: return this
    return with(shared) {
        this@sharedMedia.sharedElement(
            state = rememberSharedContentState(key = "media-$id"),
            animatedVisibilityScope = nav,
            boundsTransform = mediaBounds,
        )
    }
}
