package cloud.wafflecommons.pixelbrainreader.ui.games

import android.net.Uri
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes

/** The Games routes (12.17, RFC-009). `games` is a navigation tab (the 8th, D4). */
object GamesRoutes {
    const val GAMES = "games"
    const val GAME = "game?page={page}"
    const val PSN = "psn_settings"

    fun game(path: String): String = "game?page=" + Uri.encode(path)
}

/** The Games tab, a game's page and the PlayStation settings; [openNote] opens a vault note in the Repo pane. */
fun NavGraphBuilder.gamesDestinations(navController: NavHostController, openNote: (String) -> Unit) {
    composable(GamesRoutes.GAMES) {
        GamesScreen(
            onOpenGame = { navController.navigate(GamesRoutes.game(it)) },
            onOpenPsn = { navController.navigate(GamesRoutes.PSN) }
        )
    }
    composable(
        GamesRoutes.GAME,
        arguments = listOf(navArgument("page") { type = NavType.StringType; defaultValue = "" })
    ) {
        GamePageScreen(
            onBack = { navController.popBackStack() },
            onOpenNote = openNote,
            onAskClaude = { root, text -> navController.navigate(V11Routes.handoff(root, text)) }
        )
    }
    composable(GamesRoutes.PSN) {
        PsnSettingsScreen(onBack = { navController.popBackStack() })
    }
}
