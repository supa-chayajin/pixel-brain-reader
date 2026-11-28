package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.ui.graphics.vector.ImageVector
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeTabs
import cloud.wafflecommons.pixelbrainreader.ui.feeds.FeedsRoutes
import cloud.wafflecommons.pixelbrainreader.ui.games.GamesRoutes

/**
 * The bar's tabs (13.4): their persisted key, name and icons, one list for the bar, the rail and
 * Settings › Navigation, so a tab is named and drawn the same everywhere. The keys are the bar's
 * order keys (`navbar_order`, `ModesConfig.hiddenTabs`): persisted, never renamed. Daily is not a
 * tab: it keeps its own button. Pure (the icons are plain vectors).
 */
object NavTabs {

    /** One tab: [icon] while it is open, [iconOff] (outlined) while it isn't. */
    data class Tab(val key: String, val label: String, val icon: ImageVector, val iconOff: ImageVector)

    /**
     * In DEFAULT_NAVBAR_ORDER's order: home Repo, habits Habits, home_os Chores, chat Chat, mood
     * Mood, stats Stats, projects Projects, games Games, feeds Feeds (13.4, F1: the last one).
     */
    val ALL: List<Tab> = listOf(
        Tab(Screen.Home, "Repo", Icons.Rounded.Dashboard, Icons.Outlined.Dashboard),
        Tab("habits", "Habits", Icons.Rounded.DateRange, Icons.Outlined.DateRange),
        Tab(Screen.HomeOS, "Chores", Icons.Rounded.CleaningServices, Icons.Outlined.CleaningServices),
        Tab(Screen.Chat, "Chat", Icons.Rounded.Psychology, Icons.Outlined.Psychology),
        Tab(Screen.MoodTracker, "Mood", Icons.Rounded.Mood, Icons.Outlined.Mood),
        Tab(Screen.Stats, "Stats", Icons.Rounded.Star, Icons.Rounded.StarOutline),
        Tab(V11Routes.PROJECTS, "Projects", Icons.Rounded.RocketLaunch, Icons.Outlined.RocketLaunch),
        Tab(GamesRoutes.GAMES, "Games", Icons.Rounded.SportsEsports, Icons.Outlined.SportsEsports),
        Tab(FeedsRoutes.FEEDS, "Feeds", Icons.Rounded.RssFeed, Icons.Outlined.RssFeed)
    )

    private val byKey: Map<String, Tab> = ALL.associateBy { it.key }

    /** The tab of [key], or null for a key this version doesn't know. */
    fun of(key: String): Tab? = byKey[key]

    /**
     * A key's name in Settings: the tab's, or "<toolkit> (Daily's chip)" for [ModeTabs.TOOLKIT]
     * (the owner's name for the hub, never a hard-coded one); an unknown key reads as itself.
     */
    fun label(key: String, toolkitName: String): String = when (key) {
        ModeTabs.TOOLKIT -> "$toolkitName (Daily's chip)"
        else -> of(key)?.label ?: key
    }
}
