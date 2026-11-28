package cloud.wafflecommons.pixelbrainreader.data.modes

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeTabsTest {

    @Test
    fun `a tab hides in one mode only, and shows again`() {
        var config = ModesConfig()
        config = ModeTabs.toggled(config, ContextMode.OFF, "projects")
        config = ModeTabs.toggled(config, ContextMode.OFF, ModeTabs.TOOLKIT)
        config = ModeTabs.toggled(config, ContextMode.OFF, "habits")
        assertEquals(listOf("habits", "projects", ModeTabs.TOOLKIT), config.hiddenTabs[ContextMode.OFF])
        assertEquals(emptySet<String>(), ModeTabs.hiddenIn(config, ContextMode.WORK))
        config = ModeTabs.toggled(config, ContextMode.OFF, "habits")
        config = ModeTabs.toggled(config, ContextMode.OFF, "projects")
        config = ModeTabs.toggled(config, ContextMode.OFF, ModeTabs.TOOLKIT)
        assertTrue(config.hiddenTabs.isEmpty())
    }

    @Test
    fun `withShownIn hides the tab in the modes left out`() {
        var config = ModeTabs.toggled(ModesConfig(), ContextMode.OFF, "habits")
        config = ModeTabs.withShownIn(config, "games", setOf(ContextMode.PERSONAL))

        assertEquals(listOf("games"), config.hiddenTabs[ContextMode.WORK])
        assertEquals(null, config.hiddenTabs[ContextMode.PERSONAL])
        // The Off mode's list keeps the bar's order: habits before games.
        assertEquals(listOf("habits", "games"), config.hiddenTabs[ContextMode.OFF])

        // Hidden everywhere: a tab can leave every mode (the toolkit's chip, say).
        config = ModeTabs.withShownIn(config, ModeTabs.TOOLKIT, emptySet())
        ContextMode.entries.forEach { mode -> assertTrue(ModeTabs.TOOLKIT in ModeTabs.hiddenIn(config, mode)) }
    }

    @Test
    fun `withShownIn with every mode shows it everywhere and leaves the other tabs`() {
        var config = ModeTabs.toggled(ModesConfig(), ContextMode.OFF, "habits")
        config = ModeTabs.toggled(config, ContextMode.OFF, "feeds")
        config = ModeTabs.toggled(config, ContextMode.WORK, "feeds")
        // A key from a later version stays where it is.
        config = config.copy(hiddenTabs = config.hiddenTabs + (ContextMode.WORK to config.hiddenTabs.getValue(ContextMode.WORK) + "later_tab"))

        config = ModeTabs.withShownIn(config, "feeds", ContextMode.entries.toSet())

        assertEquals(listOf("habits"), config.hiddenTabs[ContextMode.OFF])
        assertEquals(listOf("later_tab"), config.hiddenTabs[ContextMode.WORK])
        assertEquals(null, config.hiddenTabs[ContextMode.PERSONAL])

        // A mode left hiding nothing leaves the map.
        config = ModeTabs.withShownIn(config, "habits", ContextMode.entries.toSet())
        assertEquals(setOf(ContextMode.WORK), config.hiddenTabs.keys)
    }

    @Test
    fun `the modes file keeps the hidden tabs and reads an older one`() {
        val json = Json { ignoreUnknownKeys = true }
        val config = ModeTabs.toggled(ModesConfig(), ContextMode.WORK, "games")
        assertEquals(config, json.decodeFromString(ModesConfig.serializer(), json.encodeToString(ModesConfig.serializer(), config)))
        assertTrue(json.decodeFromString(ModesConfig.serializer(), "{}").hiddenTabs.isEmpty())
    }
}
