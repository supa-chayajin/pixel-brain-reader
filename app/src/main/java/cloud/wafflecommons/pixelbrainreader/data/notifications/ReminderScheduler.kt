package cloud.wafflecommons.pixelbrainreader.data.notifications

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.workers.ChoresHabitsReminderWorker
import cloud.wafflecommons.pixelbrainreader.data.workers.DailySchedule
import cloud.wafflecommons.pixelbrainreader.data.workers.DailyWorkScheduler
import cloud.wafflecommons.pixelbrainreader.data.workers.VaultReminderWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Translates the reminder preferences into daily WorkManager periodic work, pinned to the chosen
 * wall-clock time (see [DailyWorkScheduler] for why the next run is pinned explicitly).
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val userPrefs: UserPreferencesRepository
) {

    /**
     * Sync WorkManager reminders with the saved preferences.
     *
     * @param force `false` — the app-start "ensure scheduled" call — KEEPs an already-scheduled
     *   run (WorkManager starts the app process to run a due reminder, so replacing it here killed
     *   that very run), only re-pinning a run that has drifted and is safely in the future.
     *   `true` — a real preference change from Settings — applies the new time immediately.
     */
    suspend fun reschedule(force: Boolean = false) {
        NotificationHelper.ensureChannels(context)
        val wm = WorkManager.getInstance(context)

        // Vault reminder — single daily work.
        if (userPrefs.vaultReminderEnabled.first()) {
            val time = DailySchedule.parseTime(userPrefs.vaultReminderTime.first())
            if (force) {
                wm.enqueueUniquePeriodicWork(VAULT_WORK, ExistingPeriodicWorkPolicy.UPDATE, vaultRequest(time))
            } else {
                DailyWorkScheduler.ensureScheduled(wm, VAULT_WORK, time) { vaultRequest(time) }
            }
        } else {
            wm.cancelUniqueWork(VAULT_WORK)
        }

        // Chores/habits — one daily work per configured window.
        if (userPrefs.choresReminderEnabled.first()) {
            // Only on a genuine change do we clear the tagged group, so a removed window's stale
            // periodic work stops. On app start we keep what is scheduled.
            if (force) wm.cancelAllWorkByTag(CHORES_TAG)
            userPrefs.choresReminderWindows.first().forEach { hhmm ->
                val time = DailySchedule.parseTime(hhmm)
                val name = choresWorkName(hhmm)
                if (force) {
                    wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, choresRequest(hhmm, time))
                } else {
                    DailyWorkScheduler.ensureScheduled(wm, name, time) { choresRequest(hhmm, time) }
                }
            }
        } else {
            wm.cancelAllWorkByTag(CHORES_TAG)
        }
    }

    /** Called by [VaultReminderWorker] after it ran: pins its next run back to the chosen time. */
    suspend fun repinVaultReminder() {
        if (!userPrefs.vaultReminderEnabled.first()) return
        val time = DailySchedule.parseTime(userPrefs.vaultReminderTime.first())
        DailyWorkScheduler.repin(WorkManager.getInstance(context), VAULT_WORK) { vaultRequest(time) }
    }

    /** Called by [ChoresHabitsReminderWorker] after it ran; ignores windows removed meanwhile. */
    suspend fun repinChoresReminder(hhmm: String) {
        if (!userPrefs.choresReminderEnabled.first()) return
        if (hhmm !in userPrefs.choresReminderWindows.first()) return
        val time = DailySchedule.parseTime(hhmm)
        DailyWorkScheduler.repin(WorkManager.getInstance(context), choresWorkName(hhmm)) { choresRequest(hhmm, time) }
    }

    private fun vaultRequest(time: LocalTime): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<VaultReminderWorker>(24, TimeUnit.HOURS)
            .setNextScheduleTimeOverride(DailySchedule.nextOccurrenceMillis(time))
            .build()

    private fun choresRequest(hhmm: String, time: LocalTime): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<ChoresHabitsReminderWorker>(24, TimeUnit.HOURS)
            .setNextScheduleTimeOverride(DailySchedule.nextOccurrenceMillis(time))
            .setInputData(workDataOf(ChoresHabitsReminderWorker.KEY_WINDOW to hhmm))
            .addTag(CHORES_TAG)
            .build()

    private fun choresWorkName(hhmm: String) = CHORES_WORK_PREFIX + hhmm.replace(":", "")

    private companion object {
        const val VAULT_WORK = "VaultReminder"
        const val CHORES_WORK_PREFIX = "ChoresHabitsReminder_"
        const val CHORES_TAG = "chores_habits_reminder"
    }
}
