package cloud.wafflecommons.pixelbrainreader.domain.lifeos

import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Single source of truth for "is this habit due on a given date?", shared by the dashboard,
 * the stats screen, the reminder worker and the health-automation use case so the four can
 * never drift (previously the day-of-week logic was copy-pasted in all of them).
 *
 * Modes:
 *  - WEEKLY:   [HabitConfig.frequency] = weekday keys (MON..SUN). Empty = every day.
 *  - BIWEEKLY: [HabitConfig.frequency] = 2-week slots, e.g. "W1-MON", "W2-FRI". Empty = every day.
 *  - INTERVAL: due once [HabitConfig.intervalCount] [HabitConfig.intervalUnit]s have elapsed
 *              since the last completion (like chores), regardless of weekday.
 */
object HabitScheduler {

    /** A fixed Monday used as the biweekly cycle reference so all biweekly habits share phase. */
    private val EPOCH_MONDAY: LocalDate = LocalDate.of(2024, 1, 1)

    val WEEK_DAYS = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

    fun dayKey(dow: DayOfWeek): String = WEEK_DAYS[dow.value - 1]

    /** Which half of the rolling 2-week cycle [date] falls in: 1 or 2. */
    fun biweeklyWeekOf(date: LocalDate): Int {
        val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
        val weeks = ChronoUnit.WEEKS.between(EPOCH_MONDAY, monday)
        return if (weeks.mod(2L) == 0L) 1 else 2
    }

    /**
     * @param lastCompleted the most recent date this habit was completed (COMPLETED status),
     *   or null if never — only consulted for INTERVAL mode.
     */
    fun isScheduledOn(habit: HabitConfig, date: LocalDate, lastCompleted: LocalDate?): Boolean =
        isScheduledOn(habit.scheduleMode, habit.frequency, habit.intervalCount, habit.intervalUnit, date, lastCompleted)

    /** Primitive overload so raw entities (reminder worker) and domain models share one impl. */
    fun isScheduledOn(
        scheduleMode: String,
        frequency: List<String>,
        intervalCount: Int,
        intervalUnit: String,
        date: LocalDate,
        lastCompleted: LocalDate?
    ): Boolean {
        return when (scheduleMode.uppercase()) {
            "INTERVAL" -> {
                if (intervalCount <= 0) return true
                val anchor = lastCompleted ?: return true // never completed → due now
                val elapsed = when (intervalUnit.uppercase()) {
                    "WEEK" -> ChronoUnit.WEEKS.between(anchor, date)
                    "MONTH" -> ChronoUnit.MONTHS.between(anchor, date)
                    else -> ChronoUnit.DAYS.between(anchor, date)
                }
                elapsed >= intervalCount
            }
            "BIWEEKLY" -> {
                val freq = frequency.map { it.trim().uppercase() }
                if (freq.isEmpty()) return true
                freq.contains("W${biweeklyWeekOf(date)}-${dayKey(date.dayOfWeek)}")
            }
            else -> { // WEEKLY (default)
                val freq = frequency.map { it.trim().uppercase() }
                freq.isEmpty() || freq.contains(dayKey(date.dayOfWeek))
            }
        }
    }

    /** Human-readable (French) summary of a habit's schedule, for cards/editor previews. */
    fun describe(habit: HabitConfig): String = when (habit.scheduleMode.uppercase()) {
        "INTERVAL" -> {
            val unit = when (habit.intervalUnit.uppercase()) {
                "WEEK" -> if (habit.intervalCount > 1) "semaines" else "semaine"
                "MONTH" -> if (habit.intervalCount > 1) "mois" else "mois"
                else -> if (habit.intervalCount > 1) "jours" else "jour"
            }
            "Tous les ${habit.intervalCount} $unit"
        }
        "BIWEEKLY" -> "Sur 2 semaines (${habit.frequency.size} jours)"
        else -> if (habit.frequency.isEmpty()) "Tous les jours" else habit.frequency.joinToString(", ")
    }
}
