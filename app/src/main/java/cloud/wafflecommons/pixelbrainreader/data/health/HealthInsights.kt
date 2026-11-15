package cloud.wafflecommons.pixelbrainreader.data.health

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Honest health insights (L1, 12.20; the owner's D10): how two of your measures move together over
 * the last [WINDOW_DAYS] days, one a day ahead of the other, as Spearman's rank correlation. Shown
 * only from [MIN_DAYS] paired days, always with N, and never as a cause. Measured values only: a
 * day missing either side is left out. Pure.
 */
object HealthInsights {

    const val MIN_DAYS = 14
    const val WINDOW_DAYS = 90

    /** The four pairs: what comes first, what may follow, and how many days later. */
    enum class Link(val label: String) {
        SLEEP_MOOD("Sleep → next-day mood"),
        STEPS_SLEEP("Steps → that night's sleep"),
        SLEEP_TASKS("Sleep → next-day tasks"),
        MOOD_HRV("Mood → next-night HRV")
    }

    /**
     * One day's measures. A night counts for the day it ends (sleep and HRV, as the vault keeps
     * them); mood is the day's average, tasks the share ticked.
     */
    data class Day(
        val date: LocalDate,
        val sleepMinutes: Double? = null,
        val hrvMs: Double? = null,
        val steps: Double? = null,
        val mood: Double? = null,
        val tasksDone: Double? = null
    )

    /** One pair's result: its N, and ρ once N reaches [MIN_DAYS] and both sides vary. */
    data class Insight(val link: Link, val n: Int, val rho: Double?) {
        val enoughDays: Boolean get() = n >= MIN_DAYS
    }

    fun of(days: List<Day>): List<Insight> {
        val byDate = days.associateBy { it.date }
        return listOf(
            insight(Link.SLEEP_MOOD, byDate) { d -> byDate[d]?.sleepMinutes to byDate[d]?.mood },
            insight(Link.STEPS_SLEEP, byDate) { d -> byDate[d]?.steps to byDate[d.plusDays(1)]?.sleepMinutes },
            insight(Link.SLEEP_TASKS, byDate) { d -> byDate[d]?.sleepMinutes to byDate[d]?.tasksDone },
            insight(Link.MOOD_HRV, byDate) { d -> byDate[d]?.mood to byDate[d.plusDays(1)]?.hrvMs }
        )
    }

    private fun insight(
        link: Link,
        byDate: Map<LocalDate, Day>,
        sides: (LocalDate) -> kotlin.Pair<Double?, Double?>
    ): Insight {
        val points = byDate.keys.sorted().mapNotNull { date ->
            val (x, y) = sides(date)
            if (x != null && y != null) x to y else null
        }
        val rho = if (points.size >= MIN_DAYS) spearman(points.map { it.first }, points.map { it.second }) else null
        return Insight(link, points.size, rho)
    }

    /** Spearman's ρ: Pearson's r over the ranks, ties averaged; null when either side never varies. */
    fun spearman(x: List<Double>, y: List<Double>): Double? {
        require(x.size == y.size)
        if (x.size < 2) return null
        return pearson(ranks(x), ranks(y))
    }

    /** Ranks from 1, equal values sharing the average of their ranks. */
    fun ranks(values: List<Double>): List<Double> {
        val order = values.indices.sortedBy { values[it] }
        val ranks = DoubleArray(values.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && values[order[j + 1]] == values[order[i]]) j++
            val average = (i + j) / 2.0 + 1.0
            for (k in i..j) ranks[order[k]] = average
            i = j + 1
        }
        return ranks.toList()
    }

    private fun pearson(a: List<Double>, b: List<Double>): Double? {
        val meanA = a.average()
        val meanB = b.average()
        var cov = 0.0
        var varA = 0.0
        var varB = 0.0
        for (k in a.indices) {
            val da = a[k] - meanA
            val db = b[k] - meanB
            cov += da * db
            varA += da * da
            varB += db * db
        }
        if (varA == 0.0 || varB == 0.0) return null
        return cov / sqrt(varA * varB)
    }

    /** ρ in plain words: how strongly, and which way. */
    fun words(rho: Double): String {
        val size = abs(rho)
        val strength = when {
            size < 0.1 -> return "No clear link"
            size < 0.3 -> "Weak"
            size < 0.5 -> "Moderate"
            else -> "Strong"
        }
        return if (rho > 0) "$strength: they rise together" else "$strength: one rises as the other falls"
    }
}
