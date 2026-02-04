package cloud.wafflecommons.pixelbrainreader.widget.data

import cloud.wafflecommons.pixelbrainreader.data.gamification.CharacterClass
import kotlinx.serialization.Serializable

@Serializable
data class WidgetDataSnapshot(
    val heroClass: CharacterClass = CharacterClass.PEASANT,
    val level: Int = 1,
    val currentXp: Int = 0,
    val xpToNext: Int = 100,
    val steps: String = "0",
    val sleep: String = "--:--",
    val lastMoodEmoji: String? = null,
    // Using specialized data classes for list items to ensure clean JSON serialization
    val chartHeartRate: List<SnapshotPoint> = emptyList(), 
    val chartMoods: List<SnapshotMood> = emptyList(),
    val avatarResName: String? = null,
    val lastUpdatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class SnapshotPoint(
    val timestamp: Long,
    val value: Double
)

@Serializable
data class SnapshotMood(
    val timestamp: Long,
    val score: Int
)
