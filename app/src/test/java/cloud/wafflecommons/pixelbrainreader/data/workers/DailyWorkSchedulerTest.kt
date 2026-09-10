package cloud.wafflecommons.pixelbrainreader.data.workers

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

/** Pins the wall-clock scheduling math behind reminders and the nightly burn. */
class DailyWorkSchedulerTest {

    private val eightPm = LocalTime.of(20, 0)

    @Test
    fun `next occurrence is later today while the time is still ahead`() {
        val now = LocalDateTime.of(2026, 9, 10, 8, 0)
        assertEquals(LocalDateTime.of(2026, 9, 10, 20, 0), DailySchedule.nextOccurrence(now, eightPm))
    }

    @Test
    fun `next occurrence rolls over to tomorrow at or after the time`() {
        assertEquals(
            LocalDateTime.of(2026, 9, 11, 20, 0),
            DailySchedule.nextOccurrence(LocalDateTime.of(2026, 9, 10, 20, 0), eightPm)
        )
        assertEquals(
            LocalDateTime.of(2026, 9, 11, 20, 0),
            DailySchedule.nextOccurrence(LocalDateTime.of(2026, 9, 10, 23, 55), eightPm)
        )
    }

    @Test
    fun `next occurrence handles month and year boundaries`() {
        assertEquals(
            LocalDateTime.of(2027, 1, 1, 23, 50),
            DailySchedule.nextOccurrence(LocalDateTime.of(2026, 12, 31, 23, 55), LocalTime.of(23, 50))
        )
    }

    @Test
    fun `parseTime reads HH-mm and falls back on malformed input`() {
        assertEquals(LocalTime.of(9, 5), DailySchedule.parseTime("09:05"))
        assertEquals(LocalTime.of(7, 30), DailySchedule.parseTime(" 7 : 30 "))
        assertEquals(LocalTime.of(20, 0), DailySchedule.parseTime("garbage"))
        assertEquals(LocalTime.of(20, 0), DailySchedule.parseTime("25:99"))
    }

    // --- shouldRepin -------------------------------------------------------------------

    private val now = 1_000_000_000_000L
    private val hour = 3_600_000L

    @Test
    fun `a drifted run safely in the future is re-pinned`() {
        val target = now + 4 * hour
        val drifted = target + 40 * 60_000L
        assertTrue(DailyWorkScheduler.shouldRepin(WorkInfo.State.ENQUEUED, drifted, target, now))
    }

    @Test
    fun `a run on time is left alone`() {
        val target = now + 4 * hour
        assertFalse(DailyWorkScheduler.shouldRepin(WorkInfo.State.ENQUEUED, target + 60_000L, target, now))
    }

    @Test
    fun `a run that is due or nearly due is never touched`() {
        val target = now + 24 * hour
        // Next run 10 minutes from now (drifted, but WorkManager may be about to start it).
        assertFalse(DailyWorkScheduler.shouldRepin(WorkInfo.State.ENQUEUED, now + 10 * 60_000L, target, now))
        // Next run already in the past (overdue): this process may have been started to run it.
        assertFalse(DailyWorkScheduler.shouldRepin(WorkInfo.State.ENQUEUED, now - hour, target, now))
    }

    @Test
    fun `a running job is never re-pinned from the scheduler`() {
        val target = now + 4 * hour
        assertFalse(DailyWorkScheduler.shouldRepin(WorkInfo.State.RUNNING, target + 2 * hour, target, now))
    }
}
