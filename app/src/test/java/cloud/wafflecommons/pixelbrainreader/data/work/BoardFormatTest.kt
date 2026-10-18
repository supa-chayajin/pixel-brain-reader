package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.vault.FrontmatterLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BoardFormatTest {

    private val today = LocalDate.of(2026, 10, 18)
    private val path = "20_Projects/Projet/API.md"
    private val note = """
        ---
        title: "API"
        date_created: '2026-09-01'
        date_updated: '2026-10-01'
        type: projet
        status: active
        tags:
          - api
        aliases: []
        ---
        # API

        Corps de la note.
    """.trimIndent() + "\n"

    @Test
    fun `a move rewrites only status and date_updated`() {
        val moved = BoardFormat.move(path, note, StatusColumn.PAUSED, today)

        val expected = note.replace("status: active", "status: ⏸️ en pause").replace("date_updated: '2026-10-01'", "date_updated: '2026-10-18'")
        assertEquals(expected, moved)
    }

    @Test
    fun `card details are frontmatter keys, cleared by removing them`() {
        val detailed = BoardFormat.setDetails(path, note, "Écrire le \"contrat\"", "Accès", LocalDate.of(2026, 10, 10), today)
        val card = BoardCard.of(WorkNote.from(path, detailed))
        assertEquals("Écrire le \"contrat\"", card.nextStep)
        assertEquals("Accès", card.blockers)
        assertEquals(LocalDate.of(2026, 10, 10), card.targetDate)
        assertTrue(card.overdue(today))
        assertTrue(detailed.endsWith("# API\n\nCorps de la note.\n"))

        val cleared = BoardFormat.setDetails(path, detailed, null, " ", null, today)
        assertNull(FrontmatterLines.get(cleared, "next_step"))
        assertNull(FrontmatterLines.get(cleared, "blockers"))
        assertNull(FrontmatterLines.get(cleared, "target_date"))
    }

    @Test
    fun `a note without frontmatter gets the standard block before the first edit`() {
        val moved = BoardFormat.move("20_Projects/Projet/Idée.md", "# Idée\n\nTexte\n", StatusColumn.WIP, today)
        assertTrue(moved.startsWith("---\ntitle: \"Idée\"\ndate_created: '2026-10-18'\ndate_updated: '2026-10-18'\ntype: projet\nstatus: 🏗️ wip\n"))
        assertTrue(moved.endsWith("# Idée\n\nTexte\n"))
    }

    @Test
    fun `daily notes are never cards and a done card is never late`() {
        assertFalse(BoardFormat.isCard("10_Journal/2026-10-18.md"))
        assertTrue(BoardFormat.isCard(path))
        val done = BoardCard.of(WorkNote.from(path, BoardFormat.setDetails(path, BoardFormat.move(path, note, StatusColumn.DONE, today), null, null, LocalDate.of(2026, 1, 1), today)))
        assertFalse(done.overdue(today))
    }

    @Test
    fun `the synthesis counts the columns and lists what needs attention`() {
        val wip = BoardCard.of(WorkNote.from(path, BoardFormat.setDetails(path, BoardFormat.move(path, note, StatusColumn.WIP, today), "Livrer", null, LocalDate.of(2026, 10, 1), today)))
        val doc = BoardFormat.synthesis("20_Projects/Projet", listOf(wip), today)
        assertTrue(doc.contains("| En cours | 1 |\n"))
        assertTrue(doc.contains("## Échéances dépassées\n\n- [[API]] — prochaine étape : Livrer · échéance : 2026-10-01 (dépassée)\n"))
        assertTrue(doc.contains("(1 carte)"))
    }
}
