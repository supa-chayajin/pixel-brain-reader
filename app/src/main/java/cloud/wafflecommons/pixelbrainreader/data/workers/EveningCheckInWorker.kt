package cloud.wafflecommons.pixelbrainreader.data.workers

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.local.preferences.GamificationPreferences
import cloud.wafflecommons.pixelbrainreader.data.notifications.NotificationHelper
import cloud.wafflecommons.pixelbrainreader.data.notifications.ReminderScheduler
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Posts the evening check-in at the chosen time (scheduled daily by [ReminderScheduler]), then
 * re-pins tomorrow's run to that time. Non-fatal: a failure skips tonight's check-in only.
 */
@HiltWorker
class EveningCheckInWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val userPrefs: UserPreferencesRepository,
    private val reminderScheduler: ReminderScheduler,
    private val gamificationPreferences: GamificationPreferences
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (VaultWriteGate.readOnly) return Result.success() // a read-only device runs no writer
        try {
            if (userPrefs.eveningCheckInEnabled.first()) {
                NotificationHelper.postCheckInMood(applicationContext, gamificationPreferences.moodEmojiSetFlow.first())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("EveningCheckInWorker", "Evening check-in failed (non-fatal)", e)
        }
        try {
            reminderScheduler.repinEveningCheckIn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("EveningCheckInWorker", "Could not re-pin the next check-in", e)
        }
        return Result.success()
    }
}
