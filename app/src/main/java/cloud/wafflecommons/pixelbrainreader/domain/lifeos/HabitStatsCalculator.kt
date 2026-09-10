package cloud.wafflecommons.pixelbrainreader.domain.lifeos

import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.model.HabitLogEntry
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
import cloud.wafflecommons.pixelbrainreader.data.model.HabitType
import java.time.LocalDate

/** Per-day statistics of one habit, as shown on the Habits and Stats screens. */
data class HabitDayStats(
    val isCompletedToday: Boolean,
    val currentValue: Double,
    val currentStreak: Int,
    /** Completion of the last 7 days, oldest first (index 6 = the reference day). */
    val history: List<Boolean>,
    val isScheduledToday: Boolean
)

/**
 * Pure habit statistics shared by the Habits dashboard and Life Stats.
 *
 * Logs are indexed by date once per habit: the previous code ran a linear `logs.find { … }` for
 * every one of up to 366 streak days, for every habit, on every emission — on the main thread —
 * which made the Habits and Stats screens janky as the history grew.
 */
object HabitStatsCalculator {

    private const val MAX_STREAK_LOOKBACK_DAYS = 365

    fun isComplete(habit: HabitConfig, log: HabitLogEntry?): Boolean {
        if (log == null) return false
        return if (habit.type == HabitType.MEASURABLE) {
            log.value >= habit.targetValue
        } else {
            log.status == HabitStatus.COMPLETED
        }
    }

    fun compute(habit: HabitConfig, logs: List<HabitLogEntry>, date: LocalDate): HabitDayStats {
        // First log per date wins (matches the former `find`, which returned the first match).
        val byDate = HashMap<String, HabitLogEntry>(logs.size * 2)
        logs.forEach { byDate.putIfAbsent(it.date, it) }

        val todayLog = byDate[date.toString()]
        val completedToday = isComplete(habit, todayLog)

        // Most recent completion date — only INTERVAL scheduling consults it.
        val lastCompleted = logs
            .filter { isComplete(habit, it) }
            .maxByOrNull { it.date }
            ?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        val history = (6 downTo 0).map { offset ->
            isComplete(habit, byDate[date.minusDays(offset.toLong()).toString()])
        }

        return HabitDayStats(
            isCompletedToday = completedToday,
            currentValue = todayLog?.value ?: 0.0,
            currentStreak = currentStreak(habit, logs, byDate, date, completedToday),
            history = history,
            isScheduledToday = HabitScheduler.isScheduledOn(habit, date, lastCompleted)
        )
    }

    /**
     * Interval habits complete on irregular days, so their streak is the number of completions in
     * the calendar year of [date]; weekly / bi-weekly habits walk back day by day, skipping
     * unscheduled days — across years when the logs include the previous one.
     */
    private fun currentStreak(
        habit: HabitConfig,
        logs: List<HabitLogEntry>,
        byDate: Map<String, HabitLogEntry>,
        date: LocalDate,
        completedToday: Boolean
    ): Int {
        if (habit.scheduleMode.equals("INTERVAL", ignoreCase = true)) {
            val yearPrefix = "${date.year}-"
            return logs.count { it.date.startsWith(yearPrefix) && isComplete(habit, it) }
        }
        var streak = 0
        var checkDate = if (completedToday) date else date.minusDays(1)
        repeat(MAX_STREAK_LOOKBACK_DAYS + 1) {
            when {
                isComplete(habit, byDate[checkDate.toString()]) -> {
                    streak++
                    checkDate = checkDate.minusDays(1)
                }
                !HabitScheduler.isScheduledOn(habit, checkDate, null) -> checkDate = checkDate.minusDays(1)
                else -> return streak
            }
        }
        return streak
    }
}
