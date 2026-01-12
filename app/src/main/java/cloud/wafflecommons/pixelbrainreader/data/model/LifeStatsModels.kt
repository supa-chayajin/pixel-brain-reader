package cloud.wafflecommons.pixelbrainreader.data.model

enum class RpgAttribute {
    VIGOR, MIND, ENDURANCE, INTELLIGENCE, FAITH
}

data class RpgCharacter(
    val stats: Map<RpgAttribute, Float>, // Normalized 0.0 to 1.0
    val level: Int,
    val className: String // Display Title
)

object LifeStatsLogic {
    fun determineClass(stats: Map<RpgAttribute, Float>): String {
        // Filter out very low stats to avoid being "Classed" when barely playing
        val activeStats = stats.filter { it.value > 0.1f }
        
        if (activeStats.isEmpty()) return "Deprived"

        val sortedStats = activeStats.entries.sortedByDescending { it.value }
        val top1 = sortedStats[0].key
        // If only one stat is significant, double it up or just use it
        val top2 = if (sortedStats.size > 1) sortedStats[1].key else top1

        return when (setOf(top1, top2)) {
            setOf(RpgAttribute.INTELLIGENCE, RpgAttribute.VIGOR) -> "Battlemage Ops"
            setOf(RpgAttribute.FAITH, RpgAttribute.ENDURANCE) -> "Paladin du Foyer"
            setOf(RpgAttribute.MIND, RpgAttribute.INTELLIGENCE) -> "Grand Archiviste"
            setOf(RpgAttribute.VIGOR, RpgAttribute.ENDURANCE) -> "Vanguard"
            setOf(RpgAttribute.MIND, RpgAttribute.FAITH) -> "Oracle"
            setOf(RpgAttribute.VIGOR, RpgAttribute.FAITH) -> "Justicar"
            setOf(RpgAttribute.INTELLIGENCE, RpgAttribute.ENDURANCE) -> "Strategist"
            setOf(RpgAttribute.MIND, RpgAttribute.VIGOR) -> "Monk"
            setOf(RpgAttribute.INTELLIGENCE, RpgAttribute.FAITH) -> "Scholar"
            setOf(RpgAttribute.MIND, RpgAttribute.ENDURANCE) -> "Survivor"
            else -> "Awakened Soul"
        }
    }
    
    fun getAttributeName(attr: RpgAttribute): String {
        return when(attr) {
            RpgAttribute.VIGOR -> "VIG"
            RpgAttribute.MIND -> "MND"
            RpgAttribute.ENDURANCE -> "END"
            RpgAttribute.INTELLIGENCE -> "INT"
            RpgAttribute.FAITH -> "FTH"
        }
    }
}
