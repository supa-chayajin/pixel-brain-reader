package cloud.wafflecommons.pixelbrainreader.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class MeetingFormatTest {

    private val today = LocalDate.of(2026, 10, 18)

    @Test
    fun `a new meeting note has the template and reads back as a meeting`() {
        val path = MeetingFormat.path("20_Projects/Projet/Réunions/", LocalDate.of(2026, 10, 16), "Point / hebdo")
        assertEquals("20_Projects/Projet/Réunions/2026-10-16 Point - hebdo.md", path)

        val content = MeetingFormat.newMeeting(path, "Point hebdo", LocalDate.of(2026, 10, 16), LocalTime.of(10, 0), 45, "PO, Tech lead", today)
        assertTrue(content.contains("type: projet\n"))
        assertTrue(content.contains("  - reunion\n"))
        assertTrue(content.contains("duration_min: 45\n"))
        assertTrue(content.contains("**Date** : 2026-10-16 à 10:00 · 45 min · **Participants** : PO, Tech lead"))

        val meeting = MeetingFormat.parse(WorkNote.from(path, content))
        assertTrue(MeetingFormat.isMeeting(meeting.note))
        assertEquals(LocalDate.of(2026, 10, 16), meeting.date)
        assertEquals(45, meeting.durationMinutes)
        assertTrue(meeting.decisions.isEmpty())
        assertTrue(meeting.actions.isEmpty()) // the template's empty "@moi" line is not an action
    }

    @Test
    fun `decisions are the list under the Décisions heading, actions have owners`() {
        val content = """
            # Réunion
            ## Notes
            - une note
            ## Décisions
            - Garder la base actuelle
            - Reporter la migration
            ## Actions
            - [ ] @moi Écrire l'ADR 📅 2026-10-20
            - [x] @PO Valider le budget
            - [ ] Sans responsable
        """.trimIndent()

        assertEquals(listOf("Garder la base actuelle", "Reporter la migration"), MeetingFormat.decisions(content))
        val actions = MeetingFormat.actions("m.md", content)
        assertEquals(listOf("Écrire l'ADR", "Valider le budget", "Sans responsable"), actions.map { it.text })
        assertEquals(listOf("moi", "PO", null), actions.map { it.owner })
        assertTrue(actions[0].mine)
        assertFalse(actions[1].mine)
        assertEquals(LocalDate.of(2026, 10, 20), actions[0].due)
        assertNull(actions[2].due)
    }

    @Test
    fun `the weekly load counts meetings, requests and off-ticket time`() {
        val meeting = MeetingFormat.parse(WorkNote.from("r/2026-10-14 A.md", "---\ntitle: \"A\"\ntags:\n  - reunion\nduration_min: 60\n---\n"))
        val other = MeetingFormat.parse(WorkNote.from("r/2026-10-15 B.md", "---\ntitle: \"B\"\ntags:\n  - reunion\n---\n"))
        val log = listOf(
            LogEntry(LocalDateTime.of(2026, 10, 13, 9, 0), LogKind.REQUEST, "x"),
            LogEntry(LocalDateTime.of(2026, 10, 13, 9, 0), LogKind.OFF_TICKET, "y", minutes = 30),
            LogEntry(LocalDateTime.of(2026, 10, 6, 9, 0), LogKind.REQUEST, "z")
        )
        val weeks = MeetingFormat.weeklyLoad(listOf(meeting, other), log, 2, today)

        assertEquals(listOf(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 12)), weeks.map { it.weekStart })
        assertEquals(WeekLoad(LocalDate.of(2026, 10, 12), meetings = 2, meetingMinutes = 60, requests = 1, offTicketMinutes = 30), weeks[1])
        assertEquals(WeekLoad(LocalDate.of(2026, 10, 5), meetings = 0, meetingMinutes = null, requests = 1, offTicketMinutes = null), weeks[0])
    }
}
