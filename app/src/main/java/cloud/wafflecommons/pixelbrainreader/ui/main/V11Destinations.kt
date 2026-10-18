package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import cloud.wafflecommons.pixelbrainreader.ui.modes.ModesSettingsScreen

/** Routes added in v11, kept out of MainScreen's NavHost body. */
object V11Routes {
    const val MODES_SETTINGS = "modes_settings"
}

/** The v11 destinations; [openNote] opens a vault note in the Repo pane. */
fun NavGraphBuilder.v11Destinations(navController: NavHostController, openNote: (String) -> Unit) {
    composable(V11Routes.MODES_SETTINGS) {
        ModesSettingsScreen(onBack = { navController.popBackStack() })
    }
}
