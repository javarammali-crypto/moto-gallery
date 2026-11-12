package com.javaram.gallery.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.navArgument
import com.javaram.gallery.ui.search.PlacesMapScreen
import com.javaram.gallery.ui.search.SearchScreen
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.javaram.gallery.GalleryViewModel
import com.javaram.gallery.ui.album.AlbumScreen
import com.javaram.gallery.ui.home.HomeScreen
import com.javaram.gallery.ui.theme.GalleryTheme
import com.javaram.gallery.ui.trash.TrashScreen

object Routes {
    const val HOME = "home"
    const val ALBUM = "album/{key}"
    const val TRASH = "trash"
    const val SEARCH = "search?q={q}"
    const val MAP = "map"
    fun album(key: String) = "album/$key"
    fun search(q: String = "") = "search?q=" + android.net.Uri.encode(q)
}

@Composable
fun GalleryNav(vm: GalleryViewModel) {
    val nav = rememberNavController()

    Box(Modifier.fillMaxSize().background(GalleryTheme.colors.background)) {
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { fadeIn(tween(240)) + slideInHorizontally(tween(300)) { it / 6 } },
            exitTransition = { fadeOut(tween(180)) + scaleOut(tween(300), targetScale = 0.96f) },
            popEnterTransition = { fadeIn(tween(240)) + scaleIn(tween(300), initialScale = 0.96f) },
            popExitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(300)) { it / 6 } },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    vm = vm,
                    onOpenAlbum = { key -> nav.navigate(Routes.album(key)) },
                    onOpenTrash = { nav.navigate(Routes.TRASH) },
                    onOpenSearch = { nav.navigate(Routes.search()) },
                )
            }
            composable(Routes.ALBUM) { entry ->
                val key = entry.arguments?.getString("key") ?: ""
                AlbumScreen(vm = vm, albumKey = key, onBack = { nav.popBackStack() })
            }
            composable(Routes.TRASH) {
                TrashScreen(vm = vm, onBack = { nav.popBackStack() })
            }
            composable(
                Routes.SEARCH,
                arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" }),
            ) { entry ->
                SearchScreen(
                    vm = vm,
                    initialQuery = entry.arguments?.getString("q") ?: "",
                    onBack = { nav.popBackStack() },
                    onOpenMap = { nav.navigate(Routes.MAP) },
                )
            }
            composable(Routes.MAP) {
                PlacesMapScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                    onPlace = { place -> nav.navigate(Routes.search(place)) },
                )
            }
        }
    }
}
