package cloud.wafflecommons.pixelbrainreader.data.modes

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * The custody calendar: every other week from one Friday to the next. In the fortnight these tests
 * use, the child arrives on Friday 2026-11-06 (week 45) and leaves on Friday 2026-11-13 (week 46).
 */
class CustodyScheduleTest {

    private val custody = CustodySchedule(enabled = true, name = "Kid", arrival = "2026-11-06")
    private val handover = LocalTime.of(18, 0)
    private fun day(month: Int, dayOfMonth: Int, year: Int = 2026) = LocalDate.of(year, month, dayOfMonth)

    @Test
    fun `a day with the child is a day they wake up at home`() {
        assertFalse(custody.withChildOn(day(11, 5)))
        assertFalse("the arrival day doesn't count", custody.withChildOn(day(11, 6)))
        (7..13).forEach { assertTrue("Nov $it", custody.withChildOn(day(11, it))) }
        (14..20).forEach { assertFalse("Nov $it", custody.withChildOn(day(11, it))) }
        assertTrue(custody.withChildOn(day(11, 21)))
    }

    @Test
    fun `the rhythm goes on every other week through a 53-week year`() {
        // Week 53 of 2026 and week 1 of 2027 are both odd; the alternation doesn't care.
        (28..31).forEach { assertFalse("Dec $it", custody.withChildOn(day(12, it))) }
        assertFalse(custody.withChildOn(day(1, 1, 2027)))
        assertTrue(custody.withChildOn(day(1, 2, 2027)))
        (4..8).forEach { assertTrue("Jan $it", custody.withChildOn(day(1, it, 2027))) }
        assertFalse(custody.withChildOn(day(1, 9, 2027)))
    }

    @Test
    fun `on a handover day the evening follows the new week`() {
        assertTrue(custody.withChildAt(day(11, 13).atTime(7, 0), handover))
        assertTrue(custody.withChildAt(day(11, 13).atTime(17, 59), handover))
        assertFalse(custody.withChildAt(day(11, 13).atTime(18, 0), handover))
        assertFalse(custody.withChildAt(day(11, 20).atTime(17, 59), handover))
        assertTrue(custody.withChildAt(day(11, 20).atTime(18, 0), handover))
        assertTrue(custody.withChildAt(day(11, 21).atTime(10, 0), handover))
        assertFalse(custody.withChildAt(day(11, 16).atTime(20, 0), handover))
        assertTrue(custody.withChildAt(day(11, 9).atTime(20, 0), handover))
    }

    @Test
    fun `an exception wins over the rhythm for its whole days, the later start over an earlier one`() {
        val holidays = custody.copy(
            exceptions = listOf(
                CustodyException("2026-12-19", "2026-12-26", withChild = true, label = "Christmas, first half"),
                CustodyException("2026-12-24", "2026-12-26", withChild = false)
            )
        )
        assertTrue(holidays.withChildOn(day(12, 19)))
        assertTrue(holidays.withChildOn(day(12, 23)))
        assertFalse(holidays.withChildOn(day(12, 25)))
        assertFalse(holidays.withChildOn(day(12, 26)))
        assertFalse("back to the rhythm", holidays.withChildOn(day(12, 27)))
        assertTrue(holidays.withChildAt(day(12, 19).atTime(23, 0), handover))
        assertFalse(holidays.withChildAt(day(12, 24).atTime(8, 0), handover))
    }

    @Test
    fun `off or without a rhythm it is inactive`() {
        assertFalse(custody.copy(enabled = false).isActive)
        assertFalse(CustodySchedule(enabled = true).isActive)
        assertFalse(CustodySchedule(enabled = true).withChildOn(day(11, 9)))
        assertNull(CustodySchedule(enabled = true).arrivesInWeekOf(day(11, 13)))
        assertEquals(CustodySchedule.DEFAULT_NAME, CustodySchedule(name = "  ").label)
    }

    @Test
    fun `the rhythm is set from this week`() {
        val unset = CustodySchedule(enabled = true)
        assertEquals("2026-11-06", unset.withThisWeek(day(11, 13), arrives = false).arrival)
        assertEquals("2026-11-20", unset.withThisWeek(day(11, 18), arrives = true).arrival)
        assertEquals(false, custody.arrivesInWeekOf(day(11, 10)))
        assertEquals(true, custody.arrivesInWeekOf(day(11, 16)))
    }

    @Test
    fun `moving the handover day keeps the weeks`() {
        val saturday = custody.withHandoverDay(DayOfWeek.SATURDAY)
        assertEquals("2026-11-07", saturday.arrival)
        assertEquals(DayOfWeek.SATURDAY, saturday.handoverDayOfWeek)
        val unset = CustodySchedule(enabled = true).withHandoverDay(DayOfWeek.WEDNESDAY)
        assertEquals(DayOfWeek.WEDNESDAY, unset.handoverDayOfWeek)
        assertEquals("", unset.arrival)
    }

    @Test
    fun `a modes file written before the calendar still loads, and the calendar round-trips`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = """{"workFolders":["Work"],"scheduleEnabled":true,"outsideHoursMode":"PERSONAL"}"""
        val config = json.decodeFromString(ModesConfig.serializer(), old)
        assertFalse(config.custody.isActive)
        assertEquals(ContextMode.OFF, config.withChildMode)
        assertEquals(emptyList<String>(), config.personalProjects)
        val full = config.copy(custody = custody, personalProjects = listOf("20_Projects/21_A"))
        assertEquals(full, json.decodeFromString(ModesConfig.serializer(), json.encodeToString(ModesConfig.serializer(), full)))
    }
}
