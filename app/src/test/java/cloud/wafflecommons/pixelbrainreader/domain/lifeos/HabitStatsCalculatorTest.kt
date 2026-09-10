package cloud.wafflecommons.pixelbrainreader.domain.lifeos

import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.model.HabitLogEntry
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
import cloud.wafflecommons.pixelbrainreader.data.model.HabitType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/**
 * The indexed [HabitStatsCalculator] replaced an O(days × logs) implementation that ran on the main
 * thread. These tests pin that it produces EXACTLY the same numbers as the legacy algorithm, plus
 * the one intended change: streaks now continue across January 1st.
 */
class HabitStatsCalculatorTest {

    private val today = LocalDate.of(2026, 9, 10)

    private val habits = listOf(
        HabitConfig(id = "daily", title = "Daily"),
        HabitConfig(id = "mwf", title = "MWF", frequency = listOf("MON", "WED", "FRI")),
        HabitConfig(id = "biweekly", title = "Bi", scheduleMode = "BIWEEKLY", frequency = listOf("W1-MON", "W2-FRI")),
        HabitConfig(id = "interval", title = "Every 3d", scheduleMode = "INTERVAL", intervalCount = 3, intervalUnit = "DAY"),
        HabitConfig(id = "water", title = "Water", type = HabitType.MEASURABLE, targetValue = 8.0)
    )

    @Test
    fun `indexed stats match the legacy algorithm for every schedule mode`() {
        val random = Random(42)
        repeat(40) {
            habits.forEach { habit ->
                val logs = randomLogs(habit.id, random)
                val date = today.minusDays(random.nextLong(0, 30))
                // The legacy screens only ever loaded the current year's logs.
                val currentYearLogs = logs.filter { it.date.startsWith("${date.year}-") }
                assertEquals(
                    "habit=${habit.id} date=$date",
                    legacyStats(habit, currentYearLogs, date),
                    HabitStatsCalculator.compute(habit, logs, date)
                )
            }
        }
    }

    @Test
    fun `streak counts consecutive completions and skips unscheduled days`() {
        val mwf = habits.first { it.id == "mwf" }
        // 2026-09-10 is a Thursday. Completed Mon 7, Wed 9 → Thu is unscheduled, so the streak
        // walks back over it: Wed ✓, Tue skip, Mon ✓, Sun/Sat skip, Fri 4 missing → stop.
        val logs = listOf(done("mwf", "2026-09-07"), done("mwf", "2026-09-09"))

        val stats = HabitStatsCalculator.compute(mwf, logs, today)

        assertEquals(2, stats.currentStreak)
        assertEquals(false, stats.isScheduledToday)
        assertEquals(listOf(false, false, false, true, false, true, false), stats.history)
    }

    @Test
    fun `streaks continue across January 1st`() {
        val daily = habits.first { it.id == "daily" }
        val jan2 = LocalDate.of(2027, 1, 2)
        val logs = (0L..9L).map { done("daily", jan2.minusDays(it).toString()) } // Dec 24 → Jan 2

        val stats = HabitStatsCalculator.compute(daily, logs, jan2)

        assertEquals(10, stats.currentStreak)
    }

    @Test
    fun `interval habits count the completions of the current year only`() {
        val interval = habits.first { it.id == "interval" }
        val jan2 = LocalDate.of(2027, 1, 2)
        val logs = listOf(done("interval", "2026-12-28"), done("interval", "2026-12-31"), done("interval", "2027-01-02"))

        assertEquals(1, HabitStatsCalculator.compute(interval, logs, jan2).currentStreak)
    }

    @Test
    fun `measurable habits complete on reaching the target value`() {
        val water = habits.first { it.id == "water" }
        val logs = listOf(
            HabitLogEntry("water", today.toString(), 8.0, HabitStatus.PARTIAL),
            HabitLogEntry("water", today.minusDays(1).toString(), 7.0, HabitStatus.PARTIAL)
        )

        val stats = HabitStatsCalculator.compute(water, logs, today)

        assertEquals(true, stats.isCompletedToday)
        assertEquals(8.0, stats.currentValue, 0.0)
        assertEquals(1, stats.currentStreak)
    }

    // --- fixtures ---------------------------------------------------------------------

    private fun done(id: String, date: String) = HabitLogEntry(id, date, 1.0, HabitStatus.COMPLETED, timestamp = 0)

    private fun randomLogs(habitId: String, random: Random): List<HabitLogEntry> {
        val statuses = HabitStatus.entries
        return (0..420).mapNotNull { back ->
            if (random.nextDouble() < 0.35) return@mapNotNull null
            HabitLogEntry(
                habitId = habitId,
                date = today.minusDays(back.toLong()).toString(),
                value = random.nextInt(0, 11).toDouble(),
                status = statuses[random.nextInt(statuses.size)],
                timestamp = 0
            )
        }.shuffled(random)
    }

    /** Verbatim port of the pre-optimization LifeOSViewModel computation. */
    private fun legacyStats(habit: HabitConfig, habitLogs: List<HabitLogEntry>, date: LocalDate): HabitDayStats {
        fun isHabitComplete(log: HabitLogEntry?): Boolean {
            if (log == null) return false
            return if (habit.type == HabitType.MEASURABLE) log.value >= habit.targetValue
            else log.status == HabitStatus.COMPLETED
        }

        val todayLog = habitLogs.find { it.date == date.toString() }
        val isCompletedToday = isHabitComplete(todayLog)
        val currentValue = todayLog?.value ?: 0.0
        val lastCompletedDate = habitLogs
            .filter { isHabitComplete(it) }
            .maxByOrNull { it.date }
            ?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val isScheduledToday = HabitScheduler.isScheduledOn(habit, date, lastCompletedDate)
        val history = (0..6).map { i ->
            val checkDate = date.minusDays(i.toLong()).toString()
            isHabitComplete(habitLogs.find { it.date == checkDate })
        }.reversed()
        val streak = if (habit.scheduleMode.equals("INTERVAL", ignoreCase = true)) {
            habitLogs.count { isHabitComplete(it) }
        } else {
            var s = 0
            var checkDate = if (isCompletedToday) date else date.minusDays(1)
            for (i in 0..365) {
                val scheduled = HabitScheduler.isScheduledOn(habit, checkDate, null)
                val log = habitLogs.find { it.date == checkDate.toString() }
                if (isHabitComplete(log)) {
                    s++
                    checkDate = checkDate.minusDays(1)
                } else if (!scheduled) {
                    checkDate = checkDate.minusDays(1)
                } else {
                    break
                }
            }
            s
        }
        return HabitDayStats(isCompletedToday, currentValue, streak, history, isScheduledToday)
    }
}
