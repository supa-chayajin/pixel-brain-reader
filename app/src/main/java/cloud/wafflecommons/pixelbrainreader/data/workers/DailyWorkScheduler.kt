package cloud.wafflecommons.pixelbrainreader.data.workers

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * Pure time math for "run once a day at HH:mm" jobs.
 */
object DailySchedule {

    /** The next occurrence of [time] strictly after [now]: today if still ahead, otherwise tomorrow. */
    fun nextOccurrence(now: LocalDateTime, time: LocalTime): LocalDateTime {
        val candidate = now.toLocalDate().atTime(time)
        return if (candidate.isAfter(now)) candidate else candidate.plusDays(1)
    }

    fun nextOccurrenceMillis(
        time: LocalTime,
        now: LocalDateTime = LocalDateTime.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Long = nextOccurrence(now, time).atZone(zone).toInstant().toEpochMilli()

    /** Parses "HH:mm", falling back to [fallback] for malformed input. */
    fun parseTime(hhmm: String, fallback: LocalTime = LocalTime.of(20, 0)): LocalTime = try {
        val (h, m) = hhmm.split(":")
        LocalTime.of(h.trim().toInt(), m.trim().toInt())
    } catch (e: Exception) {
        fallback
    }
}

/**
 * Keeps daily WorkManager jobs pinned to their wall-clock time.
 *
 * WHY: WorkManager periodic work drifts. After each run the next one is scheduled 24 h after the
 * run FINISHED, and an UPDATE keeps that anchor (`lastEnqueueTime`), silently ignoring a new
 * initial delay. So a 20:00 reminder crept later every day, the 23:50 burn could slip past
 * midnight, and changing a reminder time in Settings never took effect. The fix is to pin the next
 * run explicitly with `setNextScheduleTimeOverride` — at (re)schedule time and again after every
 * run (an override set while the worker runs survives its completion).
 */
object DailyWorkScheduler {

    private const val SAFE_MARGIN_MS = 30 * 60_000L
    private const val DRIFT_TOLERANCE_MS = 5 * 60_000L

    /**
     * App-start path: enqueue when absent; otherwise KEEP the existing run — unless it has drifted
     * away from [time] AND its next run is comfortably in the future, in which case it is re-pinned.
     * A run that is due (or nearly due) is never touched: WorkManager may have started this very
     * process to execute it, and replacing it at startup is what once made reminders never fire.
     */
    suspend fun ensureScheduled(
        workManager: WorkManager,
        uniqueName: String,
        time: LocalTime,
        request: () -> PeriodicWorkRequest
    ) {
        val current = workManager.getWorkInfosForUniqueWorkFlow(uniqueName).first()
            .firstOrNull { !it.state.isFinished }
        if (current == null) {
            workManager.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.KEEP, request())
            return
        }
        val target = DailySchedule.nextOccurrenceMillis(time)
        if (shouldRepin(current.state, current.nextScheduleTimeMillis, target, System.currentTimeMillis())) {
            workManager.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.UPDATE, request())
        }
    }

    /** Only re-pin an ENQUEUED run that is safely in the future and has drifted from its target. */
    fun shouldRepin(state: WorkInfo.State, nextRunMillis: Long, targetMillis: Long, nowMillis: Long): Boolean =
        state == WorkInfo.State.ENQUEUED &&
            nextRunMillis - nowMillis > SAFE_MARGIN_MS &&
            abs(nextRunMillis - targetMillis) > DRIFT_TOLERANCE_MS

    /** Re-pins the next run of a (possibly currently running) daily job; used right after a run. */
    fun repin(workManager: WorkManager, uniqueName: String, request: () -> PeriodicWorkRequest) {
        workManager.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.UPDATE, request())
    }
}
