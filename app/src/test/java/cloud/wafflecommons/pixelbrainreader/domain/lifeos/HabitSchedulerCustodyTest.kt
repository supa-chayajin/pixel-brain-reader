package cloud.wafflecommons.pixelbrainreader.domain.lifeos

import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.modes.CustodyException
import cloud.wafflecommons.pixelbrainreader.data.modes.CustodySchedule
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** A habit's custody "Days" choice on top of its schedule. The child is home Nov 7–13 and Nov 21–27, 2026. */
class HabitSchedulerCustodyTest {

    private val custody = CustodySchedule(enabled = true, name = "Kid", arrival = "2026-11-06")
    private fun day(month: Int, dayOfMonth: Int) = LocalDate.of(2026, month, dayOfMonth)
    private fun habit(custody: String, frequency: List<String> = emptyList(), mode: String = "WEEKLY") =
        HabitConfig(id = "h", title = "Habit", frequency = frequency, scheduleMode = mode, custody = custody)

    @Test
    fun `a weekend habit kept to the weeks without the child`() {
        val weekends = habit(HabitScheduler.WITHOUT_CHILD, listOf("SAT", "SUN"))
        assertTrue(HabitScheduler.isScheduledOn(weekends, day(11, 14), null, custody))
        assertTrue(HabitScheduler.isScheduledOn(weekends, day(11, 15), null, custody))
        assertFalse(HabitScheduler.isScheduledOn(weekends, day(11, 21), null, custody))
        assertFalse(HabitScheduler.isScheduledOn(weekends, day(11, 16), null, custody))
    }

    @Test
    fun `an every-day habit kept to the days with the child`() {
        val play = habit(HabitScheduler.WITH_CHILD)
        assertTrue(HabitScheduler.isScheduledOn(play, day(11, 13), null, custody))
        assertFalse(HabitScheduler.isScheduledOn(play, day(11, 20), null, custody))
        assertTrue(HabitScheduler.isScheduledOn(play, day(11, 21), null, custody))
    }

    @Test
    fun `exceptions count, and an interval habit waits for a matching day`() {
        val holidays = custody.copy(exceptions = listOf(CustodyException("2026-12-19", "2026-12-26", withChild = true)))
        assertTrue(HabitScheduler.isScheduledOn(habit(HabitScheduler.WITH_CHILD), day(12, 26), null, holidays))
        val interval = habit(HabitScheduler.WITH_CHILD, mode = "INTERVAL").copy(intervalCount = 2, intervalUnit = "DAY")
        assertFalse(HabitScheduler.isScheduledOn(interval, day(11, 18), day(11, 16), custody))
        assertTrue(HabitScheduler.isScheduledOn(interval, day(11, 23), day(11, 16), custody))
    }

    @Test
    fun `without the calendar the choice is ignored`() {
        val play = habit(HabitScheduler.WITH_CHILD)
        assertTrue(HabitScheduler.isScheduledOn(play, day(11, 20), null, null))
        assertTrue(HabitScheduler.isScheduledOn(play, day(11, 20), null, custody.copy(enabled = false)))
        assertTrue(HabitScheduler.isScheduledOn(habit(""), day(11, 20), null, custody))
    }

    @Test
    fun `the card names the days`() {
        assertEquals("SAT, SUN · without Kid", HabitScheduler.describe(habit(HabitScheduler.WITHOUT_CHILD, listOf("SAT", "SUN")), "Kid"))
        assertEquals("Every day", HabitScheduler.describe(habit(HabitScheduler.WITH_CHILD), null))
    }

    @Test
    fun `the vault file keeps the choice and older files read as any day`() {
        val gson = Gson()
        val dto = HabitRepository.HabitConfigDto(id = "h", title = "Habit", custody = "WITH")
        val json = gson.toJson(dto)
        assertTrue(json.contains("\"custody\":\"WITH\""))
        assertFalse(gson.toJson(dto.copy(custody = null)).contains("custody"))
        assertNull(gson.fromJson("""{"id":"h","title":"Habit"}""", HabitRepository.HabitConfigDto::class.java).custody)
    }
}
