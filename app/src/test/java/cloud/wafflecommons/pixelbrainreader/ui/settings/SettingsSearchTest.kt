package cloud.wafflecommons.pixelbrainreader.ui.settings

import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeAppearance
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Settings search (13.4, S6): any setting by its name or description, as the owner names things. */
class SettingsSearchTest {

    private val plain = SettingsContext()

    private fun keys(query: String, context: SettingsContext = plain): List<String> =
        SettingsSearch.search(query, context).map { it.entry.key }

    @Test
    fun `a word finds a setting by its title`() {
        assertEquals(SettingsIndex.SOUND_EFFECTS, keys("sound").first())
        assertEquals("habits", keys("habits").first())
        // A word's start is enough.
        assertEquals(SettingsIndex.SOUND_EFFECTS, keys("effe").first())
    }

    @Test
    fun `by its description`() {
        assertEquals(listOf(SettingsIndex.VAULT_LINKS), keys("orphan"))
    }

    @Test
    fun `by its topic`() {
        val found = keys("connections")
        assertTrue(found.containsAll(listOf("health_connect", "google", "feeds", "games")))
        // Only through the topic: rank 0, the home's rows first.
        assertEquals(listOf("health_connect", "google", "feeds", "games"), found.take(4))
    }

    @Test
    fun `case and accents don't matter`() {
        assertEquals(SettingsIndex.SOUND_EFFECTS, keys("SOUND").first())
        assertEquals(SettingsIndex.SOUND_EFFECTS, keys("Sôund Éffects").first())
        assertEquals("sound effects", SettingsSearch.normalize("  Sôund — Éffects!  "))
    }

    @Test
    fun `every word must match`() {
        assertTrue(keys("sound xylophone").isEmpty())
        // Two words may match in two places: the title and the description.
        assertEquals(listOf(SettingsIndex.SOUND_EFFECTS), keys("sound task"))
    }

    @Test
    fun `titles rank before descriptions`() {
        val found = keys("habits")
        // "Habits" is a title; Reminders only says habits in its description.
        assertTrue(found.indexOf("habits") < found.indexOf("reminders"))
        // A page's row with the word in its title ranks before a home row with it in its description.
        assertTrue(found.indexOf("reminders.chores") < found.indexOf("reminders"))
        // A title that starts with the query ranks first.
        assertEquals("knowledge.hybrid", keys("hybrid search").first())
    }

    @Test
    fun `a page's entry says where it is`() {
        val hit = SettingsSearch.search("check-in", plain).first()
        assertEquals("reminders.check_in", hit.entry.key)
        assertEquals("Evening check-in", hit.title)
        assertEquals("Reminders · Your day", hit.where)
        // A home row: its topic.
        assertEquals("Your day", SettingsSearch.search("sound", plain).first().where)
        // The Modes hub's page and topic share a name: said once.
        assertEquals("Modes", SettingsSearch.search("custody", plain).first { it.entry.key == "modes.custody" }.where)
    }

    @Test
    fun `a hidden entry is never found`() {
        assertFalse("games.psn" in keys("playstation"))
        assertTrue("games.psn" in keys("playstation", SettingsContext(psn = true)))
        assertFalse("repair.write_back" in keys("write back", SettingsContext(readOnly = true)))
        assertTrue("repair.write_back" in keys("write back"))
        assertFalse(SettingsIndex.SYNC_CONFLICT in keys("conflict"))
        assertTrue(SettingsIndex.SYNC_CONFLICT in keys("conflict", SettingsContext(conflictPending = true)))
    }

    @Test
    fun `an empty query finds nothing`() {
        assertTrue(keys("").isEmpty())
        assertTrue(keys("   ").isEmpty())
        assertTrue(keys("?!").isEmpty())
    }

    @Test
    fun `mode names are searched as the owner calls them`() {
        val named = SettingsContext(
            appearance = ModeAppearance(styles = mapOf(ContextMode.PERSONAL to ModeStyle(label = "Garden")), toolkit = "Workbench")
        )

        val garden = SettingsSearch.search("garden", named)
        assertTrue(garden.any { it.entry.key == "modes.projects" && it.title == "Garden projects" })
        assertFalse("modes.projects" in keys("personal", named))
        assertTrue("modes.projects" in keys("personal"))
        // The toolkit's rows sit under its name, and its hub is called by it.
        val workbench = keys("workbench", named)
        assertTrue(workbench.containsAll(listOf("mission_log", "meetings", "boards", "projects", "pinned_tools")))
        assertTrue(keys("workbench").isEmpty())
    }
}
