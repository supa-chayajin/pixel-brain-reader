package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeAppearance
import cloud.wafflecommons.pixelbrainreader.ui.feeds.FeedsRoutes
import cloud.wafflecommons.pixelbrainreader.ui.games.GamesRoutes
import cloud.wafflecommons.pixelbrainreader.ui.main.Screen
import cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes

/**
 * The Settings routes (13.4). The ones before 13.4 keep their strings: links, deep links and the
 * Quick Settings long-presses still open them. None takes an argument: the search's "open at that
 * setting" is a one-shot hand-off ([SettingsFocus]), so no route changes its pattern.
 */
object SettingsRoutes {
    const val HOME = "settings"
    const val DAILY_LAYOUT = "daily_layout"

    /** The Navigation page (S3), on its 12.x route. */
    const val NAVIGATION = "navbar_reorder"
    const val REMINDERS = "reminders"
    const val HABITS = "habit_config"
    const val HOME_CHORES = Screen.ROUTE_HOME_CONFIG

    /** The Moods page (S10), on the mood tags' 12.x route. */
    const val MOODS = "mood_tags"
    const val RPG = "gamification_settings"
    const val FEEDS = FeedsRoutes.SETTINGS
    const val PRIVACY_LEDGER = PRIVACY_LEDGER_ROUTE

    /** The Modes hub (S2), on the Modes page's 12.x route. */
    const val MODES = V11Routes.MODES_SETTINGS
    const val CONFIDENTIALITY = V11Routes.CONFIDENTIALITY
    const val PSN = GamesRoutes.PSN
    const val XBOX = GamesRoutes.XBOX
    const val SYNC_CONFLICTS = V11Routes.SYNC_CONFLICTS
    const val MODES_NAMES = "settings_modes_names"
    const val MODES_WORK = "settings_modes_work"
    const val MODES_PROJECTS = "settings_modes_projects"
    const val MODES_SCHEDULE = "settings_modes_schedule"
    const val MODES_CUSTODY = "settings_modes_custody"
    const val MODES_DATES = "settings_modes_dates"
    const val GAMES = "settings_games"
    const val PRIVATE_JOURNAL = "settings_private_journal"
    const val PERMISSIONS = "settings_permissions"
    const val REPAIR = "settings_repair"
    const val DEVICE = "settings_device"
    const val NANO = "settings_nano"
    const val KNOWLEDGE = "settings_knowledge"
    const val HEALTH = "settings_health"
    const val GOOGLE = "settings_google"
}

/** The eight topics of the home, in the owner's order (S1): nothing folded, nothing moved. */
enum class SettingsTopic(val title: String, val icon: ImageVector) {
    YOUR_DAY("Your day", Icons.Rounded.WbSunny),
    MODES("Modes", Icons.Rounded.Layers),
    LIFE_OS("Life OS", Icons.Rounded.SelfImprovement),
    TOOLKIT("Toolkit", Icons.Rounded.Apps),
    CONNECTIONS("Connections", Icons.Rounded.Cable),
    AI_SEARCH("AI & search", Icons.Rounded.AutoAwesome),
    PRIVACY("Privacy & safety", Icons.Rounded.PrivacyTip),
    VAULT_DEVICE("Vault & device", Icons.Rounded.Storage);

    /** The Toolkit takes the owner's name for it; the others keep their [title]. */
    fun titleFor(appearance: ModeAppearance): String = if (this == TOOLKIT) appearance.toolkitName else title
}

/**
 * Every Settings page: its route, its title and icon (the ones the row that opens it shows), its
 * topic; [parent] is the header's pill when it isn't the topic (the Modes hub's "Settings", the
 * consoles' "Games").
 */
enum class SettingsPage(
    val route: String,
    val title: String,
    val icon: ImageVector,
    val topic: SettingsTopic,
    val parent: String? = null
) {
    DAILY_LAYOUT(SettingsRoutes.DAILY_LAYOUT, "Daily screen", Icons.Rounded.ViewAgenda, SettingsTopic.YOUR_DAY),
    NAVIGATION(SettingsRoutes.NAVIGATION, "Navigation", Icons.Rounded.SwapVert, SettingsTopic.YOUR_DAY),
    REMINDERS(SettingsRoutes.REMINDERS, "Reminders", Icons.Rounded.NotificationsActive, SettingsTopic.YOUR_DAY),
    MODES(SettingsRoutes.MODES, "Modes", Icons.Rounded.Layers, SettingsTopic.MODES, parent = "Settings"),
    MODES_NAMES(SettingsRoutes.MODES_NAMES, "Names & icons", Icons.Rounded.Edit, SettingsTopic.MODES),
    MODES_WORK(SettingsRoutes.MODES_WORK, "What counts as work", Icons.Rounded.FolderShared, SettingsTopic.MODES),
    MODES_PROJECTS(SettingsRoutes.MODES_PROJECTS, "Personal projects", Icons.Rounded.RocketLaunch, SettingsTopic.MODES),
    MODES_SCHEDULE(SettingsRoutes.MODES_SCHEDULE, "Schedule", Icons.Rounded.Schedule, SettingsTopic.MODES),
    MODES_CUSTODY(SettingsRoutes.MODES_CUSTODY, "Shared custody", Icons.Rounded.FamilyRestroom, SettingsTopic.MODES),
    MODES_DATES(SettingsRoutes.MODES_DATES, "Leave & key dates", Icons.Rounded.BeachAccess, SettingsTopic.MODES),
    HABITS(SettingsRoutes.HABITS, "Habits", Icons.Rounded.DateRange, SettingsTopic.LIFE_OS),
    HOME_CHORES(SettingsRoutes.HOME_CHORES, "Home & chores", Icons.Rounded.CleaningServices, SettingsTopic.LIFE_OS),
    MOODS(SettingsRoutes.MOODS, "Moods", Icons.Rounded.Mood, SettingsTopic.LIFE_OS),
    RPG(SettingsRoutes.RPG, "RPG rules", Icons.Rounded.EmojiEvents, SettingsTopic.LIFE_OS),
    HEALTH(SettingsRoutes.HEALTH, "Health Connect", Icons.Rounded.HealthAndSafety, SettingsTopic.CONNECTIONS),
    GOOGLE(SettingsRoutes.GOOGLE, "Google Calendar & Tasks", Icons.Rounded.CalendarMonth, SettingsTopic.CONNECTIONS),
    FEEDS(SettingsRoutes.FEEDS, "Feeds", Icons.Rounded.RssFeed, SettingsTopic.CONNECTIONS),
    GAMES(SettingsRoutes.GAMES, "Games", Icons.Rounded.SportsEsports, SettingsTopic.CONNECTIONS),
    PSN(SettingsRoutes.PSN, "PlayStation", Icons.Rounded.VideogameAsset, SettingsTopic.CONNECTIONS, parent = "Games"),
    XBOX(SettingsRoutes.XBOX, "Xbox", Icons.Rounded.Gamepad, SettingsTopic.CONNECTIONS, parent = "Games"),
    NANO(SettingsRoutes.NANO, "Gemini Nano", Icons.Rounded.Psychology, SettingsTopic.AI_SEARCH),
    KNOWLEDGE(SettingsRoutes.KNOWLEDGE, "Knowledge vault", Icons.Rounded.Hub, SettingsTopic.AI_SEARCH),
    PRIVACY_LEDGER(SettingsRoutes.PRIVACY_LEDGER, "Privacy ledger", Icons.Rounded.Policy, SettingsTopic.PRIVACY),
    CONFIDENTIALITY(SettingsRoutes.CONFIDENTIALITY, "Confidentiality guard", Icons.Rounded.Shield, SettingsTopic.PRIVACY),
    PRIVATE_JOURNAL(SettingsRoutes.PRIVATE_JOURNAL, "Private journal", Icons.Rounded.Lock, SettingsTopic.PRIVACY),
    PERMISSIONS(SettingsRoutes.PERMISSIONS, "Permissions", Icons.Rounded.AdminPanelSettings, SettingsTopic.PRIVACY),
    REPAIR(SettingsRoutes.REPAIR, "Repair", Icons.Rounded.Build, SettingsTopic.VAULT_DEVICE),
    SYNC_CONFLICTS(SettingsRoutes.SYNC_CONFLICTS, "Sync conflict", Icons.Rounded.SyncProblem, SettingsTopic.VAULT_DEVICE),
    DEVICE(SettingsRoutes.DEVICE, "This device & version", Icons.Rounded.PhoneAndroid, SettingsTopic.VAULT_DEVICE);

    /** The Personal mode's projects take its name ("<Personal> projects"); every other page its [title]. */
    fun titleFor(appearance: ModeAppearance): String =
        if (this == MODES_PROJECTS) "${appearance.label(ContextMode.PERSONAL)} projects" else title

    /** The header's pill: [parent], else the topic's title. */
    val subtitle: String get() = parent ?: topic.title
}

/**
 * The anchors the search opens a page at (13.4, S6): lowercase words joined by "_", stable. A
 * Permissions row uses its `AppPermission.key` as its anchor.
 */
object SettingsAnchors {
    const val CHECK_IN = "check_in"
    const val VAULT_REMINDER = "vault_reminder"
    const val MEETING_PREP = "meeting_prep"
    const val ONE_ON_ONE = "one_on_one"
    const val REVIEWS = "reviews"
    const val CHORES = "chores"
    const val TAB_ORDER = "tab_order"
    const val SHOWS_IN = "shows_in"
    const val RAIL_NAMES = "rail_names"
    const val FACES = "faces"
    const val TAGS = "tags"
    const val GOALS = "goals"
    const val TAG_RULES = "tag_rules"
    const val GAME_NIGHT = "game_night"
    const val TILE_UNLOCK = "tile_unlock"
    const val AUTO_LOCK = "auto_lock"
    const val PRIVATE_AI = "private_ai"
    const val PASSWORD = "password"
    const val RELOAD = "reload"
    const val WRITE_BACK = "write_back"
    const val VERSION = "version"
    const val ROLE = "role"
    const val MODEL = "model"
    const val SELF_TEST = "self_test"
    const val INDEX = "index"
    const val HYBRID = "hybrid"
    const val MEASURE = "measure"
    const val RELATED = "related"
}
