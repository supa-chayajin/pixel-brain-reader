package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.notifications.NotificationHelper
import cloud.wafflecommons.pixelbrainreader.data.notifications.ReminderScheduler
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Reminds the user to write in the private vault at the user-chosen time (scheduled
 * daily by [ReminderScheduler]). The reminder is ALWAYS posted when enabled — it is
 * never suppressed, even if the user already wrote today (explicit product decision).
 * Non-fatal: any failure just skips this occurrence (it fires again tomorrow).
 */
@HiltWorker
class VaultReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val userPrefs: UserPreferencesRepository,
    private val reminderScheduler: ReminderScheduler
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        try {
            if (userPrefs.vaultReminderEnabled.first()) {
                NotificationHelper.postVaultReminder(applicationContext)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("VaultReminderWorker", "Vault reminder failed (non-fatal)", e)
        }
        try {
            // Keep tomorrow's run on the chosen time instead of 24 h after this run finished.
            reminderScheduler.repinVaultReminder()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("VaultReminderWorker", "Could not re-pin the next vault reminder", e)
        }
        return Result.success()
    }
}
