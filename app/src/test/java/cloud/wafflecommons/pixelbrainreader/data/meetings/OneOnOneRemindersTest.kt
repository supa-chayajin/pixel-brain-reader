package cloud.wafflecommons.pixelbrainreader.data.meetings

import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import cloud.wafflecommons.pixelbrainreader.data.people.PersonKind
import cloud.wafflecommons.pixelbrainreader.data.people.PersonLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** The 1:1 reminder (12.19, the owner's D6): which meetings, when, and in which mode. */
class OneOnOneRemindersTest {

    private val day = LocalDate.of(2026, 11, 16)

    private fun meeting(title: String, eventId: String?, at: LocalTime = LocalTime.of(10, 0)) = TimelineEntryEntity(
        id = "entry-$eventId",
        date = day,
        time = at,
        content = "[Work] $title",
        googleEventId = eventId
    )

    @Test
    fun `titles that say 1-1, in French or English, and the ones that don't`() {
        listOf("1:1 Alex", "Alex 1-1", "One-on-one", "one to one", "Tête-à-tête", "tete a tete", "Point individuel", "1on1 weekly")
            .forEach { assertTrue(it, OneOnOneReminders.isOneOnOneTitle(it)) }
        listOf("Sprint 21-1 review", "Release 1:12", "Standup", "Planning Q1", "Pointage")
            .forEach { assertFalse(it, OneOnOneReminders.isOneOnOneTitle(it)) }
    }

    @Test
    fun `a link wins over the title, and its person's kind is the reason`() {
        val links = PersonLinks.decode(
            PersonLinks.link(PersonLinks.link(null, "work1", "11111111", PersonKind.WORK), "home1", "22222222", PersonKind.PERSONAL)
        )

        assertEquals(OneOnOneReminders.Reason.WORK_PERSON, OneOnOneReminders.reasonFor(meeting("Weekly sync", "work1_20261116T090000Z"), links))
        assertEquals(OneOnOneReminders.Reason.PERSONAL_PERSON, OneOnOneReminders.reasonFor(meeting("1:1 dinner", "home1"), links))
        assertEquals(OneOnOneReminders.Reason.TITLE, OneOnOneReminders.reasonFor(meeting("1:1 Sam", "other"), links))
        assertNull(OneOnOneReminders.reasonFor(meeting("Weekly sync", "other"), links))
        assertNull(OneOnOneReminders.reasonFor(meeting("1:1 typed by hand", null), links))
    }

    @Test
    fun `due 30 minutes before, never for a meeting already that close`() {
        val now = LocalDateTime.of(day, LocalTime.of(9, 0))
        val entries = listOf(
            meeting("1:1 Sam", "a", LocalTime.of(10, 0)),
            meeting("1:1 Kim", "b", LocalTime.of(9, 20)),
            meeting("Standup", "c", LocalTime.of(11, 0))
        )

        val due = OneOnOneReminders.due(entries, emptyMap(), now)

        assertEquals(listOf("a"), due.map { it.eventId })
        assertEquals(30 * 60_000L, due.single().delayMs)
    }

    @Test
    fun `a personal person's meeting posts in any mode, the rest in Work mode only`() {
        assertTrue(OneOnOneReminders.posts(OneOnOneReminders.Reason.PERSONAL_PERSON, muteWorkNotifications = true))
        assertFalse(OneOnOneReminders.posts(OneOnOneReminders.Reason.WORK_PERSON, muteWorkNotifications = true))
        assertFalse(OneOnOneReminders.posts(OneOnOneReminders.Reason.TITLE, muteWorkNotifications = true))
        assertTrue(OneOnOneReminders.posts(OneOnOneReminders.Reason.TITLE, muteWorkNotifications = false))
    }
}
