package cloud.wafflecommons.pixelbrainreader.domain.homeos

import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChoreEntity
import cloud.wafflecommons.pixelbrainreader.ui.homeos.ChoreUiModel
import cloud.wafflecommons.pixelbrainreader.ui.homeos.StatusColor
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

class CalculateChoreEntropyUseCase @Inject constructor() {
    operator fun invoke(chores: List<ChoreEntity>): List<ChoreUiModel> {
        val today = LocalDate.now()
        
        return chores.map { chore ->
            val lastDone = try {
                LocalDate.parse(chore.lastDoneDate)
            } catch (e: Exception) {
                today // Fallback if parsing fails
            }
            
            // Days since last done
            val daysElapsed = ChronoUnit.DAYS.between(lastDone, today).coerceAtLeast(0)
            
            // Dirtiness as a percentage (can exceed 100f)
            val frequency = chore.frequencyDays.takeIf { it > 0 } ?: 1
            val dirtinessPercentage = (daysElapsed.toFloat() / frequency.toFloat()) * 100f
            
            // Derive color status based on thresholds
            val statusColor = when {
                dirtinessPercentage >= 100f -> StatusColor.RED
                dirtinessPercentage >= 71f -> StatusColor.YELLOW
                else -> StatusColor.GREEN
            }
            
            ChoreUiModel(
                entity = chore,
                daysElapsed = daysElapsed,
                dirtinessPercentage = dirtinessPercentage,
                statusColor = statusColor
            )
        }.sortedByDescending { it.dirtinessPercentage } // Global sort, though DB groups it later 
    }
}
