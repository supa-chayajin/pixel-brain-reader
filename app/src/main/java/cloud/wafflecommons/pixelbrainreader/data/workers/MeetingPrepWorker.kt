package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyDashboardDao
import cloud.wafflecommons.pixelbrainreader.data.meetings.MeetingPrepRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.notifications.NotificationHelper
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Ten minutes before a calendar meeting (D4): "Standup in 10 min · 3 notes, 1 decision", opening its
 * prep. Only when switched on, only in Work mode (`muteWorkNotifications`); Do Not Disturb is the
 * quiet hours (owner, 2026-11-11). A meeting gone from the calendar since posts nothing.
 */
@HiltWorker
class MeetingPrepWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val dashboardDao: DailyDashboardDao,
    private val prepRepository: MeetingPrepRepository,
    private val modesRepository: ModesRepository,
    private val userPrefs: UserPreferencesRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (VaultWriteGate.readOnly) return Result.success() // no notification on a read-only device
        try {
            if (!userPrefs.meetingPrepReminders.first()) return Result.success()
            if (modesRepository.currentVisibility().muteWorkNotifications) return Result.success()
            val eventId = inputData.getString(KEY_EVENT) ?: return Result.success()
            // The import replaces today's rows at each sync: the event id is what lasts.
            val entry = dashboardDao.getTimelineEntryByEvent(eventId) ?: return Result.success()
            val prep = prepRepository.prep(entry.id) ?: return Result.success()
            NotificationHelper.postMeetingPrep(
                applicationContext,
                entryId = entry.id,
                topic = prep.topic.ifEmpty { entry.content },
                notes = prep.notes.size,
                decisions = prep.decisions.size
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Meeting prep notification failed (non-fatal)", e)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "MeetingPrepWorker"
        const val KEY_EVENT = "eventId"
    }
}
