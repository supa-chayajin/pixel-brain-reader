package cloud.wafflecommons.pixelbrainreader.data.meetings

import cloud.wafflecommons.pixelbrainreader.data.work.LogEntry
import cloud.wafflecommons.pixelbrainreader.data.work.LogKind
import cloud.wafflecommons.pixelbrainreader.data.work.MeetingFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** D4: what a calendar entry's prep is built from. */
class MeetingPrepTest {

    @Test
    fun `the subject is the title without its calendar tag and asides`() {
        assertEquals("Weekly sync — Atlas", MeetingPrep.topic("[Work] Weekly sync — Atlas (Teams)"))
        assertEquals("Revue d'archi", MeetingPrep.topic("[Calendrier] Revue d'archi [visio] -"))
        assertEquals("Standup", MeetingPrep.topic("Standup"))
    }

    @Test
    fun `the search keeps the words that say which meeting, each once`() {
        assertEquals(listOf("atlas", "migration"), MeetingPrep.searchWords("Weekly sync Atlas — migration, Atlas"))
        assertEquals(listOf("revue", "archi"), MeetingPrep.searchWords("Réunion : revue d'archi avec l'équipe"))
        assertEquals(emptyList<String>(), MeetingPrep.searchWords("Daily sync"))
    }

    private fun entry(day: Int, kind: LogKind, text: String, tags: List<String> = emptyList()) =
        LogEntry(LocalDateTime.of(2026, 11, day, 10, 0), kind, text, tags = tags)

    @Test
    fun `decisions sharing a word come newest first, other kinds left out`() {
        val log = listOf(
            entry(3, LogKind.DECISION, "Atlas garde PostgreSQL"),
            entry(9, LogKind.DECISION, "Report de la migration", tags = listOf("atlas")),
            entry(10, LogKind.FACT, "Atlas en production"),
            entry(11, LogKind.DECISION, "Budget validé")
        )

        val found = MeetingPrep.decisions(log, MeetingPrep.searchWords("Weekly Atlas"))

        assertEquals(listOf("Report de la migration", "Atlas garde PostgreSQL"), found.map { it.text })
        assertEquals(emptyList<LogEntry>(), MeetingPrep.decisions(log, emptyList()))
    }

    @Test
    fun `the prep goes before the agenda of the toolkit's meeting note`() {
        val note = MeetingFormat.newMeeting(
            "30_Areas/Réunions/2026-11-11 Atlas.md", "Atlas", LocalDate.of(2026, 11, 11), LocalTime.of(10, 0), null, null, LocalDate.of(2026, 11, 11)
        )

        val withPrep = MeetingPrep.withPrep(note, listOf("20_Projects/21_Atlas/Plan.md"), listOf(entry(9, LogKind.DECISION, "Report de la migration")))

        val prep = withPrep.indexOf("## Préparation")
        assertTrue(prep in 0 until withPrep.indexOf("## Ordre du jour"))
        assertTrue("- [[20_Projects/21_Atlas/Plan]]" in withPrep)
        assertTrue("- 2026-11-09 — Report de la migration" in withPrep)
        assertTrue(withPrep.startsWith(note.substring(0, note.indexOf("## Ordre du jour"))))
    }

    @Test
    fun `a note without an agenda gets the prep at its end, and an empty prep says so`() {
        val withPrep = MeetingPrep.withPrep("# Libre\n", emptyList(), emptyList())
        assertTrue(withPrep.startsWith("# Libre\n\n## Préparation"))
        assertTrue("Rien dans le coffre" in withPrep)
    }
}
