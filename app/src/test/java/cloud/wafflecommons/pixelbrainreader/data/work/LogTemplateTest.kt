package cloud.wafflecommons.pixelbrainreader.data.work

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class LogTemplateTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `settings saved before 12_20 read with no templates`() {
        val settings = json.decodeFromString(MissionLogSettings.serializer(), """{"folder":"30_Areas/Mission log","encrypted":false,"roles":["Lead"]}""")
        assertEquals(emptyList<LogTemplate>(), settings.templates)
        assertEquals(listOf("Lead"), settings.roles)
    }

    @Test
    fun `templates survive a round trip, and an unknown kind logs a fact`() {
        val settings = MissionLogSettings(templates = listOf(LogTemplate("Prod support", LogKind.REQUEST.name, "Support")))
        val back = json.decodeFromString(MissionLogSettings.serializer(), json.encodeToString(MissionLogSettings.serializer(), settings))
        assertEquals(settings, back)
        assertEquals(LogKind.REQUEST, back.templates.single().logKind())
        assertEquals(LogKind.FACT, LogTemplate("x", kind = "SOMETHING_NEWER").logKind())
    }
}
