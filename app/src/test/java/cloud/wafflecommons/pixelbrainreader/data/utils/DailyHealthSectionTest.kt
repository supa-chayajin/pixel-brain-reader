package cloud.wafflecommons.pixelbrainreader.data.utils

import cloud.wafflecommons.pixelbrainreader.data.health.DailyHealthMetrics
import cloud.wafflecommons.pixelbrainreader.data.health.RecoveryReadout
import cloud.wafflecommons.pixelbrainreader.data.health.RecoveryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyHealthSectionTest {

    private fun metrics(
        sleep: Long = 0,
        steps: Long = 0,
        hrv: Double = 0.0,
        resting: Int = 0,
        averageHeartRate: Int = 0,
        deep: Long = 0,
        rem: Long = 0,
        light: Long = 0
    ) = DailyHealthMetrics(
        date = "2026-11-10",
        steps = steps,
        sleepDurationMinutes = sleep,
        averageHeartRate = averageHeartRate,
        restingHeartRate = resting,
        hrvRmssdMs = hrv,
        sleepDeepMinutes = deep,
        sleepRemMinutes = rem,
        sleepLightMinutes = light
    )

    private fun readout(status: RecoveryStatus) = RecoveryReadout(null, null, null, null, status)

    @Test
    fun `every measured value gets its line, in a fixed order`() {
        assertEquals(
            listOf("- Sleep: 7 h 05 min", "- Steps: 12,431", "- HRV: 42 ms", "- Resting heart rate: 57 bpm"),
            DailyHealthSection.lines(metrics(sleep = 425, steps = 12_431, hrv = 41.6, resting = 57))
        )
    }

    @Test
    fun `sleep stages and the recovery verdict follow, when there are some`() {
        assertEquals(
            listOf(
                "- Sleep: 7 h 05 min",
                "- Sleep stages: deep 1 h 05 min · REM 1 h 30 min · light 4 h 10 min",
                "- Steps: 9,000",
                "- Recovery: in your usual range"
            ),
            DailyHealthSection.lines(metrics(sleep = 425, steps = 9_000, deep = 65, rem = 90, light = 250), readout(RecoveryStatus.IN_RANGE))
        )
        // Stages recorded: each one shows, 0 included.
        assertEquals("- Sleep stages: deep 0 min · REM 45 min · light 3 h 00 min", DailyHealthSection.lines(metrics(rem = 45, light = 180))[0])
        // No verdict while the baseline builds or without data; a verdict alone is still a section.
        assertTrue(DailyHealthSection.lines(null, readout(RecoveryStatus.BUILDING_BASELINE)).isEmpty())
        assertTrue(DailyHealthSection.lines(null, readout(RecoveryStatus.NO_DATA)).isEmpty())
        assertEquals(listOf("- Recovery: below your usual recovery"), DailyHealthSection.lines(null, readout(RecoveryStatus.STRAINED)))
    }

    @Test
    fun `a value that was not measured is left out`() {
        assertEquals(listOf("- Steps: 840"), DailyHealthSection.lines(metrics(steps = 840)))
        assertEquals(listOf("- Sleep: 45 min"), DailyHealthSection.lines(metrics(sleep = 45)))
    }

    @Test
    fun `nothing measured or no health file means no section`() {
        assertTrue(DailyHealthSection.lines(null).isEmpty())
        // The average heart rate isn't one of the section's values.
        assertTrue(DailyHealthSection.lines(metrics(averageHeartRate = 70)).isEmpty())
        // An HRV that rounds to 0 counts as not measured.
        assertTrue(DailyHealthSection.lines(metrics(hrv = 0.3)).isEmpty())
    }

    @Test
    fun `every line the section writes is recognised as derived, whatever its value`() {
        val written = DailyHealthSection.lines(metrics(sleep = 600, steps = 1_234_567, hrv = 120.0, resting = 44, deep = 70, rem = 100, light = 300)) +
            DailyHealthSection.lines(metrics(sleep = 9, steps = 3, hrv = 1.0, resting = 1, deep = 1)) +
            listOf(RecoveryStatus.IN_RANGE, RecoveryStatus.STRAINED, RecoveryStatus.RESTED).flatMap { DailyHealthSection.lines(null, readout(it)) }
        assertEquals(13, written.size)
        written.forEach { assertTrue(it, DailyHealthSection.isDerivedLine(it)) }
    }

    @Test
    fun `a line the user wrote is never taken for a derived one`() {
        listOf(
            "- Steps: walked to the park",
            "- Sleep: badly",
            "- HRV: 42",
            "Steps: 8,000",
            "- Resting heart rate: 58",
            "- [ ] Steps: 8,000",
            "- Recovery: great",
            "- Sleep stages: deep sleep was short"
        ).forEach { assertFalse(it, DailyHealthSection.isDerivedLine(it)) }
    }

    @Test
    fun `strip gives back the note as it was before the section was added`() {
        val body = "# 2026-11-10\n\n## 📑 Notes / Self-care\n\nA note\n"
        val withSection = body + "\n${DailyHealthSection.HEADING}\n\n- Steps: 8,421\n- HRV: 42 ms\n"
        assertEquals(body, DailyHealthSection.strip(withSection))
        assertEquals(body, DailyHealthSection.strip(body))
        assertEquals("", DailyHealthSection.strip(""))
    }

    @Test
    fun `strip keeps what the user wrote after the section`() {
        val withSection = "A note\n\n${DailyHealthSection.HEADING}\n\n- Steps: 8,421\n\nEvening thought\n"
        assertEquals("A note\nEvening thought\n", DailyHealthSection.strip(withSection))
    }
}
