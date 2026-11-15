package cloud.wafflecommons.pixelbrainreader.data.experiments

import cloud.wafflecommons.pixelbrainreader.data.experiments.Experiments.Outcome
import cloud.wafflecommons.pixelbrainreader.data.experiments.Experiments.Phase
import cloud.wafflecommons.pixelbrainreader.data.experiments.Experiments.Verdict
import cloud.wafflecommons.pixelbrainreader.data.health.HealthInsights
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ExperimentsTest {

    private val start = LocalDate.parse("2026-11-02")
    private val experiment = Experiment(
        id = "e1",
        hypothesis = "No screens after 22:00 helps me sleep",
        habitId = "no-screens",
        outcome = Outcome.SLEEP.key,
        start = start.toString()
    )
    private val trialStart = start.plusDays(14)
    private val lastDay = start.plusDays(27)

    @Test
    fun `the phases follow the days, and the result waits for the last night`() {
        assertEquals(Phase.BASELINE, Experiments.phase(experiment, start))
        assertEquals(1, Experiments.dayOfPhase(experiment, start))
        assertEquals(14, Experiments.dayOfPhase(experiment, trialStart.minusDays(1)))
        assertEquals(Phase.TRIAL, Experiments.phase(experiment, trialStart))
        assertEquals(1, Experiments.dayOfPhase(experiment, trialStart))
        assertEquals(Phase.TRIAL, Experiments.phase(experiment, lastDay))
        assertEquals(lastDay.plusDays(1), Experiments.resultDay(experiment))
        assertEquals(Phase.DONE, Experiments.phase(experiment, lastDay.plusDays(1)))
        assertNull(Experiments.dayOfPhase(experiment, lastDay.plusDays(1)))
        assertNull(Experiments.phase(experiment.copy(start = "soon"), start))
    }

    @Test
    fun `a night counts for the day before it, mood and tasks for their own day`() {
        val d = start
        val days = listOf(
            HealthInsights.Day(d, sleepMinutes = 400.0, hrvMs = 40.0, mood = 3.0, tasksDone = 0.5),
            HealthInsights.Day(d.plusDays(1), sleepMinutes = 480.0, hrvMs = 55.0)
        )
        assertEquals(mapOf(d to 480.0), Experiments.valuesOf(Outcome.SLEEP, days))
        assertEquals(mapOf(d to 55.0), Experiments.valuesOf(Outcome.HRV, days))
        assertEquals(mapOf(d to 3.0), Experiments.valuesOf(Outcome.MOOD, days))
        assertEquals(mapOf(d to 0.5), Experiments.valuesOf(Outcome.TASKS, days))
    }

    @Test
    fun `a clear shift reads as higher, its interval around it`() {
        val values = (0..27).associate { i ->
            val day = start.plusDays(i.toLong())
            // A wobble of ±10 minutes around 420 before, 470 during.
            day to (if (i < 14) 420.0 else 470.0) + (if (i % 2 == 0) 10.0 else -10.0)
        }
        val done = (14..27).map { start.plusDays(it.toLong()) }.toSet()
        val result = Experiments.result(experiment, values, done, lastDay.plusDays(1))!!
        assertEquals(14, result.baselineN)
        assertEquals(14, result.trialN)
        assertEquals(14, result.trialDaysDone)
        assertEquals(Verdict.HIGHER, result.verdict)
        assertEquals(50.0, result.difference!!, 1e-9)
        val low = result.low!!
        val high = result.high!!
        assertTrue(low > 0 && low < 50 && high > 50)
        assertTrue(Experiments.words(Outcome.SLEEP, result).startsWith("Higher during the trial: +50 min"))
    }

    @Test
    fun `noise alone can't tell`() {
        val wobble = listOf(400.0, 460.0, 420.0, 480.0, 390.0, 450.0, 430.0)
        val values = (0..27).associate { i -> start.plusDays(i.toLong()) to wobble[i % wobble.size] + (if (i >= 14) 3.0 else 0.0) }
        val done = (14..27).map { start.plusDays(it.toLong()) }.toSet()
        val result = Experiments.result(experiment, values, done, lastDay.plusDays(1))!!
        assertEquals(Verdict.CANT_TELL, result.verdict)
        assertTrue(Experiments.words(Outcome.SLEEP, result).startsWith("Can't tell"))
    }

    @Test
    fun `only the trial days the habit was done count, and never today`() {
        val values = (0..27).associate { i -> start.plusDays(i.toLong()) to 420.0 + i }
        // Done on 5 trial days only: too few for a result.
        val done = (14..18).map { start.plusDays(it.toLong()) }.toSet()
        val result = Experiments.result(experiment, values, done, lastDay.plusDays(1))!!
        assertEquals(5, result.trialN)
        assertEquals(14, result.trialDaysSoFar)
        assertEquals(Verdict.NOT_ENOUGH, result.verdict)
        assertNull(result.difference)
        // Mid-baseline, today (day 5) isn't counted yet.
        val early = Experiments.result(experiment, values, emptySet(), start.plusDays(4))!!
        assertEquals(4, early.baselineN)
        assertEquals(0, early.trialDaysSoFar)
    }

    @Test
    fun `the interval is seeded, the same each time it is shown`() {
        val a = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 2.0, 3.0)
        val b = listOf(2.0, 3.0, 4.0, 5.0, 6.0, 3.0, 4.0)
        assertEquals(Experiments.interval(a, b, 7L), Experiments.interval(a, b, 7L))
        assertEquals(Experiments.seedOf("e1"), Experiments.seedOf("e1"))
    }

    @Test
    fun `amounts read in the outcome's own unit`() {
        assertEquals("+0.4", Experiments.amount(Outcome.MOOD, 0.4))
        assertEquals("−22 min", Experiments.amount(Outcome.SLEEP, -22.4))
        assertEquals("+4 ms", Experiments.amount(Outcome.HRV, 4.2))
        assertEquals("+12 pts", Experiments.amount(Outcome.TASKS, 0.12))
    }

    @Test
    fun `the file keeps its keys and reads an older or newer one`() {
        // The repository's settings: defaults written, so the file says how long each period runs.
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val text = json.encodeToString(ExperimentFile.serializer(), ExperimentFile(listOf(experiment)))
        assertTrue(text.contains("\"habit\":\"no-screens\""))
        assertTrue(text.contains("\"baseline_days\":14"))
        val newer = """{"experiments":[{"id":"x","hypothesis":"h","habit":"a","outcome":"mood","start":"2026-11-01","later":1}]}"""
        val read = json.decodeFromString(ExperimentFile.serializer(), newer).experiments.single()
        assertEquals(Experiments.TRIAL_DAYS, read.trialDays)
        assertEquals(Outcome.MOOD, Outcome.of(read.outcome))
    }
}
