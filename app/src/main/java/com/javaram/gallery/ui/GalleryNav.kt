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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.navArgument
import com.javaram.gallery.ui.people.PeopleScreen
import com.javaram.gallery.ui.people.PersonScreen
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
    const val PEOPLE = "people"
    const val PERSON = "person/{id}"
    fun person(id: Long) = "person/$id"
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
                    onOpenPeople = { nav.navigate(Routes.PEOPLE) },
                    onOpenPerson = { nav.navigate(Routes.person(it)) },
                    onOpenRoute = { nav.navigate(it) },
                )
            }
            composable(Routes.ALBUM) { entry ->
                val key = entry.arguments?.getString("key") ?: ""
                AlbumScreen(
                    vm = vm, albumKey = key, onBack = { nav.popBackStack() },
                    onPlay = { t, list -> vm.playSlideshow(t, list); nav.navigate("slideshow") },
                )
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
                    onOpenPerson = { nav.navigate(Routes.person(it)) },
                )
            }
            composable(Routes.PEOPLE) {
                PeopleScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                    onOpenPerson = { nav.navigate(Routes.person(it)) },
                )
            }
            composable(
                Routes.PERSON,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                PersonScreen(
                    vm = vm,
                    personId = entry.arguments?.getLong("id") ?: -1L,
                    onBack = { nav.popBackStack() },
                )
            }
            composable("albumpick/{name}") { entry ->
                com.javaram.gallery.ui.album.AlbumPickScreen(
                    vm, name = entry.arguments?.getString("name") ?: "New album",
                    onBack = { nav.popBackStack() },
                )
            }
            composable("collage") { com.javaram.gallery.ui.create.CollageScreen(vm, onBack = { nav.popBackStack() }) }
            composable("editpick") { com.javaram.gallery.ui.create.EditPickScreen(vm, onBack = { nav.popBackStack() }) }
            composable("safe") { com.javaram.gallery.ui.safe.SafeScreen(vm, onBack = { nav.popBackStack() }) }
            composable("cleanup") {
                com.javaram.gallery.ui.cleanup.CleanupScreen(
                    vm, onBack = { nav.popBackStack() },
                    onOpen = { k -> nav.navigate("cleanup/${k.name}") },
                )
            }
            composable("cleanup/{kind}") { entry ->
                val kind = runCatching {
                    com.javaram.gallery.search.CleanupKind.valueOf(entry.arguments?.getString("kind") ?: "")
                }.getOrDefault(com.javaram.gallery.search.CleanupKind.Duplicates)
                com.javaram.gallery.ui.cleanup.CleanupReviewScreen(vm, kind, onBack = { nav.popBackStack() })
            }
            composable(
                "slideshow",
                enterTransition = { fadeIn(tween(400)) },
                exitTransition = { fadeOut(tween(300)) },
                popEnterTransition = { fadeIn(tween(300)) },
                popExitTransition = { fadeOut(tween(300)) },
            ) {
                val show by vm.slideshow.collectAsStateWithLifecycle()
                val s = show
                if (s == null) androidx.compose.runtime.LaunchedEffect(Unit) { nav.popBackStack() }
                else com.javaram.gallery.ui.create.SlideshowScreen(s.first, s.second, onClose = { nav.popBackStack() })
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
