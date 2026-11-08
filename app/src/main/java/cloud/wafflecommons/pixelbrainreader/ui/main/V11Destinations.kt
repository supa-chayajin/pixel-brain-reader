package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.ViewKanban
import androidx.navigation.NavGraphBuilder
import cloud.wafflecommons.pixelbrainreader.ui.components.HubEmptyPane
import cloud.wafflecommons.pixelbrainreader.ui.components.HubListDetail
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import cloud.wafflecommons.pixelbrainreader.ui.guard.ConfidentialityScreen
import cloud.wafflecommons.pixelbrainreader.ui.modes.ModesSettingsScreen
import cloud.wafflecommons.pixelbrainreader.ui.work.ActivityScreen
import cloud.wafflecommons.pixelbrainreader.ui.work.BoardScreen
import cloud.wafflecommons.pixelbrainreader.ui.work.BoardsScreen
import cloud.wafflecommons.pixelbrainreader.ui.work.EvidenceScreen
import cloud.wafflecommons.pixelbrainreader.ui.work.InboxScreen
import cloud.wafflecommons.pixelbrainreader.ui.work.LeaveScreen
import cloud.wafflecommons.pixelbrainreader.ui.work.LoadRecoveryScreen
import cloud.wafflecommons.pixelbrainreader.ui.work.MeetingsScreen
import cloud.wafflecommons.pixelbrainreader.ui.work.MissionLogScreen
import cloud.wafflecommons.pixelbrainreader.ui.work.NextActionsScreen
import cloud.wafflecommons.pixelbrainreader.ui.work.ObjectivesScreen
import cloud.wafflecommons.pixelbrainreader.ui.work.RunbookPlayerScreen
import cloud.wafflecommons.pixelbrainreader.ui.work.RunbooksScreen
import cloud.wafflecommons.pixelbrainreader.ui.work.SnippetsScreen
import cloud.wafflecommons.pixelbrainreader.ui.work.WorkHubScreen

/** Routes added in v11, kept out of MainScreen's NavHost body. */
object V11Routes {
    const val MODES_SETTINGS = "modes_settings"
    const val CONFIDENTIALITY = "confidentiality"
    const val WORK_HUB = "work_hub"
    const val LEAVE = "leave"
    const val EVIDENCE = "evidence"
    const val BOARDS = "boards"
    const val OBJECTIVES = "objectives"
    const val MEETINGS = "meetings"
    const val LOAD = "load_recovery"
    const val SNIPPETS = "snippets"
    const val ACTIVITY = "activity"
    const val INBOX = "inbox_filing"
    const val RUNBOOKS = "runbooks"
    const val RUNBOOK = "runbook?path={path}"

    fun runbook(path: String) = "runbook?path=" + android.net.Uri.encode(path)
    const val NEXT_ACTIONS = "next_actions"
    const val BOARD = "board?folder={folder}"

    fun board(folder: String) = "board?folder=" + android.net.Uri.encode(folder)
    const val PROJECTS = "projects"
    const val PROJECT = "project?root={root}"

    fun project(root: String) = "project?root=" + android.net.Uri.encode(root)
    const val MISSION_LOG = "mission_log?add={add}"
    const val HANDOFF = "handoff?root={root}&text={text}"

    /** The hand-off composer, optionally on [root] with [text] already typed (a note's link, a shared page). */
    fun handoff(root: String? = null, text: String = "") =
        "handoff?root=" + android.net.Uri.encode(root.orEmpty()) + "&text=" + android.net.Uri.encode(text)

    fun missionLog(add: Boolean = false) = "mission_log?add=$add"
}

/**
 * The v11 destinations; [openNote] opens a vault note in the Repo pane, [onHandoffSent] leaves the
 * hand-off composer (back, or out of the app when it came from another app's share sheet).
 */
fun NavGraphBuilder.v11Destinations(navController: NavHostController, openNote: (String) -> Unit, onHandoffSent: () -> Unit) {
    composable(
        V11Routes.HANDOFF,
        arguments = listOf(
            navArgument("root") { type = NavType.StringType; defaultValue = "" },
            navArgument("text") { type = NavType.StringType; defaultValue = "" }
        )
    ) { entry ->
        cloud.wafflecommons.pixelbrainreader.ui.work.HandoffScreen(
            initialRoot = entry.arguments?.getString("root"),
            initialText = entry.arguments?.getString("text").orEmpty(),
            onBack = { navController.popBackStack() },
            onSent = onHandoffSent
        )
    }
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
    // Projects, Boards and Runbooks: the list and the open page side by side on a wide window, the
    // page over the list otherwise (HubListDetail). Their own routes below still open one page directly.
    composable(V11Routes.PROJECTS) {
        HubListDetail(
            list = { open, onOpen ->
                cloud.wafflecommons.pixelbrainreader.ui.work.ProjectsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenProject = onOpen,
                    selectedRoot = open
                )
            },
            detail = { root, onClose ->
                cloud.wafflecommons.pixelbrainreader.ui.work.ProjectScreen(root = root, onBack = onClose, onOpenNote = openNote)
            },
            emptyDetail = { HubEmptyPane(Icons.Rounded.RocketLaunch, "Pick a project to see its page here.") }
        )
    }
    composable(V11Routes.PROJECT, arguments = listOf(navArgument("root") { type = NavType.StringType; defaultValue = "" })) { entry ->
        cloud.wafflecommons.pixelbrainreader.ui.work.ProjectScreen(
            root = entry.arguments?.getString("root").orEmpty(),
            onBack = { navController.popBackStack() },
            onOpenNote = openNote
        )
    }
    composable(V11Routes.BOARDS) {
        HubListDetail(
            list = { open, onOpen -> BoardsScreen(onBack = { navController.popBackStack() }, onOpenBoard = onOpen, selected = open) },
            detail = { folder, onClose -> BoardScreen(folder = folder, onBack = onClose, onOpenNote = openNote) },
            emptyDetail = { HubEmptyPane(Icons.Rounded.ViewKanban, "Pick a board to see its columns here.") }
        )
    }
    composable(V11Routes.BOARD, arguments = listOf(navArgument("folder") { type = NavType.StringType; defaultValue = "" })) { entry ->
        BoardScreen(
            folder = entry.arguments?.getString("folder").orEmpty(),
            onBack = { navController.popBackStack() },
            onOpenNote = openNote
        )
    }
    composable(V11Routes.INBOX) {
        InboxScreen(onBack = { navController.popBackStack() }, onOpenNote = openNote)
    }
    composable(V11Routes.ACTIVITY) {
        ActivityScreen(onBack = { navController.popBackStack() }, onOpenNote = openNote)
    }
    composable(V11Routes.SNIPPETS) {
        SnippetsScreen(
            onBack = { navController.popBackStack() },
            onOpenNote = openNote,
            onOpenRunbooks = { navController.navigate(V11Routes.RUNBOOKS) }
        )
    }
    composable(V11Routes.RUNBOOKS) {
        HubListDetail(
            list = { open, onOpen -> RunbooksScreen(onBack = { navController.popBackStack() }, onPlay = onOpen, selected = open) },
            detail = { path, onClose -> RunbookPlayerScreen(path = path, onBack = onClose, onOpenNote = openNote) },
            emptyDetail = { HubEmptyPane(Icons.AutoMirrored.Rounded.PlaylistPlay, "Pick a runbook to play it here.") }
        )
    }
    composable(V11Routes.RUNBOOK, arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" })) { entry ->
        RunbookPlayerScreen(path = entry.arguments?.getString("path").orEmpty(), onBack = { navController.popBackStack() }, onOpenNote = openNote)
    }
    composable(V11Routes.LOAD) {
        LoadRecoveryScreen(
            onBack = { navController.popBackStack() },
            onOpenLog = { navController.navigate(V11Routes.missionLog()) },
            onOpenActions = { navController.navigate(V11Routes.NEXT_ACTIONS) },
            onOpenChat = { navController.navigate(Screen.Chat) }
        )
    }
    composable(V11Routes.MEETINGS) {
        MeetingsScreen(
            onBack = { navController.popBackStack() },
            onOpenNote = openNote,
            onOpenActions = { navController.navigate(V11Routes.NEXT_ACTIONS) }
        )
    }
    composable(V11Routes.NEXT_ACTIONS) {
        NextActionsScreen(onBack = { navController.popBackStack() }, onOpenNote = openNote)
    }
    composable(V11Routes.OBJECTIVES) {
        ObjectivesScreen(
            onBack = { navController.popBackStack() },
            onOpenNote = openNote,
            onOpenProject = { navController.navigate(V11Routes.project(it)) },
            onOpenChat = { navController.navigate(Screen.Chat) }
        )
    }
    composable(V11Routes.EVIDENCE) {
        EvidenceScreen(onBack = { navController.popBackStack() }, onOpenNote = openNote, onOpenChat = { navController.navigate(Screen.Chat) })
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
            onBack = { navController.popBackStack() },
            onOpenNote = openNote
        )
    }
}
