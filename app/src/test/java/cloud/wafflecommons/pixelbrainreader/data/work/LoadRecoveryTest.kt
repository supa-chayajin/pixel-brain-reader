package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.health.RecoveryDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LoadRecoveryTest {

    private val start = LocalDate.of(2026, 9, 1)

    @Test
    fun `a day's load is its meeting time plus its off-ticket time`() {
        val day = LocalDate.of(2026, 10, 14)
        val meeting = MeetingFormat.parse(WorkNote.from("r/2026-10-14 A.md", "---\ntitle: \"A\"\ntags:\n  - reunion\nduration_min: 60\n---\n"))
        val log = listOf(
            LogEntry(day.atTime(10, 0), LogKind.OFF_TICKET, "Support", minutes = 45),
            LogEntry(day.atTime(11, 0), LogKind.REQUEST, "Revue"),
            LogEntry(day.plusDays(1).atTime(9, 0), LogKind.OFF_TICKET, "Ailleurs", minutes = 99)
        )
        val load = LoadRecovery.dailyLoad(listOf(day), listOf(meeting), log).single()
        assertEquals(DayLoad(day, meetings = 1, meetingMinutes = 60, offTicketMinutes = 45, requests = 1), load)
        assertEquals(105, load.minutes)
    }

    @Test
    fun `recovery is the next night, heavy days are above the median, sample sizes are kept`() {
        // 20 days: load alternates 0 / 120 minutes; the night after a heavy day has 60 minutes less sleep.
        val days = (0 until 20).map { start.plusDays(it.toLong()) }
        val load = days.mapIndexed { i, d -> DayLoad(d, 0, 0, if (i % 2 == 0) 120 else 0, 0) }
        val nights = days.associate { d ->
            val heavy = load.first { it.date == d }.minutes > 0
            d.plusDays(1) to RecoveryDay(d.plusDays(1), sleepMinutes = if (heavy) 360 else 420, hrvMs = if (heavy) 40.0 else 50.0)
        }
        val comparisons = LoadRecovery.compare(load, nights, emptyMap()).associateBy { it.metric }

        val sleep = comparisons.getValue("sleep")
        assertEquals(360.0, sleep.heavyMean!!, 0.0)
        assertEquals(420.0, sleep.lightMean!!, 0.0)
        assertEquals(10, sleep.heavyDays)
        assertEquals(10, sleep.lightDays)
        assertEquals(-1.0, sleep.correlation!!, 1e-9)
        assertEquals(0, comparisons.getValue("mood").pairs)
        assertNull(comparisons.getValue("mood").correlation)
    }

    @Test
    fun `no correlation below ten days or without variation`() {
        assertNull(LoadRecovery.pearson(List(9) { it.toDouble() }, List(9) { it.toDouble() }))
        assertNull(LoadRecovery.pearson(List(12) { 5.0 }, List(12) { it.toDouble() }))
        assertEquals(1.0, LoadRecovery.pearson(List(12) { it.toDouble() }, List(12) { 2.0 * it })!!, 1e-9)
    }

    @Test
    fun `overload needs a much heavier week AND a recovery below the norm`() {
        val reasons = listOf("HRV over the last nights is below your norm")
        val signal = LoadRecovery.overload(lastWeekMinutes = 900, previousWeeks = listOf(600, 600, 600), recoveryReasons = reasons)
        assertNotNull(signal)
        assertEquals(50, signal!!.loadIncreasePercent)

        assertNull(LoadRecovery.overload(900, listOf(600, 600, 600), emptyList()))
        assertNull(LoadRecovery.overload(700, listOf(600, 600, 600), reasons))
        assertNull(LoadRecovery.overload(900, listOf(600, 600), reasons)) // too little history
        assertTrue(LoadRecovery.strength(-0.6) == "strong" && LoadRecovery.strength(0.35) == "moderate" && LoadRecovery.strength(0.1) == "weak")
    }
}
