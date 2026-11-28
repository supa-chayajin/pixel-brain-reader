package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import cloud.wafflecommons.pixelbrainreader.ui.modes.CustodyScreen
import cloud.wafflecommons.pixelbrainreader.ui.modes.LeaveDatesScreen
import cloud.wafflecommons.pixelbrainreader.ui.modes.ModesNamesScreen
import cloud.wafflecommons.pixelbrainreader.ui.modes.PersonalProjectsScreen
import cloud.wafflecommons.pixelbrainreader.ui.modes.ScheduleScreen
import cloud.wafflecommons.pixelbrainreader.ui.modes.WorkFoldersScreen

/** The Settings pages added in 13.4 (S-c to S-i), one group per concern. */
fun NavGraphBuilder.settingsPageDestinations(navController: NavHostController) {
    val back: () -> Unit = { navController.popBackStack() }
    // S-c, Modes
    composable(SettingsRoutes.MODES_NAMES) { ModesNamesScreen(onBack = back) }
    composable(SettingsRoutes.MODES_WORK) { WorkFoldersScreen(onBack = back) }
    composable(SettingsRoutes.MODES_PROJECTS) { PersonalProjectsScreen(onBack = back) }
    composable(SettingsRoutes.MODES_SCHEDULE) { ScheduleScreen(onBack = back) }
    composable(SettingsRoutes.MODES_CUSTODY) { CustodyScreen(onBack = back) }
    composable(SettingsRoutes.MODES_DATES) { LeaveDatesScreen(onBack = back) }

    // S-g, Games
    composable(SettingsRoutes.GAMES) { GamesSettingsScreen(onBack = back, onOpen = { navController.navigate(it) }) }

    // S-h, Privacy & safety
    composable(SettingsRoutes.PRIVATE_JOURNAL) { PrivateJournalSettingsScreen(onBack = back) }
    composable(SettingsRoutes.PERMISSIONS) { PermissionsScreen(onBack = back) }
}
