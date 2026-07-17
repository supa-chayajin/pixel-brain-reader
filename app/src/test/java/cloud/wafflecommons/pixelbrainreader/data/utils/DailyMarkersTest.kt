package cloud.wafflecommons.pixelbrainreader.data.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the invisible-marker contract behind the Google-sync triple-duplication fix: the
 * burned markdown must carry googleEventId / googleTaskId through the burn↔parse round-trip
 * without ever leaking the marker into visible text.
 */
class DailyMarkersTest {

    @Test
    fun `calendar marker round-trips the event id`() {
        val encoded = DailyMarkers.appendCalendarMarker("[Work] Standup", "evt_123")
        val (clean, id) = DailyMarkers.stripCalendarMarker(encoded)
        assertEquals("[Work] Standup", clean)
        assertEquals("evt_123", id)
    }

    @Test
    fun `task marker round-trips the task id`() {
        val encoded = DailyMarkers.appendTaskMarker("Buy milk", "task-abc")
        val (clean, id) = DailyMarkers.stripTaskMarker(encoded)
        assertEquals("Buy milk", clean)
        assertEquals("task-abc", id)
    }

    @Test
    fun `no marker is appended for a local entry`() {
        assertEquals("Local note", DailyMarkers.appendCalendarMarker("Local note", null))
        assertEquals("Local note", DailyMarkers.appendCalendarMarker("Local note", ""))
        assertEquals("Local task", DailyMarkers.appendTaskMarker("Local task", null))
    }

    @Test
    fun `stripping a line with no marker returns null id and the trimmed text`() {
        val (clean, id) = DailyMarkers.stripCalendarMarker("  Local note  ")
        assertEquals("Local note", clean)
        assertNull(id)
    }

    @Test
    fun `stripped display text never leaks the comment`() {
        val encoded = DailyMarkers.appendTaskMarker("Ship release", "id_99")
        val (clean, _) = DailyMarkers.stripTaskMarker(encoded)
        assertFalse(clean.contains("<!--"))
        assertFalse(clean.contains("gtask"))
    }
}
