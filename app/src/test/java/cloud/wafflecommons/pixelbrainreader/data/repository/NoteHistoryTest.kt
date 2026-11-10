package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.remote.FileHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** C15: a note's life in git, as Connections shows it. */
class NoteHistoryTest {

    private fun millis(date: String): Long = LocalDate.parse(date).atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val history = FileHistory(
        lastChanged = mapOf("20_Projects/plan.md" to millis("2026-11-03"), "10_Journal/2026-01-14.md" to millis("2026-01-14")),
        firstSeen = mapOf("20_Projects/plan.md" to millis("2026-01-14"), "10_Journal/2026-01-14.md" to millis("2026-01-14")),
        changes = mapOf("20_Projects/plan.md" to 27, "10_Journal/2026-01-14.md" to 1)
    )

    @Test
    fun `a note's first appearance, last change and commit count`() {
        val note = NoteHistory.of("20_Projects/plan.md", history)
        assertEquals(NoteHistory(millis("2026-01-14"), millis("2026-11-03"), 27), note)
        assertEquals("Since 14 Jan 2026 · last changed 3 Nov 2026 · 27 commits", note?.summary(ZoneOffset.UTC))
    }

    @Test
    fun `a note written once reads as one date and one commit`() {
        assertEquals("Since 14 Jan 2026 · 1 commit", NoteHistory.of("10_Journal/2026-01-14.md", history)?.summary(ZoneOffset.UTC))
    }

    @Test
    fun `a note never committed has no history yet`() {
        assertNull(NoteHistory.of("00_Inbox/new.md", history))
    }
}
