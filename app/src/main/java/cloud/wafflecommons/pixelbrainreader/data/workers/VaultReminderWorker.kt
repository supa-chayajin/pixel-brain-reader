package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.notifications.NotificationHelper
import cloud.wafflecommons.pixelbrainreader.data.repository.PrivateNoteRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reminds the user to write in the private vault, unless they already wrote today.
 * Scheduled daily at the user-chosen time by [ReminderScheduler]. Non-fatal: any
 * failure just skips this occurrence (the daily periodic work fires again tomorrow).
 */
@HiltWorker
class VaultReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val privateNoteRepository: PrivateNoteRepository,
    private val userPrefs: UserPreferencesRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            if (!userPrefs.vaultReminderEnabled.first()) return Result.success()

            val latestMs = privateNoteRepository.getPrivateNotes().firstOrNull()?.lastModified() ?: 0L
            val wroteToday = latestMs > 0L &&
                Instant.ofEpochMilli(latestMs).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()

            if (wroteToday) {
                Log.d("VaultReminderWorker", "Already wrote to the private vault today — skipping reminder.")
            } else {
                NotificationHelper.postVaultReminder(applicationContext)
            }
            Result.success()
        } catch (e: Exception) {
            Log.w("VaultReminderWorker", "Vault reminder failed (non-fatal)", e)
            Result.success()
        }
    }
}
