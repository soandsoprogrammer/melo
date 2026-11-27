package `in`.melo.presentation.navigation

import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import `in`.melo.presentation.utils.rememberReducedMotion
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import `in`.melo.MeloModel
import `in`.melo.data.preferences.AppSettings
import `in`.melo.presentation.screens.AlbumDetailScreen
import `in`.melo.presentation.screens.ArtistDetailScreen
import `in`.melo.presentation.screens.HomeScreen
import `in`.melo.presentation.screens.LibraryScreen
import `in`.melo.presentation.screens.MusicServerScreen
import `in`.melo.presentation.screens.PlaylistDetailScreen
import `in`.melo.presentation.screens.SearchScreen
import `in`.melo.presentation.screens.EqualizerScreen
import `in`.melo.presentation.screens.SettingsCategoryScreen
import `in`.melo.presentation.screens.SettingsScreen
import java.net.URLDecoder

@Composable
fun AppNavigation(
    model: MeloModel,
    navController: NavHostController,
    paddingValues: PaddingValues,
    onSearchBarActiveChange: (Boolean) -> Unit,
) {
    // The tab chosen in the settings applies at the next start, not while navigating.
    val defaultTab = androidx.compose.runtime.remember { model.ui.settings.value.defaultTab }
    val reducedMotion = rememberReducedMotion()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val transitions = remember(reducedMotion, density, layoutDirection) {
        PageTransitions(
            reducedMotion = reducedMotion,
            forwardSign = if (layoutDirection == LayoutDirection.Ltr) 1 else -1,
            tabDistancePx = with(density) { 24.dp.roundToPx() },
            detailDistancePx = with(density) { 48.dp.roundToPx() },
        )
    }
    NavHost(
        navController = navController,
        startDestination = when (defaultTab) {
            AppSettings.TAB_SEARCH -> Screen.Search.route
            AppSettings.TAB_LIBRARY -> Screen.Library.route
            else -> Screen.Home.route
        },
        enterTransition = { transitions.enter(initialState.destination.route, targetState.destination.route) },
        exitTransition = { transitions.exit(initialState.destination.route, targetState.destination.route) },
        popEnterTransition = { transitions.enter(initialState.destination.route, targetState.destination.route, isPop = true) },
        popExitTransition = { transitions.exit(initialState.destination.route, targetState.destination.route, isPop = true) },
    ) {
        composable(Screen.Home.route) { HomeScreen(model, navController, paddingValues) }
        composable(Screen.Search.route) { SearchScreen(model, navController, paddingValues, onSearchBarActiveChange) }
        composable(Screen.Library.route) { LibraryScreen(model, navController, paddingValues) }
        composable(Screen.MusicServer.route) {
            MusicServerScreen(model, paddingValues) { navController.popBackStack() }
        }
        composable(Screen.Settings.route) { SettingsScreen(model, navController) }
        composable(Screen.History.route) { `in`.melo.presentation.screens.HistoryScreen(model, navController) }
        composable(Screen.DiscordLogin.route) { `in`.melo.presentation.screens.DiscordLoginScreen(model, navController) }
        composable(
            Screen.Convert.route,
            arguments = listOf(navArgument("playlistId") { type = NavType.StringType }, navArgument("target") { type = NavType.StringType }),
        ) { entry ->
            val id = URLDecoder.decode(entry.arguments?.getString("playlistId").orEmpty(), "UTF-8")
            `in`.melo.presentation.screens.ConvertScreen(model, navController, id, entry.arguments?.getString("target").orEmpty())
        }
        composable(Screen.Equalizer.route) { EqualizerScreen(navController = navController, playerViewModel = model.ui) }
        composable(
            Screen.SettingsCategory.route,
            arguments = listOf(navArgument("categoryId") { type = NavType.StringType }),
        ) { entry -> SettingsCategoryScreen(model, navController, entry.arguments?.getString("categoryId").orEmpty()) }
        composable(
            Screen.PlaylistDetail.route,
            arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
        ) { entry ->
            val id = URLDecoder.decode(entry.arguments?.getString("playlistId").orEmpty(), "UTF-8")
            PlaylistDetailScreen(model, navController, id)
        }
        composable(
            Screen.AlbumDetail.route,
            arguments = listOf(navArgument("albumId") { type = NavType.LongType }),
        ) { entry -> AlbumDetailScreen(model, navController, entry.arguments?.getLong("albumId") ?: -1L) }
        composable(
            Screen.ArtistDetail.route,
            arguments = listOf(navArgument("artistId") { type = NavType.LongType }),
        ) { entry -> ArtistDetailScreen(model, navController, entry.arguments?.getLong("artistId") ?: -1L) }
    }
}
