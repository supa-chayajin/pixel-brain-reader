package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import cloud.wafflecommons.pixelbrainreader.ui.guard.ConfidentialityScreen
import cloud.wafflecommons.pixelbrainreader.ui.modes.ModesSettingsScreen
import cloud.wafflecommons.pixelbrainreader.ui.work.EvidenceScreen
import cloud.wafflecommons.pixelbrainreader.ui.work.LeaveScreen
import cloud.wafflecommons.pixelbrainreader.ui.work.MissionLogScreen
import cloud.wafflecommons.pixelbrainreader.ui.work.WorkHubScreen

/** Routes added in v11, kept out of MainScreen's NavHost body. */
object V11Routes {
    const val MODES_SETTINGS = "modes_settings"
    const val CONFIDENTIALITY = "confidentiality"
    const val WORK_HUB = "work_hub"
    const val LEAVE = "leave"
    const val EVIDENCE = "evidence"
    const val MISSION_LOG = "mission_log?add={add}"

    fun missionLog(add: Boolean = false) = "mission_log?add=$add"
}

/** The v11 destinations; [openNote] opens a vault note in the Repo pane. */
fun NavGraphBuilder.v11Destinations(navController: NavHostController, openNote: (String) -> Unit) {
    composable(V11Routes.MODES_SETTINGS) {
        ModesSettingsScreen(onBack = { navController.popBackStack() })
    }
    composable(V11Routes.CONFIDENTIALITY) {
        ConfidentialityScreen(onBack = { navController.popBackStack() }, onOpenNote = openNote)
    }
    composable(V11Routes.WORK_HUB) {
        WorkHubScreen(
            onBack = { navController.popBackStack() },
            onOpenLog = { add -> navController.navigate(V11Routes.missionLog(add)) },
            onOpen = { route -> navController.navigate(route) }
        )
    }
    composable(V11Routes.EVIDENCE) {
        EvidenceScreen(onBack = { navController.popBackStack() }, onOpenNote = openNote)
    }
    composable(V11Routes.LEAVE) {
        LeaveScreen(
            onBack = { navController.popBackStack() },
            onOpenNote = openNote,
            onManageLeave = { navController.navigate(V11Routes.MODES_SETTINGS) }
        )
    }
    composable(
        V11Routes.MISSION_LOG,
        arguments = listOf(navArgument("add") { type = NavType.BoolType; defaultValue = false })
    ) { entry ->
        MissionLogScreen(
            startWithAdd = entry.arguments?.getBoolean("add") ?: false,
            onBack = { navController.popBackStack() }
        )
    }
}
