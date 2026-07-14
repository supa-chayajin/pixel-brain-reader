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

    /**
     * Sync WorkManager periodic reminders with the saved preferences.
     *
     * @param force `false` — the app-start "ensure scheduled" call — preserves any
     *   already-scheduled run via [ExistingPeriodicWorkPolicy.KEEP]. This is CRITICAL:
     *   WorkManager starts the app process to run a due reminder, so a cancel-then-reschedule
     *   here (the old behavior) killed that very run at the moment it came due — the reminder
     *   never fired. `true` — a real preference change from settings — applies the new time
     *   via UPDATE (and clears stale chore windows).
     */
    suspend fun reschedule(force: Boolean = false) {
        NotificationHelper.ensureChannels(context)
        val wm = WorkManager.getInstance(context)
        val policy = if (force) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP

        // Vault reminder — single daily work.
        if (userPrefs.vaultReminderEnabled.first()) {
            wm.enqueueUniquePeriodicWork(
                VAULT_WORK,
                policy,
                PeriodicWorkRequestBuilder<VaultReminderWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(initialDelayMillis(userPrefs.vaultReminderTime.first()), TimeUnit.MILLISECONDS)
                    .build()
            )
        } else {
            wm.cancelUniqueWork(VAULT_WORK)
        }

        // Chores/habits — one daily work per configured window.
        if (userPrefs.choresReminderEnabled.first()) {
            // Only on a genuine change do we clear the tagged group, so a removed
            // window's stale periodic work stops. On app start we KEEP (see above).
            if (force) wm.cancelAllWorkByTag(CHORES_TAG)
            userPrefs.choresReminderWindows.first().forEach { hhmm ->
                wm.enqueueUniquePeriodicWork(
                    CHORES_WORK_PREFIX + hhmm.replace(":", ""),
                    policy,
                    PeriodicWorkRequestBuilder<ChoresHabitsReminderWorker>(24, TimeUnit.HOURS)
                        .setInitialDelay(initialDelayMillis(hhmm), TimeUnit.MILLISECONDS)
                        .addTag(CHORES_TAG)
                        .build()
                )
            }
        } else {
            wm.cancelAllWorkByTag(CHORES_TAG)
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
