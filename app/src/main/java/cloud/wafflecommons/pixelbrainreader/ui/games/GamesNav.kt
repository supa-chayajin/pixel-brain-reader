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

    /** Settings › Integrations › Xbox (13.3, XB-E): shown only in a build with the Xbox app. */
    const val XBOX = "xbox_settings"

    /** A game's map (13.2); without a page, the running session's game (its notification's action). */
    const val MAP = "game_map?page={page}&index={index}"

    fun game(path: String): String = "game?page=" + Uri.encode(path)

    fun map(path: String, index: Int = 0): String = "game_map?page=" + Uri.encode(path) + "&index=" + index

    /** True on the map screen: the bars give it the whole window. */
    fun isMap(route: String?): Boolean = route?.startsWith("game_map") == true
}

/**
 * The Games tab, a game's page, its map, and the PlayStation and Xbox settings; [openNote] opens a
 * vault note in the Repo pane.
 */
fun NavGraphBuilder.gamesDestinations(navController: NavHostController, openNote: (String) -> Unit) {
    composable(GamesRoutes.GAMES) {
        GamesScreen(
            onOpenGame = { navController.navigate(GamesRoutes.game(it)) },
            onOpenPsn = { navController.navigate(GamesRoutes.PSN) },
            onOpenXbox = { navController.navigate(GamesRoutes.XBOX) },
            onOpenMap = { navController.navigate(GamesRoutes.map(it)) }
        )
    }
    composable(
        GamesRoutes.GAME,
        arguments = listOf(navArgument("page") { type = NavType.StringType; defaultValue = "" })
    ) {
        GamePageScreen(
            onBack = { navController.popBackStack() },
            onOpenNote = openNote,
            onAskClaude = { root, text -> navController.navigate(V11Routes.handoff(root, text)) },
            onOpenMap = { path, index -> navController.navigate(GamesRoutes.map(path, index)) }
        )
    }
    composable(
        GamesRoutes.MAP,
        arguments = listOf(
            navArgument("page") { type = NavType.StringType; defaultValue = "" },
            navArgument("index") { type = NavType.IntType; defaultValue = 0 }
        )
    ) {
        GameMapScreen(
            onBack = { if (!navController.popBackStack()) navController.navigate(GamesRoutes.GAMES) },
            onOpenGames = { navController.navigate(GamesRoutes.GAMES) }
        )
    }
    composable(GamesRoutes.PSN) {
        PsnSettingsScreen(onBack = { navController.popBackStack() })
    }
    composable(GamesRoutes.XBOX) {
        XboxSettingsScreen(onBack = { navController.popBackStack() })
    }
}
