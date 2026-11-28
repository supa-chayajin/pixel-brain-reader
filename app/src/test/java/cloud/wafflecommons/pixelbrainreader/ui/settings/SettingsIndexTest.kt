package cloud.wafflecommons.pixelbrainreader.ui.settings

import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeAppearance
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Settings map (13.4, S1): the owner's approved home, and the rows the search opens pages at (S6). */
class SettingsIndexTest {

    private fun entry(key: String): SettingsEntry = SettingsIndex.ENTRIES.single { it.key == key }

    private fun homeKeys(context: SettingsContext): List<String> =
        SettingsIndex.home(context).flatMap { (_, rows) -> rows.map { it.key } }

    @Test
    fun `the home lists the approved map, in order`() {
        val expected = listOf(
            SettingsTopic.YOUR_DAY to listOf("daily_screen", "navigation", "reminders", "sound_effects"),
            SettingsTopic.MODES to listOf("modes", "screen_share_safe"),
            SettingsTopic.LIFE_OS to listOf("habits", "home_chores", "moods", "rpg"),
            SettingsTopic.TOOLKIT to listOf("mission_log", "meetings", "boards", "projects", "pinned_tools"),
            SettingsTopic.CONNECTIONS to listOf("health_connect", "google", "feeds", "games"),
            SettingsTopic.AI_SEARCH to listOf("nano", "knowledge"),
            SettingsTopic.PRIVACY to listOf("privacy_ledger", "confidentiality", "private_journal", "permissions"),
            SettingsTopic.VAULT_DEVICE to listOf("vault_links", "repair", "sync_conflict", "device", "log_out")
        )

        val home = SettingsIndex.home(SettingsContext(conflictPending = true))

        assertEquals(expected, home.map { (topic, rows) -> topic to rows.map { it.key } })
        assertEquals(30, home.sumOf { (_, rows) -> rows.size })
        // The two device switches and the two actions act in place; every other row is a link.
        val inPlace = home.flatMap { (_, rows) -> rows }.filter { it.kind != EntryKind.PAGE }
        assertEquals(
            mapOf(
                "sound_effects" to EntryKind.SWITCH,
                "screen_share_safe" to EntryKind.SWITCH,
                "vault_links" to EntryKind.ACTION,
                "log_out" to EntryKind.ACTION
            ),
            inPlace.associate { it.key to it.kind }
        )
        inPlace.forEach { assertNull(it.key, it.route) }
    }

    @Test
    fun `every home row has a title, a one-line description and an icon`() {
        val rows = SettingsIndex.ENTRIES.filter { it.onHome }
        assertEquals(30, rows.size)
        rows.forEach { row ->
            assertTrue(row.key, row.title.isNotBlank())
            assertTrue(row.key, row.description.isNotBlank())
            assertFalse(row.key, row.description.contains('\n'))
            assertTrue("${row.key}: one line, not a paragraph", row.description.length <= 100)
            assertTrue(row.key, row.icon.name.isNotBlank())
        }
    }

    @Test
    fun `keys are unique`() {
        val keys = SettingsIndex.ENTRIES.map { it.key }
        assertEquals(keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toString(), keys.size, keys.toSet().size)
    }

    @Test
    fun `a page's entries open that page's route with an anchor`() {
        val pageRows = SettingsIndex.ENTRIES.filterNot { it.onHome }
        assertTrue(pageRows.isNotEmpty())
        val anchor = Regex("[a-z0-9]+(_[a-z0-9]+)*")
        pageRows.forEach { row ->
            val page = row.page!!
            assertEquals(row.key, EntryKind.PAGE, row.kind)
            assertEquals(row.key, page.topic, row.topic)
            val rowAnchor = row.anchor
            if (rowAnchor != null) {
                assertEquals(row.key, page.route, row.route)
                assertTrue("${row.key}: $rowAnchor", anchor.matches(rowAnchor))
            } else {
                // The Modes hub's rows and the Games page's consoles open a page of their own.
                val target = SettingsPage.entries.firstOrNull { it.route == row.route }
                assertNotNull(row.key, target)
                assertNotEquals(row.key, page, target)
            }
        }
        // The pages' rows of §5.3, each by its anchor.
        assertEquals(SettingsAnchors.CHECK_IN, entry("reminders.check_in").anchor)
        assertEquals(SettingsAnchors.RAIL_NAMES, entry("navigation.rail_names").anchor)
        assertEquals(SettingsAnchors.TILE_UNLOCK, entry("games.tile_unlock").anchor)
        assertEquals(SettingsAnchors.RELATED, entry("knowledge.related").anchor)
        assertEquals(SettingsAnchors.WRITE_BACK, entry("repair.write_back").anchor)
        assertEquals(SettingsRoutes.MODES_CUSTODY, entry("modes.custody").route)
        assertEquals(SettingsRoutes.PSN, entry("games.psn").route)
    }

    @Test
    fun `every permission has its row, anchored on its key`() {
        AppPermissions.ALL.forEach { permission ->
            val row = entry("permissions.${permission.key}")
            assertEquals(SettingsPage.PERMISSIONS, row.page)
            assertEquals(permission.key, row.anchor)
            assertEquals(permission.title, row.title)
        }
    }

    @Test
    fun `a home row that opens a page takes the page's title`() {
        val opened = SettingsIndex.ENTRIES.filter { it.onHome && it.kind == EntryKind.PAGE }
            .mapNotNull { row -> SettingsPage.entries.firstOrNull { it.route == row.route }?.let { row to it } }
        // Every home link but the toolkit's five (they open their features) opens a Settings page.
        assertEquals(21, opened.size)
        opened.forEach { (row, page) ->
            assertEquals(row.key, page.title, row.title)
            assertEquals(row.key, page.icon, row.icon)
            assertEquals(row.key, page.topic, row.topic)
        }
        SettingsIndex.ENTRIES.filter { it.topic == SettingsTopic.TOOLKIT }.forEach { row ->
            assertTrue(row.key, row.onHome)
            assertNull(row.key, SettingsPage.entries.firstOrNull { it.route == row.route })
        }
    }

    @Test
    fun `the Toolkit topic takes the toolkit's name, Personal projects the Personal mode's`() {
        val appearance = ModeAppearance(
            styles = mapOf(ContextMode.PERSONAL to ModeStyle(label = "Garden"), ContextMode.WORK to ModeStyle(label = "Studio")),
            toolkit = "Workbench"
        )

        assertEquals("Workbench", SettingsTopic.TOOLKIT.titleFor(appearance))
        val projects = entry("modes.projects")
        assertEquals("Garden projects", SettingsIndex.text(projects.title, appearance))
        assertEquals(SettingsPage.MODES_PROJECTS.titleFor(appearance), SettingsIndex.text(projects.title, appearance))
        assertEquals("The tools at the top of the Workbench hub", SettingsIndex.text(entry("pinned_tools").description, appearance))
        assertEquals("The folders hidden outside Studio mode", SettingsIndex.text(entry("modes.work").description, appearance))
        // The defaults, while the owner has named nothing.
        assertEquals("Toolkit", SettingsTopic.TOOLKIT.titleFor(ModeAppearance()))
        assertEquals("Personal projects", SettingsIndex.text(projects.title, ModeAppearance()))
    }

    @Test
    fun `PlayStation and Xbox only in builds with them, the conflict only while pending, writing-only entries never on a read-only device`() {
        val plain = SettingsContext()
        assertFalse(SettingsIndex.shown(entry("games.psn"), plain))
        assertFalse(SettingsIndex.shown(entry("games.xbox"), plain))
        assertTrue(SettingsIndex.shown(entry("games.psn"), SettingsContext(psn = true)))
        assertTrue(SettingsIndex.shown(entry("games.xbox"), SettingsContext(xbox = true)))
        // Game night is listed without a console (S8).
        assertTrue(SettingsIndex.shown(entry("games.game_night"), plain))

        assertFalse(SettingsIndex.SYNC_CONFLICT in homeKeys(plain))
        assertTrue(SettingsIndex.SYNC_CONFLICT in homeKeys(SettingsContext(conflictPending = true)))

        val writingOnly = SettingsIndex.ENTRIES.filter { it.shown == EntryShown.WRITING_DEVICE }
        assertEquals(
            setOf("nano.self_test", "knowledge.index", "knowledge.measure", "repair.write_back"),
            writingOnly.map { it.key }.toSet()
        )
        writingOnly.forEach { row ->
            assertFalse(row.key, SettingsIndex.shown(row, SettingsContext(readOnly = true)))
            assertTrue(row.key, SettingsIndex.shown(row, SettingsContext(readOnly = false)))
        }
        // The home itself is the same on a read-only device: its pages say what doesn't run there.
        assertEquals(homeKeys(SettingsContext()), homeKeys(SettingsContext(readOnly = true)))
    }
}
