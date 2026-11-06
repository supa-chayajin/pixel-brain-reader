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
        // Nothing to say about meetings or projects: no row for them.
        assertFalse(empty.contains("| Réunions |"))
        assertFalse(empty.contains("| Commits (projets) |"))
    }

    private fun meeting(path: String, date: LocalDate, minutes: Int?) =
        Meeting(WorkNote.from(path, "---\ntitle: \"Point\"\n---\n"), date, minutes, emptyList(), emptyList())

    @Test
    fun `requesters, meetings, projects and objectives join the report`() {
        val figures = EvidenceFigures(
            entries = listOf(
                LogEntry(LocalDateTime.of(2026, 10, 2, 9, 0), LogKind.REQUEST, "Revue", requester = "PO"),
                LogEntry(LocalDateTime.of(2026, 10, 3, 9, 0), LogKind.REQUEST, "Audit", requester = "PO", status = "fait"),
                LogEntry(LocalDateTime.of(2026, 10, 4, 9, 0), LogKind.OFF_TICKET, "Aide", minutes = 45, requester = "Support"),
                LogEntry(LocalDateTime.of(2026, 10, 5, 9, 0), LogKind.DECISION, "Choix de la base", role = "Lead")
            ),
            completed = emptyList(),
            meetings = listOf(meeting("30_Areas/Réunions/a.md", LocalDate.of(2026, 10, 6), 60), meeting("30_Areas/Réunions/b.md", LocalDate.of(2026, 10, 7), 30)),
            projectActivity = listOf(
                ProjectPeriodActivity("20_Projects/21_Demo", "21_Demo", 12, 3, listOf(WorkNote.from("20_Projects/21_Demo/RFCs/RFC-1.md", "---\ntitle: \"RFC 1\"\ndate_updated: '2026-10-20'\n---\n"))),
                ProjectPeriodActivity("20_Projects/23_Other", "23_Other", 0, 0, emptyList())
            ),
            objectivesProgress = listOf(ObjectivePeriodProgress("Lead", "Lead", 2, 5, 3))
        )
        assertEquals(listOf("PO", "Support"), figures.byRequester.keys.sorted())
        assertEquals(90, figures.meetingMinutes)
        assertEquals(12, figures.projectCommits)

        val doc = EvidenceReport.render(ReportPeriod.Month(YearMonth.of(2026, 10)), figures, LocalDate.of(2026, 11, 1))
        assertTrue(doc.contains("| Réunions | 2 (1 h 30) |\n"))
        assertTrue(doc.contains("| Commits (projets) | 12 sur 1 projet |\n"))
        assertTrue(doc.contains("## Par demandeur\n\n- PO : 2 demandes (dont 1 ouverte)\n- Support : 0 demande · 45 min hors ticket\n"))
        assertTrue(doc.indexOf("## Par rôle") < doc.indexOf("## Par demandeur"))
        assertTrue(doc.contains("## Objectifs\n\n- [[Lead]] : 2 jalons sur 5 · 3 entrées du journal sur la période\n"))
        assertTrue(doc.contains("- [[20_Projects/21_Demo/README|21_Demo]] : 12 commits · 3 sessions IA\n  - [[RFC-1]] — terminé le 2026-10-20\n"))

        val noDuration = EvidenceReport.render(
            ReportPeriod.Month(YearMonth.of(2026, 10)),
            EvidenceFigures(emptyList(), emptyList(), meetings = listOf(meeting("30_Areas/Réunions/c.md", LocalDate.of(2026, 10, 8), null))),
            LocalDate.of(2026, 11, 1)
        )
        assertTrue(noDuration.contains("| Réunions | 1 (durée non mesurée) |\n"))
    }

    @Test
    fun `facts for the summary carry the role held, then objectives and projects`() {
        val figures = EvidenceFigures(
            entries = listOf(
                LogEntry(LocalDateTime.of(2026, 10, 5, 9, 0), LogKind.DECISION, "Choix de la base", role = "Lead"),
                LogEntry(LocalDateTime.of(2026, 10, 6, 9, 0), LogKind.FACT, "Mise en prod"),
                LogEntry(LocalDateTime.of(2026, 10, 7, 9, 0), LogKind.OFF_TICKET, "Support", minutes = 30, role = "Lead")
            ),
            completed = listOf(WorkNote.from("30_Areas/x/Livrable.md", "---\ntitle: \"Livrable\"\n---\n")),
            projectActivity = listOf(ProjectPeriodActivity("20_Projects/21_Demo", "21_Demo", 4, 1, emptyList())),
            objectivesProgress = listOf(ObjectivePeriodProgress("Lead", "Lead", 1, 3, 0))
        )
        assertEquals(
            listOf(
                "[Lead] décision : Choix de la base",
                "fait : Mise en prod",
                "Objectif « Lead » : 1 jalon sur 3",
                "Projet 21_Demo : 4 commits, 1 session IA",
                "Terminé : Livrable"
            ),
            EvidenceReport.facts(figures)
        )
    }

    @Test
    fun `a project's activity is summed over the period, mirrors and older worklogs aside`() {
        val root = "20_Projects/21_Demo"
        val worklog = "# Worklog\n\n## 🧬 Commits\n\n- feat: a\n- fix: b\n\n## 🤖 AI sessions\n\n- 2 session(s) · 9 prompt(s) · 4 tool call(s)\n"
        val notes = mapOf(
            "$root/README.md" to "---\ntitle: \"📌 21_Demo — Project Log\"\n---\n",
            "$root/Worklog/2026-10-03.md" to worklog,
            "$root/Worklog/2026-10-20.md" to worklog,
            "$root/Worklog/2026-09-30.md" to worklog, // before the period
            "$root/RFCs/RFC-2.md" to "---\ntitle: \"RFC 2\"\ndate_updated: '2026-10-15'\nstatus: 🟢 done\n---\n",
            "$root/RFCs/RFC-3.md" to "---\ntitle: \"RFC 3\"\ndate_updated: '2026-10-15'\nstatus: 🏗️ wip\n---\n",
            "$root/Repo/RFCs/RFC-9.md" to "---\ntitle: \"RFC 9\"\ndate_updated: '2026-10-15'\nstatus: 🟢 done\nmirror_of: \"demo:rfcs/RFC-9.md\"\n---\n"
        )
        val october = ReportPeriod.Month(YearMonth.of(2026, 10))
        val activity = ProjectFormat.periodActivity(root, notes, october.start, october.end)!!
        assertEquals("21_Demo", activity.title)
        assertEquals(4, activity.commits)
        assertEquals(4, activity.sessions)
        assertEquals(listOf("$root/RFCs/RFC-2.md"), activity.doneNotes.map { it.path })
        assertNull(ProjectFormat.periodActivity(root, notes, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
    }

    @Test
    fun `the work and personal scopes split the vault by the work folders`() {
        val work = listOf("20_Projects/22_Client", "30_Areas/Réunions")
        assertTrue(EvidenceScope.WORK.includes("20_Projects/22_Client/README.md", work))
        assertFalse(EvidenceScope.WORK.includes("20_Projects/21_Demo/README.md", work))
        assertTrue(EvidenceScope.PERSONAL.includes("20_Projects/21_Demo", work))
        assertFalse(EvidenceScope.PERSONAL.includes("30_Areas/Réunions/a.md", work))
        // Without work folders there is no split.
        assertTrue(EvidenceScope.WORK.includes("20_Projects/21_Demo", emptyList()))
        assertTrue(EvidenceScope.PERSONAL.includes("20_Projects/21_Demo", emptyList()))

        val personal = EvidenceReport.render(
            ReportPeriod.Month(YearMonth.of(2026, 10)),
            EvidenceFigures(emptyList(), emptyList(), scope = EvidenceScope.PERSONAL),
            LocalDate.of(2026, 11, 1)
        )
        assertTrue(personal.contains("# Rapport d'activité personnel — octobre 2026\n"))
        assertFalse(personal.contains("| Faits |"))
        assertTrue(personal.contains("_Aucune note de projet personnel passée à terminé sur la période._"))
        assertEquals("00_Inbox/Rapport d'activité personnel 2026-10.md", EvidenceReport.path(ReportPeriod.Month(YearMonth.of(2026, 10)), EvidenceScope.PERSONAL))
        assertEquals("00_Inbox/Rapport d'activité 2026-10.md", EvidenceReport.path(ReportPeriod.Month(YearMonth.of(2026, 10))))
    }
}
