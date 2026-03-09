package cloud.wafflecommons.pixelbrainreader.ui.homeos

import androidx.compose.runtime.Immutable
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChoreEntity

enum class StatusColor {
    GREEN,  // Clean (0% - 70% dirtiness)
    YELLOW, // Warning (71% - 99% dirtiness)
    RED     // Critical (>= 100%, meaning overdue)
}

@Immutable
data class ChoreUiModel(
    val entity: ChoreEntity,
    val daysElapsed: Long,
    val dirtinessPercentage: Float,
    val statusColor: StatusColor
)
