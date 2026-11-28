package cloud.wafflecommons.pixelbrainreader.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The habit editor's automatic sources (13.4, S-k): shown by name, stored by their keys. */
class HabitSourcesTest {

    @Test
    fun `each automatic source has a name, none is by hand`() {
        assertEquals("None (by hand)", HabitSources.label(null))
        assertEquals(null, HabitSources.KEYS.first())
        val sources = HabitSources.KEYS.filterNotNull()
        assertEquals(8, sources.size)
        // Each its own name, from Health Connect; never the stored key.
        val labels = sources.map { HabitSources.label(it) }
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(labels.all { it.endsWith(" (Health Connect)") && !it.contains("health_connect") })
        assertEquals("Steps (Health Connect)", HabitSources.label("health_connect_steps"))
        assertEquals("Water (Health Connect)", HabitSources.label("health_connect_hydration"))
        assertEquals("Active minutes (Health Connect)", HabitSources.label("health_connect_active_minutes"))
    }

    @Test
    fun `a key this version doesn't know shows as stored`() {
        assertEquals("health_connect_later", HabitSources.label("health_connect_later"))
        assertEquals("None (by hand)", HabitSources.label(" "))
    }
}
