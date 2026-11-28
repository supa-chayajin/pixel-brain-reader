package cloud.wafflecommons.pixelbrainreader.ui.settings

import cloud.wafflecommons.pixelbrainreader.data.guard.CommitHeldException
import cloud.wafflecommons.pixelbrainreader.data.guard.FindingKind
import cloud.wafflecommons.pixelbrainreader.data.guard.GuardFinding
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeAppearance
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/** The Settings pages' meta (13.4, S-a): routes, titles, pills and the search's anchors. */
class SettingsPagesTest {

    @Test
    fun `every page has its own route`() {
        val routes = SettingsPage.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
        assertTrue(routes.none { it.isBlank() || '{' in it || '?' in it })
    }

    @Test
    fun `the routes kept from version 12 keep their strings`() {
        // Links, deep links and the Quick Settings long-presses open these: never renamed.
        assertEquals("settings", SettingsRoutes.HOME)
        assertEquals("daily_layout", SettingsRoutes.DAILY_LAYOUT)
        assertEquals("navbar_reorder", SettingsRoutes.NAVIGATION)
        assertEquals("reminders", SettingsRoutes.REMINDERS)
        assertEquals("habit_config", SettingsRoutes.HABITS)
        assertEquals("home_config", SettingsRoutes.HOME_CHORES)
        assertEquals("mood_tags", SettingsRoutes.MOODS)
        assertEquals("gamification_settings", SettingsRoutes.RPG)
        assertEquals("watch_feeds", SettingsRoutes.FEEDS)
        assertEquals("privacy_ledger", SettingsRoutes.PRIVACY_LEDGER)
        assertEquals("modes_settings", SettingsRoutes.MODES)
        assertEquals("confidentiality", SettingsRoutes.CONFIDENTIALITY)
        assertEquals("psn_settings", SettingsRoutes.PSN)
        assertEquals("xbox_settings", SettingsRoutes.XBOX)
        assertEquals("sync_conflicts", SettingsRoutes.SYNC_CONFLICTS)
        // And the pages on them.
        assertEquals(SettingsRoutes.NAVIGATION, SettingsPage.NAVIGATION.route)
        assertEquals(SettingsRoutes.MOODS, SettingsPage.MOODS.route)
        assertEquals(SettingsRoutes.MODES, SettingsPage.MODES.route)
        assertEquals(SettingsRoutes.FEEDS, SettingsPage.FEEDS.route)
    }

    @Test
    fun `anchors are lowercase words`() {
        val anchors = SettingsAnchors::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
        assertTrue("No anchor found", anchors.size >= 28)
        assertEquals(anchors.size, anchors.toSet().size)
        val word = Regex("[a-z]+(_[a-z]+)*")
        val wrong = anchors.filterNot { word.matches(it) }
        assertTrue("Not lowercase words: $wrong", wrong.isEmpty())
    }

    @Test
    fun `the Personal projects page is named after the Personal mode`() {
        assertEquals("Personal projects", SettingsPage.MODES_PROJECTS.titleFor(ModeAppearance()))
        val renamed = ModeAppearance(styles = mapOf(ContextMode.PERSONAL to ModeStyle("Garden")))
        assertEquals("Garden projects", SettingsPage.MODES_PROJECTS.titleFor(renamed))
        // Every other page keeps its own title.
        assertEquals("Schedule", SettingsPage.MODES_SCHEDULE.titleFor(renamed))
        assertEquals("Moods", SettingsPage.MOODS.titleFor(renamed))
    }

    @Test
    fun `a page's pill is its topic, the Modes pages' is Modes, the hub's Settings, the consoles' Games`() {
        assertEquals("Your day", SettingsPage.NAVIGATION.subtitle)
        assertEquals("Life OS", SettingsPage.RPG.subtitle)
        assertEquals("Connections", SettingsPage.FEEDS.subtitle)
        assertEquals("Privacy & safety", SettingsPage.PRIVACY_LEDGER.subtitle)
        assertEquals("Vault & device", SettingsPage.SYNC_CONFLICTS.subtitle)
        listOf(
            SettingsPage.MODES_NAMES, SettingsPage.MODES_WORK, SettingsPage.MODES_PROJECTS,
            SettingsPage.MODES_SCHEDULE, SettingsPage.MODES_CUSTODY, SettingsPage.MODES_DATES
        ).forEach { assertEquals("Modes", it.subtitle) }
        assertEquals("Settings", SettingsPage.MODES.subtitle)
        assertEquals("Games", SettingsPage.PSN.subtitle)
        assertEquals("Games", SettingsPage.XBOX.subtitle)
    }

    @Test
    fun `the topics are the approved eight, in order`() {
        assertEquals(
            listOf("Your day", "Modes", "Life OS", "Toolkit", "Connections", "AI & search", "Privacy & safety", "Vault & device"),
            SettingsTopic.entries.map { it.title }
        )
        // The Toolkit takes the owner's name for it; the others keep theirs.
        val named = ModeAppearance(toolkit = "Bench")
        assertEquals("Bench", SettingsTopic.TOOLKIT.titleFor(named))
        assertEquals("Toolkit", SettingsTopic.TOOLKIT.titleFor(ModeAppearance()))
        assertEquals("Life OS", SettingsTopic.LIFE_OS.titleFor(named))
    }
}
