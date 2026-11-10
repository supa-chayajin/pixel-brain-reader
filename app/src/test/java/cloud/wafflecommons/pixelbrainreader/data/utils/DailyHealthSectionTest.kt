package cloud.wafflecommons.pixelbrainreader.data.utils

import cloud.wafflecommons.pixelbrainreader.data.health.DailyHealthMetrics
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
        averageHeartRate: Int = 0
    ) = DailyHealthMetrics(
        date = "2026-11-10",
        steps = steps,
        sleepDurationMinutes = sleep,
        averageHeartRate = averageHeartRate,
        restingHeartRate = resting,
        hrvRmssdMs = hrv
    )

    @Test
    fun `every measured value gets its line, in a fixed order`() {
        assertEquals(
            listOf("- Sleep: 7 h 05 min", "- Steps: 12,431", "- HRV: 42 ms", "- Resting heart rate: 57 bpm"),
            DailyHealthSection.lines(metrics(sleep = 425, steps = 12_431, hrv = 41.6, resting = 57))
        )
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
        val written = DailyHealthSection.lines(metrics(sleep = 600, steps = 1_234_567, hrv = 120.0, resting = 44)) +
            DailyHealthSection.lines(metrics(sleep = 9, steps = 3, hrv = 1.0, resting = 1))
        assertEquals(8, written.size)
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
            "- [ ] Steps: 8,000"
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
