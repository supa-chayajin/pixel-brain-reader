package cloud.wafflecommons.pixelbrainreader.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

class MissionLogFormatTest {

    private val full = LogEntry(
        at = LocalDateTime.of(2026, 10, 18, 9, 40),
        kind = LogKind.DECISION,
        text = "Base choisie pour le service, voir [[ADR 12]]",
        role = "Lead",
        status = "acté",
        minutes = 30,
        requester = "PO",
        decider = "CTO",
        reference = "ABC-123",
        tags = listOf("infra", "Sécurité")
    )

    @Test
    fun `an entry is one line of Dataview fields and reads back the same`() {
        val line = MissionLogFormat.render(full)

        assertEquals(
            "- 2026-10-18 09:40 — Base choisie pour le service, voir [[ADR 12]] [type:: décision] [role:: Lead] [statut:: acté] " +
                "[minutes:: 30] [demandeur:: PO] [decideur:: CTO] [ref:: ABC-123] #infra #sécurité",
            line
        )
        assertEquals(full.copy(tags = listOf("infra", "sécurité"), sourceLine = line), MissionLogFormat.parseLine(line))
    }

    @Test
    fun `English keys and values, an en dash and no fields but the type are read too`() {
        val entry = MissionLogFormat.parseLine("- 2026-10-01 14:05 – Asked to review the plan [kind:: request] [requester:: Client] [status:: open]")!!
        assertEquals(LogKind.REQUEST, entry.kind)
        assertEquals("Client", entry.requester)
        assertEquals("open", entry.status)
        assertEquals("Asked to review the plan", entry.text)

        assertEquals(LogKind.OFF_TICKET, MissionLogFormat.parseLine("- 2026-10-01 14:05 - Support [type:: hors-ticket] [minutes:: 45]")!!.kind)
    }

    @Test
    fun `lines that aren't entries are not entries`() {
        assertNull(MissionLogFormat.parseLine("# Journal de mission — octobre 2026"))
        assertNull(MissionLogFormat.parseLine("- 2026-10-01 14:05 — No type field"))
        assertNull(MissionLogFormat.parseLine("- an ordinary list item [type:: fait]"))
    }

    @Test
    fun `issue numbers and C# are text, not tags`() {
        val entry = MissionLogFormat.parseLine("- 2026-10-01 10:00 — Fix issue #123 in the C# client #backend [type:: fait]")!!
        assertEquals(listOf("backend"), entry.tags)
        assertEquals("Fix issue #123 in the C# client", entry.text)
    }

    @Test
    fun `a new entry goes in time order and the user's own lines stay`() {
        val start = MissionLogFormat.newFile("30_Areas/Mission log/2026-10.md", YearMonth.of(2026, 10), LocalDate.of(2026, 10, 1)) +
            "Notes à moi, gardées telles quelles.\n"
        val morning = full.copy(at = LocalDateTime.of(2026, 10, 18, 8, 0), text = "Matin")
        val evening = full.copy(at = LocalDateTime.of(2026, 10, 18, 18, 0), text = "Soir")
        val noon = full.copy(at = LocalDateTime.of(2026, 10, 18, 12, 0), text = "Midi")

        val content = listOf(evening, morning, noon).fold(start) { acc, entry -> MissionLogFormat.insert(acc, entry) }

        assertEquals(listOf("Matin", "Midi", "Soir"), MissionLogFormat.parse(content).map { it.text })
        assertTrue(content.contains("Notes à moi, gardées telles quelles.\n"))
        assertTrue(content.contains("type: area\n"))
        assertTrue(content.contains("# Journal de mission — octobre 2026\n"))
    }

    @Test
    fun `an edit or a delete replaces exactly the line it was read from`() {
        val content = MissionLogFormat.insert("# Log\n", full)
        val read = MissionLogFormat.parse(content).single()

        val edited = MissionLogFormat.replace(content, read, read.copy(text = "Changed"))!!
        assertEquals("Changed", MissionLogFormat.parse(edited).single().text)

        val deleted = MissionLogFormat.replace(content, read, null)!!
        assertTrue(MissionLogFormat.parse(deleted).isEmpty())

        assertNull(MissionLogFormat.replace(edited, read, null)) // the line changed on disk meanwhile
    }

    @Test
    fun `brackets can't break a field and a text stays on one line`() {
        val line = MissionLogFormat.render(full.copy(text = "Two\nlines", reference = "[x] link", tags = emptyList()))
        val read = MissionLogFormat.parseLine(line)!!
        assertEquals("Two lines", read.text)
        assertEquals("(x) link", read.reference)
    }

    @Test
    fun `plain and encrypted logs live in different places`() {
        val month = YearMonth.of(2026, 10)
        assertEquals("30_Areas/Mission log/2026-10.md", MissionLogFormat.pathFor(MissionLogSettings(), month))
        assertEquals("99_Private/Mission log/2026-10.md.enc", MissionLogFormat.pathFor(MissionLogSettings(encrypted = true), month))
        assertEquals("20_Projects/Client/Mission log/2026-10.md", MissionLogFormat.pathFor(MissionLogSettings(folder = "/20_Projects/Client/Mission log/"), month))
    }
}
