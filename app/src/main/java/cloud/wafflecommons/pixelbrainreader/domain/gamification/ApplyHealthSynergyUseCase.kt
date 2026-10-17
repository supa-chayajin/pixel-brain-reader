package cloud.wafflecommons.pixelbrainreader.domain.gamification

import cloud.wafflecommons.pixelbrainreader.data.local.preferences.GamificationPreferences
import cloud.wafflecommons.pixelbrainreader.data.repository.HealthMetricsRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

class ApplyHealthSynergyUseCase @Inject constructor(
    private val gamificationPreferences: GamificationPreferences,
    private val grantXpUseCase: GrantXpUseCase,
    private val moodRepository: MoodRepository,
    private val healthMetricsRepository: HealthMetricsRepository
) {
    suspend operator fun invoke(date: LocalDate): Int = withContext(Dispatchers.IO) {
        val lastAppliedDate = gamificationPreferences.lastHealthSynergyAppliedDateFlow.first()

        val metrics = healthMetricsRepository.getMetrics(date) ?: return@withContext 0

        // Synergy Calculation
        val moods = moodRepository.getMoodFlow().first()
        val todayMoods = moods.filter { it.date == date.toString() }
        var avgMood = if (todayMoods.isNotEmpty()) todayMoods.map { it.score }.average().toFloat() else 0f
        
        if (todayMoods.isEmpty()) {
            val yesterdayMoods = moods.filter { it.date == date.minusDays(1).toString() }
            if (yesterdayMoods.isNotEmpty()) {
                avgMood = yesterdayMoods.map { it.score }.average().toFloat()
            }
        }
        
        val maxMood = 5f
        val activeMinsGoal = 30f // Default if not in preferences
        val activeMins = metrics.activeMinutes.toFloat()
        
        val synergy = if (avgMood > 0f) {
            ((avgMood / maxMood) * (activeMins / activeMinsGoal)) * 100f
        } else {
            0f // Pending or no mood
        }
        
        val finalSynergy = synergy.toInt().coerceIn(0, 100)

        if (lastAppliedDate != date.toString()) {
            val stepTarget = gamificationPreferences.stepTargetFlow.first()
            val sleepMinMinutes = gamificationPreferences.sleepMinMinutesFlow.first()
    
            var applied = false
    
            if (metrics.steps >= stepTarget) {
                grantXpUseCase.executeCustom(
                    attribute = cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute.VIG,
                    xpBase = 50.0,
                    sourceId = "Health Synergy: Step Goal Reached"
                )
                applied = true
            }
    
            if (metrics.sleepDurationMinutes >= sleepMinMinutes && sleepMinMinutes > 0) {
                grantXpUseCase.executeCustom(
                    attribute = cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute.MND,
                    xpBase = 25.0,
                    sourceId = "Health Synergy: Sleep Goal Reached"
                )
                applied = true
            }
            
            // Apply XP for high Synergy
            if (finalSynergy >= 80) {
                grantXpUseCase.executeCustom(
                    attribute = cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute.MND,
                    xpBase = 30.0,
                    sourceId = "High Health/Mood Synergy"
                )
                applied = true
            }
    
            if (applied) {
                gamificationPreferences.setLastHealthSynergyAppliedDate(date.toString())
            }
        }
        
        return@withContext finalSynergy
    }
}
