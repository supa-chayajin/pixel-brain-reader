package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Diversity3
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.ui.graphics.vector.ImageVector
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeAppearance
import cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes
import cloud.wafflecommons.pixelbrainreader.ui.work.HubTool

/** What an entry is: a row opening a page, a device switch flipped in place, or an action run in place. */
enum class EntryKind { PAGE, SWITCH, ACTION }

/** When an entry exists. */
enum class EntryShown { ALWAYS, WRITING_DEVICE, PSN_BUILD, XBOX_BUILD, CONFLICT_PENDING }

/** One setting the home and the search know (13.4, S1 and S6). [title]/[description] may hold {work}, {personal}, {off}, {toolkit}. */
data class SettingsEntry(
    val key: String,
    val topic: SettingsTopic,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val kind: EntryKind = EntryKind.PAGE,
    /** Where it opens (a concrete route); null for SWITCH and ACTION. */
    val route: String? = null,
    /** The row on that page the search lights (SettingsAnchors). */
    val anchor: String? = null,
    /** The page an entry of a page sits on; null for a row of the home. */
    val page: SettingsPage? = null,
    val keywords: List<String> = emptyList(),
    val shown: EntryShown = EntryShown.ALWAYS
) {
    val onHome: Boolean get() = page == null
}

/** What the home and the search depend on, on this device and now. */
data class SettingsContext(
    val appearance: ModeAppearance = ModeAppearance(),
    val readOnly: Boolean = false,
    val psn: Boolean = false,
    val xbox: Boolean = false,
    val conflictPending: Boolean = false
)

/**
 * Every setting the Settings home shows and its search finds (13.4, S1 and S6): the home's rows in
 * the owner's approved map, then the rows of the pages, each with the anchor the search opens its
 * page at. A row that opens a page takes that page's title and icon; the toolkit's rows open their
 * features (S4). Pure: no Android type.
 */
object SettingsIndex {
    const val SOUND_EFFECTS = "sound_effects"
    const val SCREEN_SHARE_SAFE = "screen_share_safe"
    const val VAULT_LINKS = "vault_links"
    const val LOG_OUT = "log_out"
    const val SYNC_CONFLICT = "sync_conflict"

    /** This device & version: its home row shows the version and the device's role. */
    const val DEVICE = "device"

    // --- The home (S1): the 30 rows of the approved map, topic by topic, in the owner's order ---

    private val HOME: List<SettingsEntry> = listOf(
        // Your day
        homeRow("daily_screen", SettingsPage.DAILY_LAYOUT, "The order of Daily's blocks and which stay on screen"),
        homeRow(
            "navigation", SettingsPage.NAVIGATION, "The tabs' order, the modes each one shows in, the rail's names",
            keywords = words("tabs bar rail order")
        ),
        homeRow(
            "reminders", SettingsPage.REMINDERS,
            "The evening check-in, the journal reminder, meeting prep, 1:1s, reviews, chores & habits",
            keywords = words("notifications")
        ),
        SettingsEntry(
            SOUND_EFFECTS, SettingsTopic.YOUR_DAY, "Sound effects", "A short sound when you complete a habit or a task",
            Icons.AutoMirrored.Rounded.VolumeUp, EntryKind.SWITCH
        ),
        // Modes
        homeRow("modes", SettingsPage.MODES, "Names & icons, what counts as work, schedule, shared custody, leave & key dates"),
        SettingsEntry(
            SCREEN_SHARE_SAFE, SettingsTopic.MODES, "Screen-share safe",
            "Hides work, private and health content everywhere until you turn it off",
            Icons.Rounded.VisibilityOff, EntryKind.SWITCH, keywords = words("presentation privacy")
        ),
        // Life OS
        homeRow("habits", SettingsPage.HABITS, "Add and edit habits; some tick themselves from Health Connect"),
        homeRow("home_chores", SettingsPage.HOME_CHORES, "Rooms and chores, and how often each one comes back"),
        homeRow("moods", SettingsPage.MOODS, "The faces of your moods and the tags you add to them", keywords = words("emoji faces tags")),
        homeRow("rpg", SettingsPage.RPG, "Health goals and which tags feed which stat", keywords = words("xp stats gamification")),
        // The toolkit, under the owner's name for it: each row opens its feature (S4)
        feature("mission_log", "Mission log", "Where the log lives, its entry types and templates", Icons.Rounded.EditNote, V11Routes.missionLog()),
        feature("meetings", "Meetings", "Meeting notes, decisions and the actions they hold", HubTool.MEETINGS.icon, V11Routes.MEETINGS),
        feature("boards", "Boards", "Boards, their columns and work-in-progress limits", HubTool.BOARDS.icon, V11Routes.BOARDS),
        feature("projects", "Projects", "Pin, order and hide projects", HubTool.PROJECTS.icon, V11Routes.PROJECTS),
        // The hub's own emblem: the row opens the hub, whose pins it describes.
        feature("pinned_tools", "Pinned tools", "The tools at the top of the {toolkit} hub", Icons.Rounded.Build, V11Routes.WORK_HUB),
        // Connections
        homeRow("health_connect", SettingsPage.HEALTH, "Sleep, steps and heart figures for Daily, Life Stats and habits"),
        homeRow("google", SettingsPage.GOOGLE, "Today's events and tasks, imported read-only"),
        homeRow(
            "feeds", SettingsPage.FEEDS, "The feeds behind the Feeds tab and Daily, and the modes each one shows in",
            keywords = words("rss news tech watch")
        ),
        homeRow(
            "games", SettingsPage.GAMES, "PlayStation, Xbox, Game night and the Quick Settings tile",
            keywords = words("playstation xbox tile")
        ),
        // AI & search
        homeRow("nano", SettingsPage.NANO, "The on-device model: download, storage and a self-test", keywords = words("ai model gemini")),
        homeRow(
            "knowledge", SettingsPage.KNOWLEDGE, "Indexing, hybrid search, retrieval measures, related notes while writing",
            keywords = words("rag index embeddings search")
        ),
        // Privacy & safety
        homeRow("privacy_ledger", SettingsPage.PRIVACY_LEDGER, "Each host the app talked to, why, and about how much"),
        homeRow("confidentiality", SettingsPage.CONFIDENTIALITY, "Protected names and secrets: a commit holding one waits for your review"),
        homeRow("private_journal", SettingsPage.PRIVATE_JOURNAL, "Auto-lock, private notes in AI answers, changing the password"),
        homeRow("permissions", SettingsPage.PERMISSIONS, "What each permission is for, and whether it's granted", keywords = words("access allow")),
        // Vault & device
        SettingsEntry(
            VAULT_LINKS, SettingsTopic.VAULT_DEVICE, "Vault links", "Broken links and orphan notes, checked on this phone",
            Icons.Rounded.Link, EntryKind.ACTION
        ),
        homeRow(
            "repair", SettingsPage.REPAIR, "Reload habits, rooms & chores from the vault, or write them back",
            keywords = words("import export reload")
        ),
        homeRow(
            SYNC_CONFLICT, SettingsPage.SYNC_CONFLICTS, "Files changed both here and on GitHub: syncing waits for your choice",
            shown = EntryShown.CONFLICT_PENDING
        ),
        homeRow(DEVICE, SettingsPage.DEVICE, "The app's version, this device's role and its vault", keywords = words("about version read-only")),
        SettingsEntry(
            LOG_OUT, SettingsTopic.VAULT_DEVICE, "Log out", "Forget this device's token; the next login picks the vault and the role",
            Icons.AutoMirrored.Rounded.Logout, EntryKind.ACTION, keywords = words("sign out token")
        )
    )

    // --- The pages' rows (S6): the search opens their page at the row ---

    private val REMINDERS: List<SettingsEntry> = listOf(
        on(SettingsPage.REMINDERS, "reminders.check_in", "Evening check-in", "Your mood and one line, from a notification each evening", Icons.Rounded.Mood, SettingsAnchors.CHECK_IN),
        on(SettingsPage.REMINDERS, "reminders.vault", "Private vault reminder", "A daily nudge to journal at the time you choose", Icons.Rounded.Lock, SettingsAnchors.VAULT_REMINDER),
        on(SettingsPage.REMINDERS, "reminders.meeting_prep", "Meeting prep", "Before a meeting, what your notes and the mission log hold about it", Icons.Rounded.Groups, SettingsAnchors.MEETING_PREP),
        on(SettingsPage.REMINDERS, "reminders.one_on_one", "1:1 reminders", "Thirty minutes before a 1:1, or a meeting linked to a person", Icons.Rounded.Diversity3, SettingsAnchors.ONE_ON_ONE),
        on(SettingsPage.REMINDERS, "reminders.reviews", "Weekly review & monthly reports", "A quiet notification when a review or a report is written", Icons.Rounded.EditCalendar, SettingsAnchors.REVIEWS),
        on(SettingsPage.REMINDERS, "reminders.chores", "Chores & habits reminder", "At each window, the chores due and the habits left", Icons.Rounded.CleaningServices, SettingsAnchors.CHORES)
    )

    private val NAVIGATION: List<SettingsEntry> = listOf(
        on(SettingsPage.NAVIGATION, "navigation.order", "Tab order", "Drag the tabs into place; a phone's bar shows the first four", Icons.Rounded.SwapVert, SettingsAnchors.TAB_ORDER),
        on(SettingsPage.NAVIGATION, "navigation.shows_in", "Tabs per mode", "Which of {work}, {personal} and {off} each tab shows in", Icons.Rounded.Layers, SettingsAnchors.SHOWS_IN),
        on(SettingsPage.NAVIGATION, "navigation.rail_names", "Tab names on the rail", "A wide window's rail shows each tab's name", Icons.AutoMirrored.Rounded.MenuOpen, SettingsAnchors.RAIL_NAMES)
    )

    /** The Modes hub's rows (S2): each opens its own short page, no anchor. */
    private val MODES_HUB: List<SettingsEntry> = listOf(
        leadsTo(SettingsPage.MODES, "modes.names", SettingsPage.MODES_NAMES, "What the modes and the {toolkit} are called, and their icons"),
        leadsTo(SettingsPage.MODES, "modes.work", SettingsPage.MODES_WORK, "The folders hidden outside {work} mode"),
        leadsTo(
            SettingsPage.MODES, "modes.projects", SettingsPage.MODES_PROJECTS,
            "What {personal} mode shows on the Projects tab, Daily and the widget", title = "{personal} projects"
        ),
        leadsTo(SettingsPage.MODES, "modes.schedule", SettingsPage.MODES_SCHEDULE, "{work} mode in work hours, another mode the rest of the time"),
        leadsTo(SettingsPage.MODES, "modes.custody", SettingsPage.MODES_CUSTODY, "Every other week, from one handover to the next"),
        leadsTo(SettingsPage.MODES, "modes.dates", SettingsPage.MODES_DATES, "Days off, and the countdowns Daily shows")
    )

    private val MOODS: List<SettingsEntry> = listOf(
        on(SettingsPage.MOODS, "moods.faces", "Mood faces", "The face set your moods wear, with each face's score", Icons.Rounded.EmojiEmotions, SettingsAnchors.FACES),
        on(SettingsPage.MOODS, "moods.tags", "Mood tags", "The tags you add to a mood, their icons and categories", Icons.AutoMirrored.Rounded.Label, SettingsAnchors.TAGS)
    )

    private val RPG: List<SettingsEntry> = listOf(
        on(SettingsPage.RPG, "rpg.goals", "Health goals", "The steps and sleep that earn a day its health XP", Icons.Rounded.Flag, SettingsAnchors.GOALS),
        on(SettingsPage.RPG, "rpg.rules", "Tag rules", "Which mood tag sends its XP to which of your hero's stats", Icons.Rounded.Sell, SettingsAnchors.TAG_RULES)
    )

    private val GAMES: List<SettingsEntry> = listOf(
        leadsTo(SettingsPage.GAMES, "games.psn", SettingsPage.PSN, "Play time and trophies, synced each night", shown = EntryShown.PSN_BUILD),
        leadsTo(SettingsPage.GAMES, "games.xbox", SettingsPage.XBOX, "Play time and achievements, synced each night", shown = EntryShown.XBOX_BUILD),
        on(
            SettingsPage.GAMES, "games.game_night", "Game night",
            "A {personal} session after work hours turns on Game night, one of Android's Modes",
            Icons.Rounded.DoNotDisturbOn, SettingsAnchors.GAME_NIGHT
        ),
        on(
            SettingsPage.GAMES, "games.tile_unlock", "Unlock to start a session",
            "A start from the Quick Settings tile on a locked phone asks for an unlock first",
            Icons.Rounded.LockOpen, SettingsAnchors.TILE_UNLOCK
        )
    )

    private val NANO: List<SettingsEntry> = listOf(
        on(SettingsPage.NANO, "nano.model", "Model", "Gemini Nano on this phone: its download, progress and storage", SettingsPage.NANO.icon, SettingsAnchors.MODEL),
        on(
            SettingsPage.NANO, "nano.self_test", "Self-test", "One real request and one streamed answer through the app's own path, timed",
            Icons.Rounded.Speed, SettingsAnchors.SELF_TEST, shown = EntryShown.WRITING_DEVICE
        )
    )

    private val KNOWLEDGE: List<SettingsEntry> = listOf(
        on(
            SettingsPage.KNOWLEDGE, "knowledge.index", "Index the vault", "Embeds the passages that changed since the last run, on this phone",
            Icons.Rounded.Memory, SettingsAnchors.INDEX, shown = EntryShown.WRITING_DEVICE
        ),
        on(SettingsPage.KNOWLEDGE, "knowledge.hybrid", "Hybrid search", "Notes found by meaning rank higher when they share your question's words", Icons.Rounded.TravelExplore, SettingsAnchors.HYBRID),
        on(
            SettingsPage.KNOWLEDGE, "knowledge.measure", "Measure retrieval", "Runs the golden set: how often meaning, words or both find the right notes",
            Icons.Rounded.Science, SettingsAnchors.MEASURE, shown = EntryShown.WRITING_DEVICE
        ),
        on(SettingsPage.KNOWLEDGE, "knowledge.related", "Related notes while writing", "The notes closest to the paragraph you're writing, beside the text", Icons.Rounded.Lightbulb, SettingsAnchors.RELATED)
    )

    private val PRIVATE_JOURNAL: List<SettingsEntry> = listOf(
        on(SettingsPage.PRIVATE_JOURNAL, "journal.auto_lock", "Auto-lock", "How long the journal stays open after it leaves the screen", Icons.Rounded.Timer, SettingsAnchors.AUTO_LOCK),
        on(SettingsPage.PRIVATE_JOURNAL, "journal.private_ai", "Private notes in AI answers", "Whether Chat reads the private journal while it's unlocked", Icons.Rounded.AutoAwesome, SettingsAnchors.PRIVATE_AI),
        on(SettingsPage.PRIVATE_JOURNAL, "journal.password", "Change password", "In the journal's menu, once it's unlocked", Icons.Rounded.Password, SettingsAnchors.PASSWORD)
    )

    private val REPAIR: List<SettingsEntry> = listOf(
        on(SettingsPage.REPAIR, "repair.reload", "Reload from the vault", "Habits, rooms and chores read again from the vault", Icons.Rounded.Download, SettingsAnchors.RELOAD),
        on(
            SettingsPage.REPAIR, "repair.write_back", "Write back to the vault", "Habits, rooms and chores written to the vault and pushed",
            Icons.Rounded.Upload, SettingsAnchors.WRITE_BACK, shown = EntryShown.WRITING_DEVICE
        )
    )

    private val DEVICE_PAGE: List<SettingsEntry> = listOf(
        on(SettingsPage.DEVICE, "device.version", "Version", "The app's version and build on this device", Icons.Rounded.Info, SettingsAnchors.VERSION),
        on(SettingsPage.DEVICE, "device.role", "Device role", "Whether this device writes the vault or only reads it", Icons.Rounded.PhoneAndroid, SettingsAnchors.ROLE)
    )

    /** §5.3's table, in its order: the home's rows, then each page's. */
    val ENTRIES: List<SettingsEntry> =
        HOME + REMINDERS + NAVIGATION + MODES_HUB + MOODS + RPG + GAMES + NANO + KNOWLEDGE + PRIVATE_JOURNAL +
            permissionEntries() + REPAIR + DEVICE_PAGE

    /** The home: each topic in order with its rows ([SettingsEntry.onHome] entries shown in [context]). */
    fun home(context: SettingsContext): List<Pair<SettingsTopic, List<SettingsEntry>>> =
        SettingsTopic.entries
            .map { topic -> topic to ENTRIES.filter { it.onHome && it.topic == topic && shown(it, context) } }
            .filter { (_, rows) -> rows.isNotEmpty() }

    fun shown(entry: SettingsEntry, context: SettingsContext): Boolean = when (entry.shown) {
        EntryShown.ALWAYS -> true
        EntryShown.WRITING_DEVICE -> !context.readOnly
        EntryShown.PSN_BUILD -> context.psn
        EntryShown.XBOX_BUILD -> context.xbox
        EntryShown.CONFLICT_PENDING -> context.conflictPending
    }

    /** {work}/{personal}/{off} → the mode's name, {toolkit} → the toolkit's. */
    fun text(raw: String, appearance: ModeAppearance): String = raw
        .replace("{work}", appearance.label(ContextMode.WORK))
        .replace("{personal}", appearance.label(ContextMode.PERSONAL))
        .replace("{off}", appearance.label(ContextMode.OFF))
        .replace("{toolkit}", appearance.toolkitName)

    /** The Permissions page's rows (S11): one per permission the app declares, anchored on its key. */
    private fun permissionEntries(): List<SettingsEntry> = AppPermissions.ALL.map { permission ->
        on(
            SettingsPage.PERMISSIONS, "permissions.${permission.key}", permission.title, permission.purpose,
            permissionIcon(permission.key), permission.key
        )
    }

    private fun permissionIcon(key: String): ImageVector = when (key) {
        "notifications" -> Icons.Rounded.Notifications
        "live_updates" -> Icons.Rounded.Timelapse
        "dnd" -> Icons.Rounded.DoNotDisturbOn
        "location" -> Icons.Rounded.LocationOn
        "microphone" -> Icons.Rounded.Mic
        "health" -> Icons.Rounded.HealthAndSafety
        "internet" -> Icons.Rounded.Public
        else -> SettingsPage.PERMISSIONS.icon
    }

    /** A home row opening [page]: the page's own title and icon. */
    private fun homeRow(
        key: String,
        page: SettingsPage,
        description: String,
        keywords: List<String> = emptyList(),
        shown: EntryShown = EntryShown.ALWAYS
    ) = SettingsEntry(key, page.topic, page.title, description, page.icon, EntryKind.PAGE, route = page.route, keywords = keywords, shown = shown)

    /** A row of the toolkit's topic: it opens the feature itself, not a settings page (S4). */
    private fun feature(key: String, title: String, description: String, icon: ImageVector, route: String) =
        SettingsEntry(key, SettingsTopic.TOOLKIT, title, description, icon, EntryKind.PAGE, route = route)

    /** A row of [page], opened at [anchor]. */
    private fun on(
        page: SettingsPage,
        key: String,
        title: String,
        description: String,
        icon: ImageVector,
        anchor: String,
        shown: EntryShown = EntryShown.ALWAYS
    ) = SettingsEntry(key, page.topic, title, description, icon, EntryKind.PAGE, route = page.route, anchor = anchor, page = page, shown = shown)

    /** A row of [page] that opens a page of its own, [target], with that page's title and icon. */
    private fun leadsTo(
        page: SettingsPage,
        key: String,
        target: SettingsPage,
        description: String,
        title: String = target.title,
        shown: EntryShown = EntryShown.ALWAYS
    ) = SettingsEntry(key, page.topic, title, description, target.icon, EntryKind.PAGE, route = target.route, page = page, shown = shown)

    private fun words(text: String): List<String> = text.split(' ').filter { it.isNotBlank() }
}
