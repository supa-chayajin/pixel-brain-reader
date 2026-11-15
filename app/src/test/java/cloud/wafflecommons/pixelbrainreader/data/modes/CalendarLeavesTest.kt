package cloud.wafflecommons.pixelbrainreader.data.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CalendarLeavesTest {

    private val today = LocalDate.parse("2026-11-15")

    @Test
    fun `leave titles in French and English, whole words only`() {
        listOf("Congés", "CONGÉS d'hiver", "Vacances 🏖️", "RTT", "Leave", "Out of office", "Jour off").forEach {
            assertTrue(it, CalendarLeaves.isLeave(it))
        }
        listOf("Rttfoo", "Leaves review", "Livraison des congéssss", "Holidayss", "Team offsite").forEach {
            assertFalse(it, CalendarLeaves.isLeave(it))
        }
    }

    @Test
    fun `an all-day event's exclusive end becomes the last day`() {
        val event = CalendarLeaves.of("e1", " Congés ", LocalDate.parse("2026-12-21"), LocalDate.parse("2027-01-02"))!!
        assertEquals(LocalDate.parse("2027-01-01"), event.end)
        assertEquals("Congés", event.title)
        assertEquals(LocalDate.parse("2026-12-24"), CalendarLeaves.of("e2", "RTT", LocalDate.parse("2026-12-24"), LocalDate.parse("2026-12-24"))!!.end)
        assertNull(CalendarLeaves.of("e3", "Sprint review", LocalDate.parse("2026-12-21"), LocalDate.parse("2026-12-22")))
    }

    @Test
    fun `the calendar's leaves follow their events, typed ones never move`() {
        val typed = LeavePeriod("2026-12-01", "2026-12-03", "Pont")
        val past = LeavePeriod("2026-10-01", "2026-10-05", "Congés", source = CalendarLeaves.SOURCE, eventId = "old")
        val moved = LeavePeriod("2026-12-21", "2026-12-31", "Congés", source = CalendarLeaves.SOURCE, eventId = "e1")
        val gone = LeavePeriod("2027-02-01", "2027-02-05", "Vacances", source = CalendarLeaves.SOURCE, eventId = "e2")
        val events = listOf(CalendarLeaves.Event("e1", "Congés", LocalDate.parse("2026-12-22"), LocalDate.parse("2027-01-01")))

        val merged = CalendarLeaves.merge(listOf(typed, past, moved, gone), events, today)!!

        assertTrue(typed in merged)
        assertTrue("a past calendar leave stays as history", past in merged)
        assertFalse(gone in merged)
        assertFalse(moved in merged)
        assertTrue(merged.any { it.eventId == "e1" && it.start == "2026-12-22" && it.end == "2027-01-01" })
        assertNull("nothing changed: nothing to write", CalendarLeaves.merge(merged, events, today))
    }
}
