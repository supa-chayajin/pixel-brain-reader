package cloud.wafflecommons.pixelbrainreader.data.usecase

import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository
import cloud.wafflecommons.pixelbrainreader.ui.utils.ConfettiType
import cloud.wafflecommons.pixelbrainreader.ui.utils.GlobalEffect
import cloud.wafflecommons.pixelbrainreader.ui.utils.UiEffectManager
import javax.inject.Inject

class GrantXpUseCase @Inject constructor(
    private val gamificationRepository: GamificationRepository,
    private val uiEffectManager: UiEffectManager,
    private val widgetUpdateManager: cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetUpdateManager
) {
    suspend operator fun invoke(amount: Int) {
        gamificationRepository.updateState { currentState ->
            val profile = currentState.profile
            var newProfile = profile.copy(currentXp = profile.currentXp + amount)
            
            // Level Up Check
            // Assuming xpToNextLevel is the threshold.
            // If we exceed it, we level up.
            // To ensure we don't get stuck, we increase the threshold.
            // Using a simple multiplier if XpCalculator not accessible or complicated.
            // Let's try to match existing logic: oldLevel = xp / 100.
            // But we should respect the model fields if they exist.
            
            if (newProfile.currentXp >= newProfile.xpToNextLevel) {
                 val nextLevel = newProfile.level + 1
                 // Simple exponential curve: 100 * level^1.5
                 val nextThreshold = 100.0 * Math.pow(nextLevel.toDouble(), 1.5)
                 
                 newProfile = newProfile.copy(
                     level = nextLevel,
                     xpToNextLevel = nextThreshold
                 )
                 uiEffectManager.tryTriggerEffect(GlobalEffect.Confetti(ConfettiType.LEVEL_UP))
            }
            
            // Trigger Widget Update
            widgetUpdateManager.triggerUpdate()
            
            currentState.copy(profile = newProfile)
        }
    }
}
