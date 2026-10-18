package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.health.RecoveryDay
import java.time.LocalDate
import kotlin.math.sqrt

/** A work day's load: meeting time and off-ticket time are the minutes; requests are counted. */
data class DayLoad(val date: LocalDate, val meetings: Int, val meetingMinutes: Int, val offTicketMinutes: Int, val requests: Int) {
    val minutes: Int get() = meetingMinutes + offTicketMinutes
}

/** A recovery signal set against load: averages after heavy and light days, the correlation, and how many days each rests on. */
data class LoadComparison(
    val metric: String,
    val heavyMean: Double?,
    val heavyDays: Int,
    val lightMean: Double?,
    val lightDays: Int,
    val correlation: Double?,
    val pairs: Int
)

/** A heavier week than usual meeting a recovery below the user's norm. */
data class OverloadSignal(val loadIncreasePercent: Int, val weekMinutes: Int, val usualWeekMinutes: Int, val reasons: List<String>)

/**
 * Load vs recovery (#7). A work day's load (meetings and off-ticket minutes, from the meeting
 * notes and the mission log) is set against the next night's sleep, HRV and resting heart rate
 * and that day's mood. Heavy days are those above the median load. Everything comes with its
 * sample size, and nothing is shown below [MIN_DAYS]: a correlation is not a cause.
 */
object LoadRecovery {

    const val MIN_DAYS = 10
    const val OVERLOAD_RATIO = 1.3

    fun dailyLoad(days: List<LocalDate>, meetings: List<Meeting>, log: List<LogEntry>): List<DayLoad> = days.map { day ->
        val dayMeetings = meetings.filter { it.date == day }
        val dayLog = log.filter { it.at.toLocalDate() == day }
        DayLoad(
            date = day,
            meetings = dayMeetings.size,
            meetingMinutes = dayMeetings.sumOf { it.durationMinutes ?: 0 },
            offTicketMinutes = dayLog.filter { it.kind == LogKind.OFF_TICKET }.sumOf { it.minutes ?: 0 },
            requests = dayLog.count { it.kind == LogKind.REQUEST }
        )
    }

    /**
     * Each recovery metric against load. Sleep, HRV and resting heart rate are the night after the
     * day (nights are dated by the day they end); mood is the day's own average.
     */
    fun compare(load: List<DayLoad>, nights: Map<LocalDate, RecoveryDay>, mood: Map<LocalDate, Double>): List<LoadComparison> {
        if (load.isEmpty()) return emptyList()
        // The lower median: with as many light as heavy days, "above it" still splits them in two.
        val median = load.map { it.minutes }.sorted().let { it[(it.size - 1) / 2] }
        fun metric(name: String, value: (DayLoad) -> Double?): LoadComparison {
            val pairs = load.mapNotNull { day -> value(day)?.let { day to it } }
            val heavy = pairs.filter { (day, _) -> day.minutes > median }.map { it.second }
            val light = pairs.filter { (day, _) -> day.minutes <= median }.map { it.second }
            return LoadComparison(
                metric = name,
                heavyMean = heavy.takeIf { it.isNotEmpty() }?.average(),
                heavyDays = heavy.size,
                lightMean = light.takeIf { it.isNotEmpty() }?.average(),
                lightDays = light.size,
                correlation = pearson(pairs.map { it.first.minutes.toDouble() }, pairs.map { it.second }),
                pairs = pairs.size
            )
        }
        return listOf(
            metric("sleep") { day -> nights[day.date.plusDays(1)]?.sleepMinutes?.toDouble() },
            metric("hrv") { day -> nights[day.date.plusDays(1)]?.hrvMs },
            metric("resting") { day -> nights[day.date.plusDays(1)]?.restingHeartRate?.toDouble() },
            metric("mood") { day -> mood[day.date] }
        )
    }

    /** Pearson's r; null below [MIN_DAYS] pairs or when either series doesn't vary. */
    fun pearson(xs: List<Double>, ys: List<Double>): Double? {
        if (xs.size != ys.size || xs.size < MIN_DAYS) return null
        val mx = xs.average()
        val my = ys.average()
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - mx
            val dy = ys[i] - my
            sxy += dx * dy
            sxx += dx * dx
            syy += dy * dy
        }
        if (sxx == 0.0 || syy == 0.0) return null
        return sxy / sqrt(sxx * syy)
    }

    /**
     * The last seven days' load against the usual week of [previousWeeks], when it is at least
     * [OVERLOAD_RATIO] times higher AND recovery is below the norm ([recoveryReasons] non-empty).
     */
    fun overload(lastWeekMinutes: Int, previousWeeks: List<Int>, recoveryReasons: List<String>): OverloadSignal? {
        val usual = previousWeeks.takeIf { it.size >= 3 }?.average() ?: return null
        if (usual <= 0.0 || lastWeekMinutes < usual * OVERLOAD_RATIO || recoveryReasons.isEmpty()) return null
        return OverloadSignal(((lastWeekMinutes / usual - 1) * 100).toInt(), lastWeekMinutes, usual.toInt(), recoveryReasons)
    }

    /** "weak", "moderate" or "strong", by |r|. */
    fun strength(r: Double): String = when {
        kotlin.math.abs(r) >= 0.5 -> "strong"
        kotlin.math.abs(r) >= 0.3 -> "moderate"
        else -> "weak"
    }
}
