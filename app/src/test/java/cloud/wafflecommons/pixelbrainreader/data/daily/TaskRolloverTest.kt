package cloud.wafflecommons.pixelbrainreader.data.daily

import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TaskRolloverTest {

    private val today = LocalDate.of(2026, 11, 11)
    private val yesterday = today.minusDays(1)

    private fun task(label: String, done: Boolean = false, priority: Int = 1, google: String? = null, date: LocalDate = yesterday) =
        DailyTaskEntity(scheduledDate = date.toString(), label = label, isDone = done, priority = priority, googleTaskId = google)

    @Test
    fun `only the unfinished local tasks are offered, in their order`() {
        val offer = TaskRollover.offer(
            yesterday, today,
            listOf(task("Call the bank", priority = 2), task("Buy milk", done = true), task("Google one", google = "g1"), task("Book trains")),
            emptyList(), emptySet()
        )
        assertEquals(listOf("Call the bank", "Book trains"), offer?.tasks?.map { it.label })
        assertEquals(2, offer?.tasks?.first()?.priority)
        assertEquals(yesterday, offer?.from)
        assertEquals(today, offer?.to)
    }

    @Test
    fun `a task already on today's board is not offered, however it is spaced or cased`() {
        val offer = TaskRollover.offer(
            yesterday, today,
            listOf(task("Call  the Bank"), task("Book trains")),
            listOf(task(" call the bank ", date = today)), emptySet()
        )
        assertEquals(listOf("Book trains"), offer?.tasks?.map { it.label })
    }

    @Test
    fun `a handled task is not offered again, and nothing left means no offer`() {
        val handled = setOf(TaskRollover.key(yesterday, "Call the bank"))
        val offer = TaskRollover.offer(yesterday, today, listOf(task("Call the bank"), task("Book trains")), emptyList(), handled)
        assertEquals(listOf("Book trains"), offer?.tasks?.map { it.label })

        assertNull(TaskRollover.offer(yesterday, today, listOf(task("Call the bank")), emptyList(), handled))
        assertNull(TaskRollover.offer(yesterday, today, emptyList(), emptyList(), emptySet()))
    }

    @Test
    fun `the same task twice on the earlier day is offered once`() {
        val offer = TaskRollover.offer(yesterday, today, listOf(task("Book trains"), task("book  trains")), emptyList(), emptySet())
        assertEquals(1, offer?.tasks?.size)
    }

    @Test
    fun `a key names the day it comes from, so the same label another day is a new offer`() {
        assertEquals("2026-11-10|call the bank", TaskRollover.key(yesterday, "  Call the  Bank "))
        val handled = setOf(TaskRollover.key(yesterday.minusDays(1), "Call the bank"))
        val offer = TaskRollover.offer(yesterday, today, listOf(task("Call the bank")), emptyList(), handled)
        assertEquals(1, offer?.tasks?.size)
    }

    @Test
    fun `prune keeps the keys of the look-back window and drops older or broken ones`() {
        val kept = TaskRollover.key(today.minusDays(TaskRollover.LOOKBACK_DAYS), "a")
        val recent = TaskRollover.key(yesterday, "b")
        val old = TaskRollover.key(today.minusDays(TaskRollover.LOOKBACK_DAYS + 1), "c")
        assertEquals(setOf(kept, recent), TaskRollover.prune(setOf(kept, recent, old, "not a key"), today))
    }
}
