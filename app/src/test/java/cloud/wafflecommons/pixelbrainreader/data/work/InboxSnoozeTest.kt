package cloud.wafflecommons.pixelbrainreader.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class InboxSnoozeTest {

    private val today = LocalDate.parse("2026-11-15")

    @Test
    fun `a snoozed note stays away until its day, then comes back`() {
        val entries = InboxSnooze.snooze(emptySet(), "00_Inbox/a.md", today)
        assertEquals(setOf("2026-11-22|00_Inbox/a.md"), entries)
        assertEquals(mapOf("00_Inbox/a.md" to LocalDate.parse("2026-11-22")), InboxSnooze.active(entries, today))
        assertEquals(mapOf("00_Inbox/a.md" to LocalDate.parse("2026-11-22")), InboxSnooze.active(entries, LocalDate.parse("2026-11-21")))
        assertTrue(InboxSnooze.active(entries, LocalDate.parse("2026-11-22")).isEmpty())
    }

    @Test
    fun `snoozing again moves the day, waking removes it, past entries are pruned`() {
        val old = setOf("2026-11-10|00_Inbox/old.md", "2026-11-20|00_Inbox/a.md", "garbage", "2026-11-30|")
        val again = InboxSnooze.snooze(old, "00_Inbox/a.md", today, days = 1)
        assertEquals(setOf("2026-11-16|00_Inbox/a.md"), again)
        assertTrue(InboxSnooze.wake(again, "00_Inbox/a.md", today).isEmpty())
    }

    @Test
    fun `a path with a bar in it survives`() {
        val entries = InboxSnooze.snooze(emptySet(), "00_Inbox/a|b.md", today)
        assertEquals(setOf("00_Inbox/a|b.md"), InboxSnooze.active(entries, today).keys)
    }
}
