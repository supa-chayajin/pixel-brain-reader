package cloud.wafflecommons.pixelbrainreader.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Decision records (C9): a logged decision becomes an ADR note, linked back from its entry. */
class DecisionRecordsTest {

    private val decision = LogEntry(
        at = LocalDateTime.of(2026, 11, 6, 9, 40),
        kind = LogKind.DECISION,
        text = "Base choisie pour le service : PostgreSQL / pas de NoSQL #infra",
        role = "Lead",
        decider = "CTO",
        requester = "PO",
        reference = "ABC-123",
        tags = listOf("infra")
    )

    @Test
    fun `the note goes next to the log, encrypted when the log is, with a file-safe title`() {
        val plain = MissionLogSettings(folder = "30_Areas/Mission log")
        assertEquals(
            "30_Areas/Mission log/Décisions/ADR 2026-11-06 Base choisie pour le service PostgreSQL pas de NoSQL infra.md",
            DecisionRecords.path(plain, decision)
        )
        assertEquals(
            "99_Private/Mission log/Décisions/ADR 2026-11-06 Base choisie pour le service PostgreSQL pas de NoSQL infra.md.enc",
            DecisionRecords.path(plain.copy(encrypted = true), decision)
        )
        val long = decision.copy(text = "Un très long texte de décision qui dépasse largement la limite fixée pour un nom de fichier")
        assertTrue(DecisionRecords.title(long).length <= 60)
        assertTrue(!DecisionRecords.title(long).endsWith(" "))
        assertEquals("Décision", DecisionRecords.title(decision.copy(text = "///")))
    }

    @Test
    fun `the entry keeps its reference and gains the note's link`() {
        val path = "30_Areas/Mission log/Décisions/ADR 2026-11-06 Base choisie.md"
        assertEquals("ABC-123 · [[ADR 2026-11-06 Base choisie]]", DecisionRecords.reference("ABC-123", path))
        assertEquals("[[ADR 2026-11-06 Base choisie]]", DecisionRecords.reference(null, "$path.enc"))
    }

    @Test
    fun `the ADR is French, pre-filled from the entry, with the rest left to fill in`() {
        val path = "30_Areas/Mission log/Décisions/ADR 2026-11-06 Base choisie.md"
        val note = DecisionRecords.render(path, decision, LocalDate.of(2026, 11, 6))
        assertTrue(note.startsWith("---\n"))
        assertTrue(note.contains("# ADR — Base choisie pour le service PostgreSQL pas de NoSQL infra\n"))
        assertTrue(note.contains("**Date** : 2026-11-06 · **Statut** : acté · **Rôle** : Lead · **Décidé par** : CTO"))
        assertTrue(note.contains("## Contexte\n\nDemandé par : PO.\n\n- \n"))
        assertTrue(note.contains("## Options envisagées\n"))
        assertTrue(note.contains("## Décision\n\nBase choisie pour le service : PostgreSQL / pas de NoSQL #infra\n"))
        assertTrue(note.contains("## Conséquences\n"))
        assertTrue(note.contains("> Créée depuis le journal de mission (2026-11-06 09:40) · réf. : ABC-123."))
        assertTrue(note.contains("  - adr\n") && note.contains("  - decision\n") && note.contains("  - infra\n"))
    }

    @Test
    fun `a wikilink reference round-trips through the log line, other brackets don't break it`() {
        val linked = decision.copy(text = "Base choisie", tags = emptyList(), reference = "ABC-123 · [[ADR 2026-11-06 Base choisie]]")
        val line = MissionLogFormat.render(linked)
        assertTrue(line.contains("[ref:: ABC-123 · [[ADR 2026-11-06 Base choisie]]]"))
        val back = MissionLogFormat.parseLine(line)!!
        assertEquals(linked.reference, back.reference)
        assertEquals("Base choisie", back.text)
        assertEquals("CTO", back.decider)

        // A stray bracket in a value is still turned into a parenthesis.
        val odd = MissionLogFormat.render(linked.copy(status = "acté [v2]"))
        assertEquals("acté (v2)", MissionLogFormat.parseLine(odd)!!.status)

        // A malformed line (unclosed links) parses quickly and doesn't take the next field with it.
        val start = System.nanoTime()
        val broken = MissionLogFormat.parseLine("- 2026-11-06 09:40 — x [type:: décision] [ref:: " + "[[".repeat(40) + "] [role:: Lead]")
        assertTrue((System.nanoTime() - start) < 1_000_000_000L)
        assertEquals("Lead", broken!!.role)
    }
}
