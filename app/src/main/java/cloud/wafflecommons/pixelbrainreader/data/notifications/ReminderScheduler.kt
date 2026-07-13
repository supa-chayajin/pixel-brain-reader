package cloud.wafflecommons.pixelbrainreader.data.notifications

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.workers.ChoresHabitsReminderWorker
import cloud.wafflecommons.pixelbrainreader.data.workers.VaultReminderWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Translates the reminder preferences into WorkManager periodic work. Idempotent —
 * safe to call on app start and after every settings change. Uses the same
 * fixed-time-of-day pattern as PixelBrainApplication.scheduleDailyBurnWork.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPrefs: UserPreferencesRepository
) {

    suspend fun reschedule() {
        NotificationHelper.ensureChannels(context)
        val wm = WorkManager.getInstance(context)

        // Vault reminder — single daily work. Cancel first so a disabled toggle stops it.
        wm.cancelUniqueWork(VAULT_WORK)
        if (userPrefs.vaultReminderEnabled.first()) {
            wm.enqueueUniquePeriodicWork(
                VAULT_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<VaultReminderWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(initialDelayMillis(userPrefs.vaultReminderTime.first()), TimeUnit.MILLISECONDS)
                    .build()
            )
        }

        // Chores/habits — one daily work per configured window. Cancel the whole
        // tagged group first so a removed window's stale periodic work stops firing.
        wm.cancelAllWorkByTag(CHORES_TAG)
        if (userPrefs.choresReminderEnabled.first()) {
            userPrefs.choresReminderWindows.first().forEach { hhmm ->
                wm.enqueueUniquePeriodicWork(
                    CHORES_WORK_PREFIX + hhmm.replace(":", ""),
                    ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<ChoresHabitsReminderWorker>(24, TimeUnit.HOURS)
                        .setInitialDelay(initialDelayMillis(hhmm), TimeUnit.MILLISECONDS)
                        .addTag(CHORES_TAG)
                        .build()
                )
            }
        }
    }

    /** Millis from now to the next occurrence of HH:mm (today, or tomorrow if passed). */
    private fun initialDelayMillis(hhmm: String): Long {
        val now = LocalDateTime.now()
        var target = LocalDateTime.of(now.toLocalDate(), parseTime(hhmm))
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now, target).toMillis()
    }

    private fun parseTime(hhmm: String): LocalTime = try {
        val (h, m) = hhmm.split(":")
        LocalTime.of(h.trim().toInt(), m.trim().toInt())
    } catch (e: Exception) {
        LocalTime.of(20, 0)
    }

    private companion object {
        const val VAULT_WORK = "VaultReminder"
        const val CHORES_WORK_PREFIX = "ChoresHabitsReminder_"
        const val CHORES_TAG = "chores_habits_reminder"
    }
}
