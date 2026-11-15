package cloud.wafflecommons.pixelbrainreader.data.experiments

import cloud.wafflecommons.pixelbrainreader.data.health.HealthInsights
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/** One experiment as the vault keeps it (`10_Journal/data/experiments/experiments.json`). */
@Serializable
data class Experiment(
    val id: String,
    /** What you expect, in your words ("No screens after 22:00 helps me sleep"). */
    val hypothesis: String,
    /** The habit you tick on the days you do it. */
    @SerialName("habit") val habitId: String,
    /** An [Experiments.Outcome] key; persisted, never renamed. */
    val outcome: String,
    /** The baseline's first day, yyyy-MM-dd. */
    val start: String,
    @SerialName("baseline_days") val baselineDays: Int = Experiments.BASELINE_DAYS,
    @SerialName("trial_days") val trialDays: Int = Experiments.TRIAL_DAYS
)

@Serializable
data class ExperimentFile(val experiments: List<Experiment> = emptyList())

/**
 * Personal experiments (L2, 12.21; the owner's D2–D3): "does doing X change Y?", run as a baseline
 * then a trial — [BASELINE_DAYS] days as usual, then [TRIAL_DAYS] days doing X, a habit ticked on
 * the days it was done. The result, shown once the trial is over (looking along the way invites
 * stopping on a lucky day), compares Y over the baseline with Y on the trial days X was done: the
 * difference of the means, with a 95% bootstrap interval, and "can't tell" when the interval holds
 * zero. Measured values only; a day still being lived never counts. Pure.
 */
object Experiments {

    const val BASELINE_DAYS = 14
    const val TRIAL_DAYS = 14

    /** Fewer measured days than this on either side: no result. */
    const val MIN_DAYS = 7

    const val RESAMPLES = 2_000

    /** What may change (D2). The keys are persisted: never rename one. */
    enum class Outcome(val key: String, val label: String) {
        MOOD("mood", "Mood"),
        SLEEP("sleep", "Sleep"),
        HRV("hrv", "HRV"),
        TASKS("tasks", "Tasks done");

        /** Where the value of a day comes from, said on the card. */
        val source: String
            get() = when (this) {
                MOOD -> "the day's average mood"
                SLEEP -> "the night that follows the day"
                HRV -> "the next night's HRV"
                TASKS -> "the share of the day's tasks ticked"
            }

        companion object {
            fun of(key: String): Outcome? = entries.firstOrNull { it.key == key }
        }
    }

    enum class Phase { BASELINE, TRIAL, DONE }

    fun startOf(experiment: Experiment): LocalDate? = runCatching { LocalDate.parse(experiment.start) }.getOrNull()

    fun trialStart(experiment: Experiment): LocalDate? = startOf(experiment)?.plusDays(experiment.baselineDays.toLong())

    /** The trial's last day. */
    fun lastDay(experiment: Experiment): LocalDate? = trialStart(experiment)?.plusDays(experiment.trialDays.toLong() - 1)

    /** The day the result shows: the morning after the last day, once its night is known. */
    fun resultDay(experiment: Experiment): LocalDate? = lastDay(experiment)?.plusDays(1)

    fun phase(experiment: Experiment, today: LocalDate): Phase? {
        val trial = trialStart(experiment) ?: return null
        val result = resultDay(experiment) ?: return null
        return when {
            today.isBefore(trial) -> Phase.BASELINE
            today.isBefore(result) -> Phase.TRIAL
            else -> Phase.DONE
        }
    }

    /** Today's place in its phase, from 1; null once done. */
    fun dayOfPhase(experiment: Experiment, today: LocalDate): Int? {
        val start = startOf(experiment) ?: return null
        return when (phase(experiment, today)) {
            Phase.BASELINE -> ChronoUnit.DAYS.between(start, today).toInt() + 1
            Phase.TRIAL -> trialStart(experiment)?.let { ChronoUnit.DAYS.between(it, today).toInt() + 1 }
            else -> null
        }
    }

    /**
     * Each day's value of [outcome], keyed by the day it belongs to. A night counts for the day it
     * ends (as [HealthInsights.Day] holds it): sleep and HRV are the next day's night.
     */
    fun valuesOf(outcome: Outcome, days: List<HealthInsights.Day>): Map<LocalDate, Double> {
        val byDate = days.associateBy { it.date }
        return days.mapNotNull { day ->
            val value = when (outcome) {
                Outcome.MOOD -> day.mood
                Outcome.SLEEP -> byDate[day.date.plusDays(1)]?.sleepMinutes
                Outcome.HRV -> byDate[day.date.plusDays(1)]?.hrvMs
                Outcome.TASKS -> day.tasksDone
            }
            value?.let { day.date to it }
        }.toMap()
    }

    enum class Verdict { NOT_ENOUGH, CANT_TELL, HIGHER, LOWER }

    data class Result(
        val baselineN: Int,
        val trialN: Int,
        /** Trial days lived so far, and those on which the habit was ticked. */
        val trialDaysSoFar: Int,
        val trialDaysDone: Int,
        val baselineMean: Double?,
        val trialMean: Double?,
        /** Trial mean minus baseline mean, and its 95% interval. */
        val difference: Double?,
        val low: Double?,
        val high: Double?
    ) {
        val verdict: Verdict
            get() {
                val d = difference ?: return Verdict.NOT_ENOUGH
                val lo = low ?: return Verdict.NOT_ENOUGH
                val hi = high ?: return Verdict.NOT_ENOUGH
                return when {
                    lo <= 0.0 && hi >= 0.0 -> Verdict.CANT_TELL
                    d > 0 -> Verdict.HIGHER
                    else -> Verdict.LOWER
                }
            }
    }

    /**
     * The experiment as it stands [today]: the baseline's days, the trial days the habit was
     * [done], each with a measured [values] entry and before [today].
     */
    fun result(experiment: Experiment, values: Map<LocalDate, Double>, done: Set<LocalDate>, today: LocalDate): Result? {
        val start = startOf(experiment) ?: return null
        val trial = trialStart(experiment) ?: return null
        val baselineDays = (0 until experiment.baselineDays).map { start.plusDays(it.toLong()) }.filter { it.isBefore(today) }
        val trialDays = (0 until experiment.trialDays).map { trial.plusDays(it.toLong()) }.filter { it.isBefore(today) }
        val before = baselineDays.mapNotNull { values[it] }
        val during = trialDays.filter { it in done }.mapNotNull { values[it] }
        val enough = before.size >= MIN_DAYS && during.size >= MIN_DAYS
        val bounds = if (enough) interval(before, during, seedOf(experiment.id)) else null
        return Result(
            baselineN = before.size,
            trialN = during.size,
            trialDaysSoFar = trialDays.size,
            trialDaysDone = trialDays.count { it in done },
            baselineMean = before.takeIf { it.isNotEmpty() }?.average(),
            trialMean = during.takeIf { it.isNotEmpty() }?.average(),
            difference = if (enough) during.average() - before.average() else null,
            low = bounds?.first,
            high = bounds?.second
        )
    }

    /**
     * The 95% percentile-bootstrap interval of mean(b) − mean(a): each side resampled with
     * replacement [RESAMPLES] times. Seeded, so a result reads the same each time it is shown.
     */
    fun interval(a: List<Double>, b: List<Double>, seed: Long): Pair<Double, Double> {
        require(a.isNotEmpty() && b.isNotEmpty())
        val random = Random(seed)
        val differences = DoubleArray(RESAMPLES) { resampledMean(b, random) - resampledMean(a, random) }
        differences.sort()
        val low = differences[(RESAMPLES * 0.025).toInt()]
        val high = differences[(RESAMPLES * 0.975).toInt() - 1]
        return low to high
    }

    private fun resampledMean(values: List<Double>, random: Random): Double {
        var sum = 0.0
        repeat(values.size) { sum += values[random.nextInt(values.size)] }
        return sum / values.size
    }

    /** A stable seed from the experiment's id (String.hashCode is specified, so it never moves). */
    fun seedOf(id: String): Long = id.hashCode().toLong()

    /** A difference in the outcome's own unit, signed: "+0.4", "+22 min", "−4 ms", "+12 pts". */
    fun amount(outcome: Outcome, value: Double): String {
        val sign = if (value < 0) "−" else "+"
        val size = abs(value)
        return sign + when (outcome) {
            Outcome.MOOD -> String.format(Locale.ROOT, "%.1f", size)
            Outcome.SLEEP -> "${size.roundToInt()} min"
            Outcome.HRV -> "${size.roundToInt()} ms"
            Outcome.TASKS -> "${(size * 100).roundToInt()} pts"
        }
    }

    /** The result in plain words. */
    fun words(outcome: Outcome, result: Result): String = when (result.verdict) {
        Verdict.NOT_ENOUGH ->
            "Not enough measured days: ${result.baselineN} before, ${result.trialN} during (at least $MIN_DAYS each)"
        Verdict.CANT_TELL ->
            "Can't tell: ${amount(outcome, result.difference ?: 0.0)} is within the day-to-day noise " +
                "(95%: ${amount(outcome, result.low ?: 0.0)} to ${amount(outcome, result.high ?: 0.0)})"
        Verdict.HIGHER, Verdict.LOWER ->
            (if (result.verdict == Verdict.HIGHER) "Higher" else "Lower") +
                " during the trial: ${amount(outcome, result.difference ?: 0.0)} " +
                "(95%: ${amount(outcome, result.low ?: 0.0)} to ${amount(outcome, result.high ?: 0.0)})"
    }
}
