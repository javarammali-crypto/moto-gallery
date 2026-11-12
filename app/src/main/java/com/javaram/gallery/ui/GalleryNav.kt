package com.javaram.gallery.ui

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.javaram.gallery.GalleryViewModel
import com.javaram.gallery.ui.album.AlbumScreen
import com.javaram.gallery.ui.components.LocalNavScope
import com.javaram.gallery.ui.components.LocalSharedScope
import com.javaram.gallery.ui.home.HomeScreen
import com.javaram.gallery.ui.theme.GalleryTheme
import com.javaram.gallery.ui.trash.TrashScreen
import com.javaram.gallery.ui.viewer.ViewerScreen

object Routes {
    const val HOME = "home"
    const val ALBUM = "album/{key}"
    const val VIEWER = "viewer"
    const val TRASH = "trash"
    fun album(key: String) = "album/$key"
}

@Composable
fun GalleryNav(vm: GalleryViewModel) {
    val nav = rememberNavController()
    SharedTransitionLayout(Modifier.fillMaxSize().background(GalleryTheme.colors.background)) {
        CompositionLocalProvider(LocalSharedScope provides this) {
            NavHost(
                navController = nav,
                startDestination = Routes.HOME,
                enterTransition = { fadeIn(tween(260)) + slideInHorizontally(tween(320)) { it / 6 } },
                exitTransition = {
                    if (targetState.destination.route == Routes.VIEWER) fadeOut(tween(260))
                    else fadeOut(tween(200)) + scaleOut(tween(320), targetScale = 0.96f)
                },
                popEnterTransition = {
                    if (initialState.destination.route == Routes.VIEWER) fadeIn(tween(220))
                    else fadeIn(tween(260)) + scaleIn(tween(320), initialScale = 0.96f)
                },
                popExitTransition = { fadeOut(tween(200)) + slideOutHorizontally(tween(320)) { it / 6 } },
            ) {
                composable(Routes.HOME) {
                    CompositionLocalProvider(LocalNavScope provides this) {
                        HomeScreen(
                            vm = vm,
                            onOpen = { list, media ->
                                vm.openViewer(list, media.id)
                                nav.navigate(Routes.VIEWER)
                            },
                            onOpenAlbum = { key -> nav.navigate(Routes.album(key)) },
                            onOpenTrash = { nav.navigate(Routes.TRASH) },
                        )
                    }
                }
                composable(Routes.ALBUM) { entry ->
                    val key = entry.arguments?.getString("key") ?: ""
                    CompositionLocalProvider(LocalNavScope provides this) {
                        AlbumScreen(
                            vm = vm,
                            albumKey = key,
                            onBack = { nav.popBackStack() },
                            onOpen = { list, media ->
                                vm.openViewer(list, media.id)
                                nav.navigate(Routes.VIEWER)
                            },
                        )
                    }
                }
                composable(
                    Routes.VIEWER,
                    enterTransition = { fadeIn(tween(220)) },
                    exitTransition = { fadeOut(tween(220)) },
                    popEnterTransition = { fadeIn(tween(220)) },
                    popExitTransition = { fadeOut(tween(260)) },
                ) {
                    CompositionLocalProvider(LocalNavScope provides this) {
                        ViewerScreen(vm = vm, onBack = { nav.popBackStack() })
                    }
                }
                composable(Routes.TRASH) {
                    CompositionLocalProvider(LocalNavScope provides this) {
                        TrashScreen(vm = vm, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
