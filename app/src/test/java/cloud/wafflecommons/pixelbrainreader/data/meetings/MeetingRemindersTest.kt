package cloud.wafflecommons.pixelbrainreader.data.meetings

import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** D4's notification comes 10 minutes before each calendar meeting still to come. */
class MeetingRemindersTest {

    private val day = LocalDate.of(2026, 11, 12)

    private fun entry(time: String, eventId: String?) =
        TimelineEntryEntity(date = day, time = LocalTime.parse(time), content = "[Work] Meeting", googleEventId = eventId)

    @Test
    fun `each meeting still more than ten minutes away gets its wait`() {
        val now = LocalDateTime.of(day, LocalTime.of(9, 0))
        val due = MeetingReminders.due(
            listOf(entry("09:05", "soon"), entry("09:10", "exactly"), entry("10:00", "standup"), entry("11:00", null)),
            now
        )
        assertEquals(listOf(MeetingReminders.Due("standup", 50 * 60_000L)), due)
    }

    @Test
    fun `one work per event, so a new import replaces it`() {
        assertEquals("meeting_prep_abc123", MeetingReminders.workName("abc123"))
    }
}
