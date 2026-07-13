package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.local.dao.HabitDao
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
import cloud.wafflecommons.pixelbrainreader.data.notifications.NotificationHelper
import cloud.wafflecommons.pixelbrainreader.data.repository.ChoreRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.domain.homeos.CalculateChoreEntropyUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
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
    private val userPrefs: UserPreferencesRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            if (!userPrefs.choresReminderEnabled.first()) return Result.success()

            // Due chores: reuse the same entropy logic the Home OS dashboard shows.
            val chores = choreRepository.getAllChoresStream().first()
            val dueChores = calculateChoreEntropy(chores)
                .filter { it.dirtinessPercentage >= 100f }
                .map { it.entity.name }

            // Habits scheduled today (frequency empty = daily) minus those already
            // COMPLETED today.
            val today = LocalDate.now()
            val todayStr = today.toString() // ISO yyyy-MM-dd
            val todayKey = dayKeyOf(today.dayOfWeek)
            val activeToday = habitDao.getAllConfigs()
                .filter { !it.archived && (it.frequency.isEmpty() || it.frequency.contains(todayKey)) }
            val completedIds = habitDao.getLogsForYear(todayStr.substring(0, 4))
                .filter { it.date == todayStr && it.status == HabitStatus.COMPLETED }
                .map { it.habitId }
                .toSet()
            val unfinishedHabits = activeToday.filter { it.id !in completedIds }.map { it.title }

            if (dueChores.isEmpty() && unfinishedHabits.isEmpty()) {
                Log.d("ChoresHabitsReminder", "Nothing due — no reminder.")
                return Result.success()
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
            Result.success()
        } catch (e: Exception) {
            Log.w("ChoresHabitsReminder", "Chores/habits reminder failed (non-fatal)", e)
            Result.success()
        }
    }

    private fun dayKeyOf(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "MON"
        DayOfWeek.TUESDAY -> "TUE"
        DayOfWeek.WEDNESDAY -> "WED"
        DayOfWeek.THURSDAY -> "THU"
        DayOfWeek.FRIDAY -> "FRI"
        DayOfWeek.SATURDAY -> "SAT"
        DayOfWeek.SUNDAY -> "SUN"
    }
}
