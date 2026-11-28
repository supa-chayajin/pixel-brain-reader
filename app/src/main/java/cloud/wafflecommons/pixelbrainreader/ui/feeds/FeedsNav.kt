package cloud.wafflecommons.pixelbrainreader.ui.feeds

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import cloud.wafflecommons.pixelbrainreader.ui.main.Screen
import cloud.wafflecommons.pixelbrainreader.ui.settings.FeedsScreen

/** The Feeds routes (13.4). Persisted or linked: never renamed. */
object FeedsRoutes {
    /** The Feeds tab (13.4, F1): its key in the bar's order, persisted. */
    const val FEEDS = "feeds"

    /** The feeds' page (12.12): its route kept. */
    const val SETTINGS = "watch_feeds"
}

/** The Feeds tab and the feeds' page. */
fun NavGraphBuilder.feedsDestinations(navController: NavHostController) {
    composable(FeedsRoutes.FEEDS) {
        FeedsTabScreen(
            onOpenReader = { navController.navigate(Screen.Import) },
            onOpenFeedsSettings = { navController.navigate(FeedsRoutes.SETTINGS) }
        )
    }
    composable(FeedsRoutes.SETTINGS) { FeedsScreen(onNavigateBack = { navController.popBackStack() }) }
}
