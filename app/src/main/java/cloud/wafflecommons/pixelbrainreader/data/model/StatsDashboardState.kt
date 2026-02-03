package cloud.wafflecommons.pixelbrainreader.data.model

import java.time.Instant
import java.time.LocalDate

data class StatsDashboardState(
    val sleepHistory: List<DailyMetric<Double>> = emptyList(), // Value in Hours
    val stepHistory: List<DailyMetric<Long>> = emptyList(),
    val weeklyCorrelation: List<CorrelationPoint> = emptyList(),
    val todayCorrelation: List<IntradayPoint> = emptyList()
)

data class DailyMetric<T>(
    val date: LocalDate,
    val value: T,
    val isGoalMet: Boolean = false
)

data class CorrelationPoint(
    val date: LocalDate,
    val avgBpm: Double,
    val moodScore: Double // 1.0 to 5.0
)

data class IntradayPoint(
    val timestamp: Instant,
    val bpm: Double? = null,
    val moodScore: Double? = null
)
