package cloud.wafflecommons.pixelbrainreader.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** The briefs sent to the 🧭 Coach preset: French, measured facts only, always under budget. */
class CoachSeedsTest {

    private val today = LocalDate.of(2026, 11, 6)

    private val objective = Objective(
        note = WorkNote.from("30_Areas/Objectifs/Lead.md", "---\ntitle: \"Lead\"\ndate_updated: '2026-11-01'\ntags:\n  - objectif\n---\n"),
        milestones = listOf(
            Milestone("Cadrer l'équipe", done = true, due = null, sourceLine = "- [x] Cadrer l'équipe"),
            Milestone("Revue d'architecture", done = false, due = LocalDate.of(2026, 11, 3), sourceLine = "- [ ] Revue d'architecture 📅 2026-11-03"),
            Milestone("Plan de formation", done = false, due = null, sourceLine = "- [ ] Plan de formation")
        ),
        material = listOf(LogEntry(LocalDateTime.of(2026, 11, 2, 9, 0), LogKind.DECISION, "Choix du socle")),
        linkedProjects = listOf(
            ProjectFormat.summarize(
                "20_Projects/21_Demo",
                mapOf("20_Projects/21_Demo/Worklog/2026-11-05.md" to "## 🧬 Commits\n\n- feat: a\n- fix: b\n"),
                emptyList(), today
            )
        )
    )

    @Test
    fun `an objective's brief names its open milestones, linked projects and log, then asks`() {
        val seed = CoachSeeds.objective(objective, today)
        assertTrue(seed.startsWith("Mon objectif « Lead » : 1 jalon sur 3."))
        assertTrue(seed.contains("- Jalon ouvert : Revue d'architecture (en retard, échéance 2026-11-03)"))
        assertTrue(seed.contains("- Jalon ouvert : Plan de formation\n"))
        assertTrue(seed.contains("- Projet lié : 21_Demo, 2 commits en 14 jours"))
        assertTrue(seed.contains("- Journal 2026-11-02 (décision) : Choix du socle"))
        assertFalse(seed.contains("Rien n'a bougé"))
        assertTrue(seed.endsWith("\n\nAide-moi à choisir le prochain pas concret."))
    }

    @Test
    fun `the load brief keeps recorded minutes only and asks what to refuse`() {
        val report = LoadRecoveryReport(
            comparisons = emptyList(), workDays = 20, loggedDays = 12,
            overload = OverloadSignal(40, weekMinutes = 840, usualWeekMinutes = 600, reasons = listOf("Less sleep than usual this week")),
            needsHealthPermission = false,
            breakdown = LoadBreakdown(
                byRole = listOf(LoadShare("Lead", offTicketMinutes = 90, requests = 2, openRequests = 1, refusals = 1)),
                byRequester = listOf(LoadShare("PO", offTicketMinutes = null, requests = 3, openRequests = 2, refusals = 0)),
                openRequests = listOf(LogEntry(LocalDateTime.of(2026, 11, 4, 9, 0), LogKind.REQUEST, "Revue sécurité")),
                overdueMyActions = 2
            )
        )
        val seed = CoachSeeds.load(report)
        assertTrue(seed.contains("- Réunions et hors-ticket : 14 h 00 sur 7 jours, contre 10 h 00 d'habitude (+40 %)."))
        assertTrue(seed.contains("- Rôle Lead : 1 h 30 hors ticket, 2 demandes dont 1 ouverte, 1 refus"))
        // No minutes recorded for the requester: none shown, never "0 min".
        assertTrue(seed.contains("- Demandeur PO : 3 demandes dont 2 ouvertes\n"))
        assertTrue(seed.contains("- Demande ouverte : Revue sécurité"))
        assertTrue(seed.contains("- 2 actions à moi en retard."))
        // The English UI reasons never leak into the French brief.
        assertFalse(seed.contains("Less sleep"))
        assertTrue(seed.endsWith("Qu'est-ce que je devrais refuser, renégocier ou transformer en ticket ?"))

        val quiet = CoachSeeds.load(LoadRecoveryReport(emptyList(), 0, 0, null, false))
        assertTrue(quiet.contains("- Rien de noté cette semaine par rôle ni par demandeur."))
    }

    @Test
    fun `the evidence brief carries roles, decisions and finished work`() {
        val figures = EvidenceFigures(
            entries = listOf(
                LogEntry(LocalDateTime.of(2026, 10, 5, 9, 0), LogKind.DECISION, "Choix de la base", role = "Lead"),
                LogEntry(LocalDateTime.of(2026, 10, 6, 9, 0), LogKind.FACT, "Mise en prod", role = "Lead")
            ),
            completed = listOf(WorkNote.from("30_Areas/x/Livrable.md", "---\ntitle: \"Livrable\"\n---\n"))
        )
        val seed = CoachSeeds.evidence(ReportPeriod.Month(YearMonth.of(2026, 10)).frenchLabel, figures)
        assertTrue(seed.startsWith("Mon bilan pro de octobre 2026 : 2 entrées dans mon journal de mission."))
        assertTrue(seed.contains("- Rôle Lead : 2 entrées"))
        assertTrue(seed.contains("- Décision : Choix de la base"))
        assertTrue(seed.contains("- Terminé : Livrable"))
        assertTrue(seed.endsWith("Quel résultat manque au journal avant que j'écrive le bilan ?"))
    }

    @Test
    fun `a brief never exceeds the budget and its question always fits`() {
        val huge = objective.copy(
            milestones = (1..200).map { Milestone("Jalon très long numéro $it " + "x".repeat(300), done = false, due = null, sourceLine = "- [ ] $it") }
        )
        val seed = CoachSeeds.objective(huge, today)
        assertTrue(seed.length <= CoachSeeds.MAX_CHARS)
        assertTrue(seed.endsWith("Aide-moi à choisir le prochain pas concret."))
        // Long facts are cut, never split across lines.
        assertTrue(seed.lines().filter { it.startsWith("- ") }.all { it.length <= 182 })

        val composed = CoachSeeds.compose("Intro.", List(50) { "fait $it" }, "Question ?")
        assertEquals("Question ?", composed.lines().last())
        assertTrue(composed.length <= CoachSeeds.MAX_CHARS)
    }
}
