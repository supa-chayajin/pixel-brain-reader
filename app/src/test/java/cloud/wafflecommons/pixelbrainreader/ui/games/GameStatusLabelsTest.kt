package cloud.wafflecommons.pixelbrainreader.ui.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 13.4 (the audit's status-lines-mislead and copy-app-and-mode-names): a console's last sync says
 * its day, and the Game night copy says the app's and the mode's own names.
 */
class GameStatusLabelsTest {

    private val zone = ZoneId.of("Asia/Tokyo")
    private val today = LocalDate.of(2026, 11, 28)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `a sync three days ago says its day`() {
        assertEquals("25 Nov 23:40", syncedLabel(at(2026, 11, 25, 23, 40), today, zone))
        assertEquals("yesterday 23:40", syncedLabel(at(2026, 11, 27, 23, 40), today, zone))
        assertEquals("today 06:00", syncedLabel(at(2026, 11, 28, 6, 0), today, zone))
        // The day is the sync's own in the zone given, not the instant's in UTC.
        assertEquals("today 00:30", syncedLabel(at(2026, 11, 28, 0, 30), today, zone))
    }

    @Test
    fun `the game night copy says the app's and the mode's own names`() {
        val text = gameNightText(personalLabel = "Home", appName = "Glass Notes")
        assertTrue(text.startsWith("On a Home evening, "))
        assertTrue(text.contains("Allow Glass Notes in Do Not Disturb access"))
        assertFalse(text.contains("Perso"))
        assertFalse(text.contains("PixelBrain"))
        assertTrue(text.endsWith("The session runs either way."))
    }
}
