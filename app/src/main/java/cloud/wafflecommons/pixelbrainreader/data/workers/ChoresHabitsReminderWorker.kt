package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.local.dao.HabitDao
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
import cloud.wafflecommons.pixelbrainreader.data.notifications.NotificationHelper
import cloud.wafflecommons.pixelbrainreader.data.notifications.ReminderScheduler
import cloud.wafflecommons.pixelbrainreader.data.repository.ChoreRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.domain.homeos.CalculateChoreEntropyUseCase
import cloud.wafflecommons.pixelbrainreader.domain.lifeos.HabitScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Reminds the user about chores that are due (dirtiness ≥ 100%) and habits that are
 * scheduled today but not yet completed. Fires once per configured global window.
 * Stays silent when nothing is due, so it never nags. Non-fatal.
 */
@HiltWorker
class ChoresHabitsReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val choreRepository: ChoreRepository,
    private val calculateChoreEntropy: CalculateChoreEntropyUseCase,
    private val habitDao: HabitDao,
    private val userPrefs: UserPreferencesRepository,
    private val reminderScheduler: ReminderScheduler
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        try {
            if (userPrefs.choresReminderEnabled.first()) postReminderIfDue()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ChoresHabitsReminder", "Chores/habits reminder failed (non-fatal)", e)
        }
        // Works enqueued by older builds carry no window; the app-start drift check re-pins those.
        inputData.getString(KEY_WINDOW)?.let { window ->
            try {
                reminderScheduler.repinChoresReminder(window)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ChoresHabitsReminder", "Could not re-pin the $window reminder", e)
            }
        }
        return Result.success()
    }

    private suspend fun postReminderIfDue() {
        // Due chores: reuse the same entropy logic the Home OS dashboard shows (excluding archived chores).
        val chores = choreRepository.getAllChoresStream().first().filter { !it.archived }
        val dueChores = calculateChoreEntropy(chores)
            .filter { it.dirtinessPercentage >= 100f }
            .map { it.entity.name }

        // Habits scheduled today (via the shared HabitScheduler — supports weekly,
        // bi-weekly and interval modes) minus those already COMPLETED today.
        val today = LocalDate.now()
        val todayStr = today.toString() // ISO yyyy-MM-dd
        // Include last year so an INTERVAL habit last completed in December isn't wrongly "due".
        val recentLogs = habitDao.getLogsForYear(today.year.toString()) +
            habitDao.getLogsForYear((today.year - 1).toString())
        val lastCompletedByHabit = recentLogs
            .filter { it.status == HabitStatus.COMPLETED }
            .groupBy { it.habitId }
            .mapValues { (_, logs) -> logs.maxOf { it.date } }
        val activeToday = habitDao.getAllConfigs().filter { cfg ->
            !cfg.archived && HabitScheduler.isScheduledOn(
                cfg.scheduleMode, cfg.frequency, cfg.intervalCount, cfg.intervalUnit,
                today,
                lastCompletedByHabit[cfg.id]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            )
        }
        val completedIds = recentLogs
            .filter { it.date == todayStr && it.status == HabitStatus.COMPLETED }
            .map { it.habitId }
            .toSet()
        val unfinishedHabits = activeToday.filter { it.id !in completedIds }.map { it.title }

        if (dueChores.isEmpty() && unfinishedHabits.isEmpty()) {
            Log.d("ChoresHabitsReminder", "Nothing due — no reminder.")
            return
        }

        val titleParts = buildList {
            if (dueChores.isNotEmpty()) add("🧹 ${dueChores.size} chore(s) due")
            if (unfinishedHabits.isNotEmpty()) add("☑️ ${unfinishedHabits.size} habit(s) left")
        }
        val lines = (dueChores.map { "🧹 $it" } + unfinishedHabits.map { "☑️ $it" }).take(8)

        NotificationHelper.postChoresHabitsReminder(
            applicationContext,
            title = titleParts.joinToString(" · "),
            lines = lines
        )
    }

    companion object {
        /** "HH:mm" window this work was scheduled for; used to re-pin the next run. */
        const val KEY_WINDOW = "window"
    }
}
