package cloud.wafflecommons.pixelbrainreader.data.modes

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ModeAppearanceTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `blank names and icons fall back to the defaults`() {
        val appearance = ModesConfig(modeStyles = mapOf(ContextMode.PERSONAL to ModeStyle(label = "  ", icon = ""))).appearance()
        assertEquals("Work", appearance.label(ContextMode.WORK))
        assertEquals("Personal", appearance.label(ContextMode.PERSONAL))
        assertEquals("home", appearance.iconKey(ContextMode.PERSONAL))
        assertEquals("Toolkit", appearance.toolkitName)
    }

    @Test
    fun `custom names and icons are used, trimmed`() {
        val config = ModesConfig(
            modeStyles = mapOf(ContextMode.WORK to ModeStyle(label = " Mission ", icon = "laptop")),
            toolkitName = "Desk"
        )
        val visibility = Visibility(ContextMode.WORK, appearance = config.appearance())
        assertEquals("Mission", visibility.label())
        assertEquals("Mission", visibility.workLabel)
        assertEquals("laptop", visibility.appearance.iconKey(ContextMode.WORK))
        assertEquals("Desk", visibility.appearance.toolkitName)
    }

    @Test
    fun `styles round-trip through the vault file and older files still load`() {
        val config = ModesConfig(modeStyles = mapOf(ContextMode.OFF to ModeStyle("Rest", "night")), toolkitName = "Desk")
        val decoded = json.decodeFromString(ModesConfig.serializer(), json.encodeToString(ModesConfig.serializer(), config))
        assertEquals(config, decoded)

        val legacy = json.decodeFromString(ModesConfig.serializer(), """{"workFolders":["20_Work"]}""")
        assertEquals("Off", legacy.appearance().label(ContextMode.OFF))
    }
}
