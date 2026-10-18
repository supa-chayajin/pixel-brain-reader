package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.ai.AiLanguage
import cloud.wafflecommons.pixelbrainreader.data.modes.KeyDate
import cloud.wafflecommons.pixelbrainreader.data.modes.LeavePeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class LeaveDocumentsTest {

    private val today = LocalDate.of(2026, 11, 16)

    private fun note(path: String, status: String?) = WorkNote(path, path.substringAfterLast('/').removeSuffix(".md"), status, LocalDate.of(2026, 10, 30), null, emptyList(), "")

    private fun log(kind: LogKind, text: String, status: String? = null) =
        LogEntry(LocalDateTime.of(2026, 10, 20, 10, 0), kind, text, status = status, requester = "PO")

    @Test
    fun `the briefing is offered in the three days after a leave, not during one`() {
        val leaves = listOf(LeavePeriod("2026-11-02", "2026-11-13"), LeavePeriod("2026-08-01", "2026-08-20"))
        assertEquals("2026-11-13", LeaveDocuments.recentReturn(leaves, today)!!.end)
        assertNull(LeaveDocuments.recentReturn(leaves, today.plusDays(1)))
        assertNull(LeaveDocuments.recentReturn(leaves, LocalDate.of(2026, 11, 13)))
        assertNull(LeaveDocuments.recentReturn(leaves + LeavePeriod("2026-11-16", "2026-11-20"), today))
    }

    @Test
    fun `a request stays open until its status closes it`() {
        assertTrue(LeaveDocuments.isOpenRequest(log(LogKind.REQUEST, "Revue")))
        assertTrue(LeaveDocuments.isOpenRequest(log(LogKind.REQUEST, "Revue", status = "en attente")))
        assertFalse(LeaveDocuments.isOpenRequest(log(LogKind.REQUEST, "Revue", status = "Fait")))
        assertFalse(LeaveDocuments.isOpenRequest(log(LogKind.DECISION, "Choix")))
    }

    @Test
    fun `the handover lists the scope's work and the log, in French, as an inbox draft`() {
        val inputs = HandoverInputs(
            scope = "20_Projects/Projet",
            start = LocalDate.of(2026, 11, 2),
            end = LocalDate.of(2026, 11, 13),
            generated = LocalDate.of(2026, 10, 30),
            inProgress = listOf(note("20_Projects/Projet/API.md", "🏗️ wip")),
            paused = emptyList(),
            decisions = listOf(log(LogKind.DECISION, "Base choisie")),
            openRequests = listOf(log(LogKind.REQUEST, "Revue du plan")),
            refusals = emptyList(),
            keyDates = listOf(KeyDate("2026-11-05", "Comité"))
        )

        val doc = LeaveDocuments.handover(inputs)

        assertTrue(doc.startsWith("---\ntitle: \"Passation — Projet — 2026-11-02 au 2026-11-13\"\n"))
        assertTrue(doc.contains("type: inbox\n"))
        assertTrue(doc.contains("- [[API]] — 🏗️ wip · mis à jour le 2026-10-30\n"))
        assertTrue(doc.contains("- 2026-10-20 — Base choisie (demandé par : PO)\n"))
        assertTrue(doc.contains("_Rien en pause._"))
        assertTrue(doc.contains("- 2026-11-05 — Comité\n"))
        assertFalse(doc.contains("## Synthèse"))
        assertEquals("00_Inbox/Passation Projet 2026-11-02.md", LeaveDocuments.handoverPath(inputs.scope, inputs.start))

        assertTrue(LeaveDocuments.handover(inputs.copy(summary = "Tout va bien.")).contains("## Synthèse\n\n> Rédigée sur l'appareil (Gemini Nano), à vérifier.\n\nTout va bien.\n"))
    }

    @Test
    fun `the briefing counts what changed and what is new`() {
        val doc = LeaveDocuments.briefing(
            BriefingInputs(
                start = LocalDate.of(2026, 11, 2),
                end = LocalDate.of(2026, 11, 13),
                generated = today,
                changed = listOf(note("20_Projects/Projet/API.md", "🏗️ wip")),
                created = emptyList(),
                logEntries = emptyList(),
                upcoming = emptyList()
            )
        )
        assertTrue(doc.contains("## Notes modifiées pendant l'absence (1)\n\n- [[API]]"))
        assertTrue(doc.contains("## Nouvelles notes (0)\n\n_Aucune nouvelle note._"))
    }

    @Test
    fun `the summary prompt stays within its budget and asks for French`() {
        val prompt = LeaveDocuments.summaryPrompt("Tâche.", List(500) { "Élément numéro $it" }, budget = 600)
        assertTrue(prompt.length < 900)
        assertTrue(prompt.endsWith(AiLanguage.DIRECTIVE))
    }
}
