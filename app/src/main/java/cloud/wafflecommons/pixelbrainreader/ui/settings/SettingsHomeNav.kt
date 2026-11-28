package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import cloud.wafflecommons.pixelbrainreader.ui.main.Screen

/**
 * The Settings home and the pages that took its inline controls (13.4, S-b): Gemini Nano, the
 * Knowledge vault, Health Connect and Google. [openNote] opens a vault note in the Repo pane (the
 * link check's notes); [onLogout] forgets the token.
 */
fun NavGraphBuilder.settingsHomeDestinations(navController: NavHostController, openNote: (String) -> Unit, onLogout: () -> Unit) {
    composable(Screen.Settings) {
        SettingsScreen(
            onBack = { navController.popBackStack() },
            onOpen = { route -> navController.navigate(route) },
            onOpenNote = { path ->
                openNote(path)
                navController.popBackStack()
            },
            onLogout = onLogout
        )
    }
    composable(SettingsRoutes.NANO) { NanoSettingsScreen(onBack = { navController.popBackStack() }) }
    composable(SettingsRoutes.KNOWLEDGE) { KnowledgeVaultScreen(onBack = { navController.popBackStack() }) }
    composable(SettingsRoutes.HEALTH) { HealthSettingsScreen(onBack = { navController.popBackStack() }) }
    composable(SettingsRoutes.GOOGLE) { GoogleSettingsScreen(onBack = { navController.popBackStack() }) }
}
