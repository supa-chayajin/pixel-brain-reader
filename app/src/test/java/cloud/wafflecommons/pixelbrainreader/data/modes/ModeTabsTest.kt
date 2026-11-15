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
    fun `the modes file keeps the hidden tabs and reads an older one`() {
        val json = Json { ignoreUnknownKeys = true }
        val config = ModeTabs.toggled(ModesConfig(), ContextMode.WORK, "games")
        assertEquals(config, json.decodeFromString(ModesConfig.serializer(), json.encodeToString(ModesConfig.serializer(), config)))
        assertTrue(json.decodeFromString(ModesConfig.serializer(), "{}").hiddenTabs.isEmpty())
    }
}
