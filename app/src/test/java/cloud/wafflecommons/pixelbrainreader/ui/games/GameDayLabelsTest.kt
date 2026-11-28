package cloud.wafflecommons.pixelbrainreader.ui.games

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** 13.4 (the audit's day-labels-stale-overnight): a day says itself against the state's today. */
class GameDayLabelsTest {

    private val today = LocalDate.of(2026, 11, 28)

    @Test
    fun `today, yesterday, then the date`() {
        assertEquals("today", dayLabel(today, today))
        assertEquals("yesterday", dayLabel(today.minusDays(1), today))
        assertEquals("26 Nov", dayLabel(today.minusDays(2), today))
        assertEquals("3 Jan", dayLabel(LocalDate.of(2026, 1, 3), today))
        // A day after today (a clock set back) is its date, never "tomorrow".
        assertEquals("29 Nov", dayLabel(today.plusDays(1), today))
    }

    @Test
    fun `the label follows the day it is given, not the one it was drawn on`() {
        val stopped = LocalDate.of(2026, 11, 27)
        // The evening the session was stopped, then the next morning with the page left open.
        assertEquals("today", dayLabel(stopped, today = stopped))
        assertEquals("yesterday", dayLabel(stopped, today = stopped.plusDays(1)))
    }
}
