package cloud.wafflecommons.pixelbrainreader.domain.gamification

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.health.DailyHealthMetrics
import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.model.HabitLogEntry
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.HealthMetricsRepository
import cloud.wafflecommons.pixelbrainreader.domain.lifeos.HabitScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

/**
 * Derives the Health-Connect-driven habits of a day from that day's vault health metrics.
 *
 * Idempotent and cheap to repeat — it runs on every app foreground and every sync: values are
 * ABSOLUTE daily totals, and habits whose value and status are unchanged are skipped. It used to
 * rewrite every automatic habit on each run, each with its own Room write, JSON rewrite, widget
 * rebuild and git push; the changed ones are now written as one batch.
 */
class AutomateHabitsUseCase @Inject constructor(
    private val habitRepository: HabitRepository,
    private val healthMetricsRepository: HealthMetricsRepository
) {
    suspend operator fun invoke(date: LocalDate) = withContext(Dispatchers.IO) {
        val metrics = healthMetricsRepository.getMetrics(date)
        if (metrics == null) {
            Log.d(TAG, "No health metrics found for $date")
            return@withContext
        }

        val automatic = habitRepository.getHabitConfigs()
            .filter { !it.archived && !it.autoSource.isNullOrBlank() }
        if (automatic.isEmpty()) return@withContext

        val dateKey = date.toString()
        val currentLogs = habitRepository.getLogsForYear(date.year)
        val updates = automatic.mapNotNull { habit ->
            // Only habits actually scheduled for `date` (weekly / bi-weekly / interval), via the
            // shared HabitScheduler. Last-completed is only needed for INTERVAL mode.
            val lastCompleted = if (habit.scheduleMode.equals("INTERVAL", ignoreCase = true)) {
                habitRepository.getLastCompletedDate(habit.id)
            } else {
                null
            }
            if (!HabitScheduler.isScheduledOn(habit, date, lastCompleted)) return@mapNotNull null

            val value = valueFor(habit, metrics)
            if (value <= 0.0) return@mapNotNull null
            val status = if (habitRepository.calculateCompletion(value, habit.targetValue, habit.type)) {
                HabitStatus.COMPLETED
            } else {
                HabitStatus.PARTIAL
            }
            val current = currentLogs[habit.id]?.firstOrNull { it.date == dateKey }
            if (current != null && current.value == value && current.status == status) return@mapNotNull null

            HabitLogEntry(habitId = habit.id, date = dateKey, value = value, status = status)
        }
        if (updates.isEmpty()) return@withContext

        Log.d(TAG, "Automating ${updates.size} habit(s) for $date")
        habitRepository.logHabits(date, updates)
    }

    /**
     * IMPORTANT: Health Connect metrics are ABSOLUTE daily totals. The value is SET, never added,
     * so repeated runs stay idempotent.
     */
    private fun valueFor(habit: HabitConfig, metrics: DailyHealthMetrics): Double = when (habit.autoSource) {
        "health_connect_steps" -> metrics.steps.toDouble()
        "health_connect_sleep" -> metrics.sleepDurationMinutes / 60.0
        "health_connect_hydration" -> metrics.waterConsumedMl
        "health_connect_mindfulness" -> metrics.mindfulnessMinutes.toDouble()
        "health_connect_meditation" -> metrics.mindfulnessMinutes.toDouble()
        "health_connect_active_minutes" -> metrics.activeMinutes.toDouble()
        "health_connect_exercise" -> metrics.activeMinutes.toDouble()
        "health_connect_distance" -> metrics.distanceKm
        "health_connect_weight" -> metrics.weight
        // "Prise de Masse" nutrition mapping
        "health_connect_nutrition" -> metrics.caloriesConsumed
        else -> 0.0
    }

    private companion object {
        const val TAG = "AutomateHabitsUseCase"
    }
}
