package cloud.wafflecommons.pixelbrainreader.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

class EvidenceReportTest {

    private fun entry(kind: LogKind, text: String, minutes: Int? = null, role: String? = null, tags: List<String> = emptyList(), status: String? = null) =
        LogEntry(LocalDateTime.of(2026, 10, 10, 9, 0), kind, text, role = role, minutes = minutes, tags = tags, status = status)

    @Test
    fun `periods step by month and by quarter`() {
        val october = ReportPeriod.Month(YearMonth.of(2026, 10))
        assertEquals(LocalDate.of(2026, 10, 31), october.end)
        assertEquals("2026-09", october.previous().key)

        val q4 = ReportPeriod.Quarter.of(LocalDate.of(2026, 11, 3))
        assertEquals(ReportPeriod.Quarter(2026, 4), q4)
        assertEquals(LocalDate.of(2026, 10, 1), q4.start)
        assertEquals(LocalDate.of(2026, 12, 31), q4.end)
        assertEquals(ReportPeriod.Quarter(2027, 1), q4.next())
        assertEquals("2026-T4", q4.key)
    }

    @Test
    fun `figures are counted from the log, unmeasured time says so`() {
        val figures = EvidenceFigures(
            entries = listOf(
                entry(LogKind.DECISION, "Choix", role = "Lead", tags = listOf("infra")),
                entry(LogKind.REQUEST, "Revue", status = "fait"),
                entry(LogKind.REQUEST, "Audit"),
                entry(LogKind.OFF_TICKET, "Support", minutes = 90, role = "Lead"),
                entry(LogKind.OFF_TICKET, "Aide")
            ),
            completed = emptyList()
        )
        assertEquals(1, figures.openRequests)
        assertEquals(90, figures.offTicketMinutes)
        assertEquals(2, figures.byRole.getValue("Lead").size)
        assertNull(EvidenceFigures(emptyList(), emptyList()).offTicketMinutes)

        val doc = EvidenceReport.render(ReportPeriod.Month(YearMonth.of(2026, 10)), figures, LocalDate.of(2026, 11, 1))
        assertTrue(doc.contains("# Rapport d'activité — octobre 2026\n"))
        assertTrue(doc.contains("| Demandes | 2 (dont 1 ouverte) |\n"))
        assertTrue(doc.contains("| Temps hors ticket | 1 h 30 (2 entrées) |\n"))
        assertTrue(doc.contains("- Lead : 2 entrées · 1 h 30 hors ticket\n"))
        assertTrue(doc.contains("- #infra : 1\n"))
        assertTrue(doc.contains("_Aucune note des dossiers de travail passée à terminé sur la période._"))
        assertFalse(doc.contains("## Synthèse"))

        val empty = EvidenceReport.render(ReportPeriod.Quarter(2026, 4), EvidenceFigures(emptyList(), emptyList()), LocalDate.of(2027, 1, 2))
        assertTrue(empty.contains("| Temps hors ticket | non mesuré (0 entrée) |"))
        assertTrue(empty.contains("# Rapport d'activité — T4 2026"))
    }
}
