package cloud.wafflecommons.pixelbrainreader.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class ChronoBiometricInsight(
    val title: String,
    val description: String,
    val iconType: String, // "sleep", "steps", "heart"
    val scoreDelta: Int
)

@Immutable
data class ChronoBiometricReport(
    val analyzedDays: Int,
    val avgSleepMinutes: Long,
    val avgSteps: Long,
    val insights: List<ChronoBiometricInsight> = emptyList()
)
