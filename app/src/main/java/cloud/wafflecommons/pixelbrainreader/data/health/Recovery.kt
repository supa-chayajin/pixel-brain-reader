package cloud.wafflecommons.pixelbrainreader.data.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sqrt

/** Minutes per sleep stage for one night. All zero when the device recorded no stages. */
data class SleepStages(val deep: Long = 0, val rem: Long = 0, val light: Long = 0, val awake: Long = 0) {
    val asleep: Long get() = deep + rem + light
    val recorded: Boolean get() = deep + rem + light + awake > 0

    operator fun plus(other: SleepStages) =
        SleepStages(deep + other.deep, rem + other.rem, light + other.light, awake + other.awake)

    companion object {
        // Health Connect's SleepSessionRecord.STAGE_TYPE_* values (stable API constants).
        const val STAGE_AWAKE = 1
        const val STAGE_SLEEPING = 2
        const val STAGE_OUT_OF_BED = 3
        const val STAGE_LIGHT = 4
        const val STAGE_DEEP = 5
        const val STAGE_REM = 6
        const val STAGE_AWAKE_IN_BED = 7

        /** Sums stage spans (type, minutes). Generic "sleeping" and out-of-bed time count in no stage. */
        fun of(spans: List<Pair<Int, Long>>): SleepStages = spans.fold(SleepStages()) { acc, (type, minutes) ->
            when (type) {
                STAGE_DEEP -> acc.copy(deep = acc.deep + minutes)
                STAGE_REM -> acc.copy(rem = acc.rem + minutes)
                STAGE_LIGHT -> acc.copy(light = acc.light + minutes)
                STAGE_AWAKE, STAGE_AWAKE_IN_BED -> acc.copy(awake = acc.awake + minutes)
                else -> acc
            }
        }
    }
}

/**
 * One night's recovery signals, attributed to the day the night ends. Null means "not measured":
 * nothing here is ever estimated.
 */
data class RecoveryDay(
    val date: LocalDate,
    val restingHeartRate: Int? = null,
    val hrvMs: Double? = null,
    val sleepMinutes: Long? = null,
    val stages: SleepStages? = null
) {
    val hasSignals: Boolean get() = restingHeartRate != null || hrvMs != null

    companion object {
        /** From the vault's daily metrics, where 0 means "not measured". */
        fun from(metrics: DailyHealthMetrics): RecoveryDay? {
            val date = runCatching { LocalDate.parse(metrics.date) }.getOrNull() ?: return null
            val stages = SleepStages(metrics.sleepDeepMinutes, metrics.sleepRemMinutes, metrics.sleepLightMinutes, metrics.sleepAwakeMinutes)
            return RecoveryDay(
                date = date,
                restingHeartRate = metrics.restingHeartRate.takeIf { it > 0 },
                hrvMs = metrics.hrvRmssdMs.takeIf { it > 0.0 },
                sleepMinutes = metrics.sleepDurationMinutes.takeIf { it > 0 },
                stages = stages.takeIf { it.recorded }
            )
        }

        /**
         * The night a measurement belongs to: the sleep window runs from 18:00 the day before to
         * 18:00, so 23:00 on Monday and 05:00 on Tuesday both belong to Tuesday's night.
         */
        fun nightOf(instant: Instant, zone: ZoneId): LocalDate = instant.atZone(zone).plusHours(6).toLocalDate()
    }
}

/** A personal norm: the mean and spread of the nights before, and how many nights it rests on. */
data class Baseline(val mean: Double, val sd: Double, val nights: Int)

enum class RecoveryStatus { NO_DATA, BUILDING_BASELINE, IN_RANGE, STRAINED, RESTED }

/** Last night against the nights before it; [nightsWithSignals] counts the window's measured nights. */
data class RecoveryReadout(
    val night: RecoveryDay?,
    val restingBaseline: Baseline?,
    val hrvBaseline: Baseline?,
    val sleepBaseline: Baseline?,
    val status: RecoveryStatus,
    val nightsWithSignals: Int = 0
) {
    /** Last night's HRV against its baseline, in percent; null when either is missing. */
    val hrvDeltaPercent: Double?
        get() {
            val hrv = night?.hrvMs ?: return null
            val base = hrvBaseline?.takeIf { it.mean > 0 } ?: return null
            return (hrv - base.mean) / base.mean * 100.0
        }

    /** Last night's resting heart rate minus its baseline, in bpm. */
    val restingDelta: Double?
        get() {
            val rhr = night?.restingHeartRate ?: return null
            return rhr - (restingBaseline?.mean ?: return null)
        }
}

object RecoveryMath {
    /** The baseline window: four weeks of nights before the one being read. */
    const val BASELINE_NIGHTS = 28

    /** Fewer nights than this and there is no baseline yet. */
    const val MIN_BASELINE_NIGHTS = 7

    fun baseline(values: List<Double>): Baseline? {
        if (values.size < MIN_BASELINE_NIGHTS) return null
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        return Baseline(mean, sqrt(variance), values.size)
    }

    /**
     * Reads [night] against the [BASELINE_NIGHTS] nights before it. Outside one standard deviation
     * of the user's own norm: HRV below or resting heart rate above means STRAINED; HRV above with
     * resting heart rate not above means RESTED.
     */
    fun readout(days: List<RecoveryDay>, night: LocalDate): RecoveryReadout {
        val tonight = days.firstOrNull { it.date == night }
        val window = days.filter { it.date < night && it.date >= night.minusDays(BASELINE_NIGHTS.toLong()) }
        val resting = baseline(window.mapNotNull { it.restingHeartRate?.toDouble() })
        val hrv = baseline(window.mapNotNull { it.hrvMs })
        val sleep = baseline(window.mapNotNull { it.sleepMinutes?.toDouble() })

        val status = when {
            tonight == null || !tonight.hasSignals -> RecoveryStatus.NO_DATA
            (tonight.hrvMs == null || hrv == null) && (tonight.restingHeartRate == null || resting == null) ->
                RecoveryStatus.BUILDING_BASELINE
            else -> {
                val hrvLow = hrv != null && tonight.hrvMs != null && tonight.hrvMs < hrv.mean - hrv.sd
                val hrvHigh = hrv != null && tonight.hrvMs != null && tonight.hrvMs > hrv.mean + hrv.sd
                val restingHigh = resting != null && tonight.restingHeartRate != null &&
                    tonight.restingHeartRate > resting.mean + resting.sd
                val restingNotUp = resting == null || tonight.restingHeartRate == null ||
                    tonight.restingHeartRate <= resting.mean
                when {
                    hrvLow || restingHigh -> RecoveryStatus.STRAINED
                    hrvHigh && restingNotUp -> RecoveryStatus.RESTED
                    else -> RecoveryStatus.IN_RANGE
                }
            }
        }
        return RecoveryReadout(tonight, resting, hrv, sleep, status, window.count { it.hasSignals })
    }
}
