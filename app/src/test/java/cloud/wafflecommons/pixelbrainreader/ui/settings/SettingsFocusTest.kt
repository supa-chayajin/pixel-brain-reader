package cloud.wafflecommons.pixelbrainreader.ui.settings

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The search's hand-off (13.4, S6): the page it opens lights the row asked for, once. */
class SettingsFocusTest {

    @After
    fun clear() {
        // Nothing left for the next test.
        SettingsFocus.take("")
    }

    @Test
    fun `a focus is taken once, by its route only`() {
        SettingsFocus.request(SettingsRoutes.REMINDERS, SettingsAnchors.CHECK_IN)
        assertEquals(SettingsAnchors.CHECK_IN, SettingsFocus.take(SettingsRoutes.REMINDERS))
        assertNull(SettingsFocus.take(SettingsRoutes.REMINDERS))

        // Another page opening first drops it: a later page never lights a row it wasn't asked for.
        SettingsFocus.request(SettingsRoutes.GAMES, SettingsAnchors.TILE_UNLOCK)
        assertNull(SettingsFocus.take(SettingsRoutes.MOODS))
        assertNull(SettingsFocus.take(SettingsRoutes.GAMES))

        // A newer request replaces the one before.
        SettingsFocus.request(SettingsRoutes.MOODS, SettingsAnchors.FACES)
        SettingsFocus.request(SettingsRoutes.MOODS, SettingsAnchors.TAGS)
        assertEquals(SettingsAnchors.TAGS, SettingsFocus.take(SettingsRoutes.MOODS))
    }
}
