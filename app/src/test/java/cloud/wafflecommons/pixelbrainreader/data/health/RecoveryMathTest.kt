package cloud.wafflecommons.pixelbrainreader.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class RecoveryMathTest {

    private val night = LocalDate.of(2026, 10, 18)
    private val zone = ZoneId.of("Europe/Paris")

    /** [count] nights before [night], each with the given signals. */
    private fun history(count: Int, rhr: Int? = 50, hrv: Double? = 50.0, vary: Boolean = true): List<RecoveryDay> =
        (1..count).map { i ->
            val wobble = if (vary) (if (i % 2 == 0) 1 else -1) else 0
            RecoveryDay(
                date = night.minusDays(i.toLong()),
                restingHeartRate = rhr?.plus(wobble),
                hrvMs = hrv?.plus(wobble * 2),
                sleepMinutes = 420L + wobble * 10
            )
        }

    @Test
    fun `stages add up by type, generic sleep and out-of-bed count in none`() {
        val stages = SleepStages.of(
            listOf(
                SleepStages.STAGE_DEEP to 60L,
                SleepStages.STAGE_REM to 90L,
                SleepStages.STAGE_LIGHT to 200L,
                SleepStages.STAGE_AWAKE to 20L,
                SleepStages.STAGE_AWAKE_IN_BED to 5L,
                SleepStages.STAGE_SLEEPING to 30L,
                SleepStages.STAGE_OUT_OF_BED to 10L
            )
        )
        assertEquals(SleepStages(deep = 60, rem = 90, light = 200, awake = 25), stages)
        assertEquals(350L, stages.asleep)
        assertTrue(stages.recorded)
    }

    @Test
    fun `a measurement belongs to the night it ends`() {
        fun at(day: Int, hour: Int, minute: Int = 0) =
            LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant()
        assertEquals(night, RecoveryDay.nightOf(at(17, 23), zone))
        assertEquals(night, RecoveryDay.nightOf(at(18, 5), zone))
        assertEquals(night, RecoveryDay.nightOf(at(18, 17, 59), zone))
        assertEquals(night.plusDays(1), RecoveryDay.nightOf(at(18, 18), zone))
    }

    @Test
    fun `in the vault's metrics, zero means not measured`() {
        val day = RecoveryDay.from(DailyHealthMetrics(date = night.toString(), steps = 0, sleepDurationMinutes = 0, averageHeartRate = 0))!!
        assertNull(day.restingHeartRate)
        assertNull(day.hrvMs)
        assertNull(day.sleepMinutes)
        assertNull(day.stages)

        val measured = RecoveryDay.from(
            DailyHealthMetrics(
                date = night.toString(), steps = 0, sleepDurationMinutes = 400, averageHeartRate = 60,
                restingHeartRate = 51, hrvRmssdMs = 44.5, sleepDeepMinutes = 70
            )
        )!!
        assertEquals(51, measured.restingHeartRate)
        assertEquals(44.5, measured.hrvMs!!, 0.0)
        assertEquals(70L, measured.stages!!.deep)
    }

    @Test
    fun `a baseline needs seven nights`() {
        assertNull(RecoveryMath.baseline(List(6) { 50.0 }))
        val baseline = RecoveryMath.baseline(listOf(48.0, 50.0, 52.0, 48.0, 50.0, 52.0, 50.0))!!
        assertEquals(50.0, baseline.mean, 1e-9)
        assertEquals(7, baseline.nights)
        assertTrue(baseline.sd > 0)
    }

    @Test
    fun `no signal last night reads as no data`() {
        val days = history(20) + RecoveryDay(night, sleepMinutes = 400)
        assertEquals(RecoveryStatus.NO_DATA, RecoveryMath.readout(days, night).status)
        assertEquals(RecoveryStatus.NO_DATA, RecoveryMath.readout(history(20), night).status)
    }

    @Test
    fun `fewer than seven nights before builds the baseline`() {
        val readout = RecoveryMath.readout(history(4) + RecoveryDay(night, restingHeartRate = 50, hrvMs = 50.0), night)
        assertEquals(RecoveryStatus.BUILDING_BASELINE, readout.status)
        assertEquals(4, readout.nightsWithSignals)
    }

    @Test
    fun `low HRV or a raised resting heart rate is strained`() {
        val lowHrv = RecoveryMath.readout(history(20) + RecoveryDay(night, restingHeartRate = 50, hrvMs = 40.0), night)
        assertEquals(RecoveryStatus.STRAINED, lowHrv.status)
        assertEquals(-20.0, lowHrv.hrvDeltaPercent!!, 1.0)

        val raisedResting = RecoveryMath.readout(history(20) + RecoveryDay(night, restingHeartRate = 56, hrvMs = 50.0), night)
        assertEquals(RecoveryStatus.STRAINED, raisedResting.status)
        assertEquals(6.0, raisedResting.restingDelta!!, 0.5)
    }

    @Test
    fun `high HRV with resting heart rate not up is rested, the rest is in range`() {
        assertEquals(
            RecoveryStatus.RESTED,
            RecoveryMath.readout(history(20) + RecoveryDay(night, restingHeartRate = 49, hrvMs = 60.0), night).status
        )
        assertEquals(
            RecoveryStatus.IN_RANGE,
            RecoveryMath.readout(history(20) + RecoveryDay(night, restingHeartRate = 50, hrvMs = 51.0), night).status
        )
    }

    @Test
    fun `nights outside the four-week window don't count`() {
        val old = (29..60).map { RecoveryDay(night.minusDays(it.toLong()), restingHeartRate = 80, hrvMs = 20.0) }
        val readout = RecoveryMath.readout(old + history(3) + RecoveryDay(night, restingHeartRate = 50, hrvMs = 50.0), night)
        assertEquals(RecoveryStatus.BUILDING_BASELINE, readout.status)
        assertEquals(3, readout.nightsWithSignals)
    }

    @Test
    fun `Health Connect wins per signal, the vault fills the gaps`() {
        val live = listOf(RecoveryDay(night, restingHeartRate = 50, hrvMs = null))
        val stored = listOf(
            RecoveryDay(night, restingHeartRate = 99, hrvMs = 42.0, sleepMinutes = 400),
            RecoveryDay(night.minusDays(1), restingHeartRate = 52)
        )
        val merged = RecoveryRepository.merge(live, stored)

        assertEquals(listOf(night.minusDays(1), night), merged.map { it.date })
        assertEquals(RecoveryDay(night, restingHeartRate = 50, hrvMs = 42.0, sleepMinutes = 400), merged.last())
    }
}
