package cloud.wafflecommons.pixelbrainreader.data.usecase

import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository
import cloud.wafflecommons.pixelbrainreader.data.gamification.XpCalculator
import cloud.wafflecommons.pixelbrainreader.ui.utils.ConfettiType
import cloud.wafflecommons.pixelbrainreader.ui.utils.GlobalEffect
import cloud.wafflecommons.pixelbrainreader.ui.utils.UiEffectManager
import javax.inject.Inject

/** Grants a flat amount of XP (gratitude entries). */
class GrantXpUseCase @Inject constructor(
    private val gamificationRepository: GamificationRepository,
    private val uiEffectManager: UiEffectManager
) {
    suspend operator fun invoke(amount: Int) {
        var leveledUp = false
        gamificationRepository.updateState { currentState ->
            val profile = currentState.profile
            var xp = profile.currentXp + amount
            var level = profile.level
            var xpTarget = profile.xpToNextLevel

            // Same progression as the domain GrantXpUseCase: currentXp is the progress WITHIN the
            // level, so the threshold is subtracted on level-up. It used to be kept, leaving the XP
            // bar over-full and out of step with every other XP source.
            while (xp >= xpTarget) {
                xp -= xpTarget
                level++
                xpTarget = XpCalculator.getXpForNextLevel(level)
                leveledUp = true
            }

            currentState.copy(profile = profile.copy(level = level, currentXp = xp, xpToNextLevel = xpTarget))
        }
        // updateState already refreshes the widgets; celebrate once the new level is saved.
        if (leveledUp) {
            uiEffectManager.tryTriggerEffect(GlobalEffect.Confetti(ConfettiType.LEVEL_UP))
        }
    }
}
