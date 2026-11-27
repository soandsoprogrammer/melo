package `in`.melo.presentation.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import `in`.melo.MeloModel
import `in`.melo.presentation.navigation.Screen
import `in`.melo.presentation.navigation.navigateSafely

@Composable
fun HomeScreen(model: MeloModel, navController: NavHostController, paddingValues: PaddingValues) {
    MusicServerScreen(model, paddingValues, showBackButton = false,
        onAppSettings = { navController.navigateSafely(Screen.Settings.route) }, onBack = { })
}
