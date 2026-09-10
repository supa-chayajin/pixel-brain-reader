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
            val frequency = chore.frequencyDays.takeIf { it > 0 } ?: 1

            // Days since last done. A chore without a readable completion date has never been done,
            // so it is due — it used to count as done today (clean) while the widgets listed it as due.
            val daysElapsed = runCatching { LocalDate.parse(chore.lastDoneDate) }.getOrNull()
                ?.let { lastDone -> ChronoUnit.DAYS.between(lastDone, today).coerceAtLeast(0) }
                ?: frequency.toLong()

            // Dirtiness as a percentage (can exceed 100f)
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
