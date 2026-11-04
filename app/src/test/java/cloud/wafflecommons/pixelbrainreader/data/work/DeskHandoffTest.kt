package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.vault.FrontmatterLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DeskHandoffTest {

    private val at = LocalDateTime.of(2026, 11, 4, 21, 10)

    @Test
    fun `a first hand-off creates a DTO note`() {
        val note = DeskHandoffFormat.add(null, "21_Demo", at, "finish the README")
        assertEquals(
            listOf("title", "date_created", "date_updated", "type", "status", "tags", "aliases"),
            FrontmatterLines.keys(note)
        )
        assertEquals("\"🤝 21_Demo — Hand-offs\"", FrontmatterLines.get(note, "title")?.let { "\"$it\"" })
        assertTrue(note.endsWith("- [ ] 2026-11-04 21:10 — finish the README\n"))
    }

    @Test
    fun `lines stay single and match what the desktop hook reads`() {
        val line = DeskHandoffFormat.line(at, "  review PR 42\n  then merge  ")
        assertEquals("- [ ] 2026-11-04 21:10 — review PR 42 then merge", line)
        // worktrace.py: ^\s*- \[ \] (.+)$
        assertTrue(Regex("""^\s*- \[ ] (.+)$""").matches(line))
    }

    @Test
    fun `items are parsed, appended in order, and ticked one exact line at a time`() {
        var note = DeskHandoffFormat.add(null, "21_Demo", at, "one")
        note = DeskHandoffFormat.add(note, "21_Demo", at.plusDays(1), "two")
        val items = DeskHandoffFormat.items(note)
        assertEquals(listOf("one", "two"), items.map { it.text })
        assertEquals(at, items.first().at)
        assertEquals("'2026-11-05'", note.lines().first { it.startsWith("date_updated:") }.substringAfter(": "))

        val ticked = DeskHandoffFormat.complete(note, items.first().line, LocalDate.of(2026, 11, 6))!!
        assertEquals(listOf(true, false), DeskHandoffFormat.items(ticked).map { it.done })
        // Already ticked (e.g. on the desktop meanwhile): nothing to write.
        assertNull(DeskHandoffFormat.complete(ticked, items.first().line, LocalDate.of(2026, 11, 6)))
    }

    @Test
    fun `a line ticked on the desktop and hand-written lines are read too`() {
        val content = "- [x] 2026-11-01 09:00 — done there\n- [ ] no stamp, written by hand\n"
        val items = DeskHandoffFormat.items(content)
        assertEquals(listOf(true, false), items.map { it.done })
        assertEquals("no stamp, written by hand", items[1].text)
        assertNull(items[1].at)
    }

    @Test
    fun `hand-off notes stay out of the project's generic notes and actions`() {
        val root = "20_Projects/21_Demo"
        val notes = mapOf(
            "$root/README.md" to "---\ntitle: Demo\n---\n",
            DeskHandoffFormat.path(root) to DeskHandoffFormat.add(null, "Demo", at, "ship it")
        )
        val s = ProjectFormat.summarize(root, notes, emptyList(), at.toLocalDate())
        assertTrue(s.actions.isEmpty())
        assertTrue(s.recentNotes.none { DeskHandoffFormat.isHandoff(it.path) })
    }
}
