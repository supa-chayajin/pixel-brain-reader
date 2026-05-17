package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyDashboardDao
import cloud.wafflecommons.pixelbrainreader.data.repository.GoogleCalendarRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDateTime

private const val TAG = "CalendarSyncWorker"
private const val MAX_RETRY_ATTEMPTS = 5

/**
 * Drains the [cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity]
 * outbox to Google Calendar. Mirrors [TaskSyncWorker]'s contract.
 *
 * For each row where `isDirty = 1` OR `pendingDeletion = 1`:
 *  - pendingDeletion + googleEventId   → DELETE on Google, then remove locally
 *  - pendingDeletion + no googleEventId → just remove locally (never reached Google)
 *  - dirty + no googleEventId          → CREATE on Google, write the new id back
 *  - dirty + googleEventId             → (UPDATE not yet implemented) clear dirty
 *
 * UPDATE is intentionally deferred: the timeline UI currently only supports
 * create + delete via outbox. When in-place edits land, replace the no-op
 * branch with a call to GoogleCalendarRepository.updateEvent.
 *
 * Network constraint enforced at enqueue (see PixelBrainApplication). Partial
 * failures return Result.retry so WorkManager applies its backoff.
 */
@HiltWorker
class CalendarSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dashboardDao: DailyDashboardDao,
    private val googleCalendarRepository: GoogleCalendarRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dirty = dashboardDao.getDirtyTimelineSnapshot()
        if (dirty.isEmpty()) {
            Log.d(TAG, "No dirty timeline entries; nothing to drain")
            return Result.success()
        }

        Log.i(TAG, "Draining ${dirty.size} dirty timeline entry(ies)")
        var pushed = 0
        var failed = 0

        for (entry in dirty) {
            try {
                val ok = when {
                    entry.pendingDeletion && entry.googleEventId != null -> {
                        val res = googleCalendarRepository.deleteEvent(entry.googleEventId)
                        if (res.isSuccess) {
                            dashboardDao.deleteTimelineEntryById(entry.id)
                            true
                        } else false
                    }
                    entry.pendingDeletion -> {
                        // Never reached Google — just remove locally.
                        dashboardDao.deleteTimelineEntryById(entry.id)
                        true
                    }
                    entry.googleEventId == null -> {
                        // Created locally — POST to Google and link the id back.
                        val startsAt = LocalDateTime.of(entry.date, entry.time)
                        val res = googleCalendarRepository.createEvent(entry.content, startsAt)
                        res.fold(
                            onSuccess = {
                                dashboardDao.markTimelinePushedWithGoogleId(entry.id, it)
                                true
                            },
                            onFailure = { false }
                        )
                    }
                    else -> {
                        // dirty + googleEventId present → UPDATE path; not yet implemented.
                        // Clear the flag so we don't loop. When edit-event UI lands,
                        // replace this branch with GoogleCalendarRepository.updateEvent.
                        Log.w(TAG, "UPDATE path not implemented; clearing dirty on ${entry.id}")
                        dashboardDao.clearTimelineDirty(entry.id)
                        true
                    }
                }
                if (ok) pushed++ else failed++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to drain entry ${entry.id}", e)
                failed++
            }
        }

        Log.i(TAG, "Drain complete: $pushed pushed, $failed failed (attempt $runAttemptCount)")
        return if (failed > 0 && runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "CalendarSyncWorker"
    }
}
