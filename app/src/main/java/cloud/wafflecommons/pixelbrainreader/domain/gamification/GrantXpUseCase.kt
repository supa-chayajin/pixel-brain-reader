package cloud.wafflecommons.pixelbrainreader.domain.gamification

import cloud.wafflecommons.pixelbrainreader.data.gamification.*
import cloud.wafflecommons.pixelbrainreader.data.model.HabitType
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import javax.inject.Inject

enum class XpActionType {
    HABIT_DONE,
    TASK_DONE,
    MOOD_LOGGED
}

class GrantXpUseCase @Inject constructor(
    private val gamificationRepository: GamificationRepository,
    private val habitRepository: HabitRepository
) {

    
    // Correct Implementation Structure
    suspend fun execute(
        sourceId: String,
        actionType: XpActionType,
        value: Double = 1.0
    ) {
        // 1. Prepare Data (IO)
        var xpBase = 0.0
        var attrBonus = 1
        var targetAttribute: Attribute? = null
        
        when (actionType) {
            XpActionType.HABIT_DONE -> {
                val configs = habitRepository.getHabitConfigs() // Suspend IO
                val habit = configs.find { it.id == sourceId }
                if (habit != null) {
                    targetAttribute = AttributeParser.parse(habit.description)
                    
                    if (habit.type == HabitType.MEASURABLE) {
                        if (value >= habit.targetValue) {
                            xpBase = 30.0
                            attrBonus = 2
                        } else {
                            xpBase = 10.0 // Partial
                            attrBonus = 1
                        }
                    } else {
                        xpBase = 15.0
                        attrBonus = 1
                    }
                }
            }
            XpActionType.TASK_DONE -> {
                xpBase = 20.0
                targetAttribute = Attribute.INT // Default
            }
            XpActionType.MOOD_LOGGED -> {
                xpBase = 5.0
                targetAttribute = Attribute.MND
            }
        }
        
        if (xpBase <= 0) return

        // 2. Atomic Update
        gamificationRepository.updateState { state ->
            // Update Attribute
            val newAttributes = state.attributes.toMutableMap()
            if (targetAttribute != null) {
                val currentAttrVal = newAttributes[targetAttribute] ?: 0
                newAttributes[targetAttribute] = currentAttrVal + attrBonus
            }
            
            // Update XP
            val currentProfile = state.profile
            var newXp = currentProfile.currentXp + xpBase
            var newLevel = currentProfile.level
            var newXpTarget = currentProfile.xpToNextLevel
            
            // Level Up Check
            if (newXp >= newXpTarget) {
                newXp -= newXpTarget
                newLevel++
                newXpTarget = XpCalculator.getXpForNextLevel(newLevel)
            }
            
            // Update Class (Simple Logic: Highest Attribute)
            val bestAttr = newAttributes.maxByOrNull { it.value }?.key
            val newClass = when(bestAttr) {
                Attribute.VIG -> CharacterClass.WARRIOR
                Attribute.MND -> CharacterClass.MAGE
                Attribute.INT -> CharacterClass.MAGE 
                Attribute.FTH -> CharacterClass.CLERIC
                Attribute.SOC -> CharacterClass.BARD
                Attribute.CRE -> CharacterClass.BARD
                else -> if (newLevel > 1) CharacterClass.WARRIOR else CharacterClass.PEASANT
            }
            
            val newProfile = currentProfile.copy(
                level = newLevel,
                currentXp = newXp,
                xpToNextLevel = newXpTarget,
                characterClass = newClass,
                avatarResName = getAvatarForClass(newClass)
            )
            
            // History
            val historyEntry = XpGainEntry(
                timestamp = System.currentTimeMillis(),
                amount = xpBase,
                source = sourceId,
                attribute = targetAttribute
            )
            
            state.copy(
                profile = newProfile,
                attributes = newAttributes,
                history = (state.history + historyEntry).takeLast(50) // Keep last 50
            )
        }
    }

    suspend fun executeCustom(
        attribute: Attribute,
        xpBase: Double,
        sourceId: String
    ) {
        if (xpBase < 0) return

        gamificationRepository.updateState { state ->
            val newAttributes = state.attributes.toMutableMap()
            val currentAttrVal = newAttributes[attribute] ?: 0
            newAttributes[attribute] = currentAttrVal + 1 // Custom stat bonus

            val currentProfile = state.profile
            var newXp = currentProfile.currentXp + xpBase
            var newLevel = currentProfile.level
            var newXpTarget = currentProfile.xpToNextLevel

            if (newXp >= newXpTarget) {
                newXp -= newXpTarget
                newLevel++
                newXpTarget = XpCalculator.getXpForNextLevel(newLevel)
            }

            val bestAttr = newAttributes.maxByOrNull { it.value }?.key
            val newClass = when(bestAttr) {
                Attribute.VIG -> CharacterClass.WARRIOR
                Attribute.MND -> CharacterClass.MAGE
                Attribute.INT -> CharacterClass.MAGE 
                Attribute.FTH -> CharacterClass.CLERIC
                Attribute.SOC -> CharacterClass.BARD
                Attribute.CRE -> CharacterClass.BARD
                else -> if (newLevel > 1) CharacterClass.WARRIOR else CharacterClass.PEASANT
            }

            val newProfile = currentProfile.copy(
                level = newLevel,
                currentXp = newXp,
                xpToNextLevel = newXpTarget,
                characterClass = newClass,
                avatarResName = getAvatarForClass(newClass)
            )

            val historyEntry = XpGainEntry(
                timestamp = System.currentTimeMillis(),
                amount = xpBase,
                source = sourceId,
                attribute = attribute
            )

            state.copy(
                profile = newProfile,
                attributes = newAttributes,
                history = (state.history + historyEntry).takeLast(50)
            )
        }
    }

    
    private fun getAvatarForClass(cls: CharacterClass): String {
        return when(cls) {
            CharacterClass.WARRIOR -> "tiny_hero_knight"
            CharacterClass.MAGE -> "tiny_hero_mage"
            CharacterClass.ROGUE -> "tiny_hero_rogue"
            CharacterClass.CLERIC -> "tiny_hero_cleric"
            CharacterClass.BARD -> "tiny_hero_bard"
            CharacterClass.PEASANT -> "tiny_hero_peasant"
        }
    }
}
