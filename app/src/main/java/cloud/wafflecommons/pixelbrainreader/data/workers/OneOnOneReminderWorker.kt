package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyDashboardDao
import cloud.wafflecommons.pixelbrainreader.data.meetings.MeetingPrep
import cloud.wafflecommons.pixelbrainreader.data.meetings.OneOnOneReminders
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.notifications.NotificationHelper
import cloud.wafflecommons.pixelbrainreader.data.people.PersonLinks
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Thirty minutes before a 1:1 (12.19, the owner's D6): "<meeting> in 30 min", opening its prep. The
 * meeting must still be on the calendar and still linked to a person or titled like a 1:1 (a link
 * removed since posts nothing); a work person's meeting and a 1:1 title post in Work mode only. The
 * notification carries the calendar's title, never anything from a People note.
 */
@HiltWorker
class OneOnOneReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val dashboardDao: DailyDashboardDao,
    private val modesRepository: ModesRepository,
    private val userPrefs: UserPreferencesRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (VaultWriteGate.readOnly) return Result.success() // no notification on a read-only device
        try {
            if (!userPrefs.oneOnOneReminders.first()) return Result.success()
            val eventId = inputData.getString(KEY_EVENT) ?: return Result.success()
            // The import replaces today's rows at each sync: the event id is what lasts.
            val entry = dashboardDao.getTimelineEntryByEvent(eventId) ?: return Result.success()
            val reason = OneOnOneReminders.reasonFor(entry, PersonLinks.decode(userPrefs.personLinks.first())) ?: return Result.success()
            if (!OneOnOneReminders.posts(reason, modesRepository.currentVisibility().muteWorkNotifications)) return Result.success()
            NotificationHelper.postOneOnOne(
                applicationContext,
                entryId = entry.id,
                topic = MeetingPrep.topic(entry.content).ifEmpty { entry.content },
                linked = reason != OneOnOneReminders.Reason.TITLE
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "1:1 reminder failed (non-fatal): ${e.javaClass.simpleName}")
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "OneOnOneReminder"
        const val KEY_EVENT = "eventId"
    }
}
