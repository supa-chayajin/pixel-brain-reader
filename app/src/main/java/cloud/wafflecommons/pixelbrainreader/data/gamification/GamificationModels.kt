package cloud.wafflecommons.pixelbrainreader.data.gamification

import kotlinx.serialization.Serializable

@Serializable
data class GamificationState(
    val profile: PlayerProfile = PlayerProfile(),
    val attributes: Map<Attribute, Int> = Attribute.entries.associateWith { 0 },
    val history: List<XpGainEntry> = emptyList()
)

@Serializable
data class PlayerProfile(
    val level: Int = 1,
    val currentXp: Double = 0.0,
    val xpToNextLevel: Double = 100.0,
    val characterClass: CharacterClass = CharacterClass.PEASANT,
    val avatarResName: String = "tiny_hero_peasant" // Default
)

@Serializable
data class XpGainEntry(
    val timestamp: Long,
    val amount: Double,
    val source: String,
    val attribute: Attribute?
)

enum class Attribute {
    VIG, // Physical Vigor
    MND, // Mental Focus
    END, // Endurance
    SOC, // Social
    CRE, // Creative
    INT, // Intellect
    FTH  // Faith/Spirit
}

enum class CharacterClass {
    PEASANT,
    WARRIOR, // High VIG
    MAGE,    // High MND/INT
    ROGUE,   // High END/SOC? mixed
    CLERIC,  // High FTH
    BARD     // High SOC/CRE
}

object XpCalculator {
    fun getXpForNextLevel(level: Int): Double {
        // Formula: 100 * (Level ^ 1.5)
        return 100.0 * Math.pow(level.toDouble(), 1.5)
    }

    fun calculateLevel(totalXp: Double): Int {
        // Inverse approximation or iterative check? 
        // Let's stick to state-based leveling: 
        // State holds current level and target. 
        // This helper might not be strictly needed if we update incrementally.
        // But for "Total XP" calculation:
        // Total XP to reach Level L = sigma(1..L-1) [100 * i^1.5]
        return 1
    }
}

object AttributeParser {
    // Regex to find (+XXX) where XXX is 3 uppercase letters
    private val regex = Regex("\\(\\+([A-Z]{3})\\)")

    fun parse(description: String): Attribute? {
        val match = regex.find(description)
        val tag = match?.groupValues?.get(1) ?: return null
        return try {
            Attribute.valueOf(tag)
        } catch (e: Exception) {
            null
        }
    }
}
