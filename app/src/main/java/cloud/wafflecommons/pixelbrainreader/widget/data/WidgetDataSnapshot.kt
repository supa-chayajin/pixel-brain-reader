package cloud.wafflecommons.pixelbrainreader.widget.data

import cloud.wafflecommons.pixelbrainreader.data.gamification.CharacterClass
import kotlinx.serialization.Serializable

/**
 * The pre-rendered, glanceable snapshot the non-interactive widgets (Companion, Health) read from
 * `filesDir/widget_snapshot.json`. Built off the main thread by
 * [cloud.wafflecommons.pixelbrainreader.widget.manager.WidgetSnapshotManager] so the widget's
 * `provideGlance` never has to touch Health Connect (slow) or aggregate the whole day itself.
 *
 * EVERY field has a default: the file is decoded with `Json { ignoreUnknownKeys = true }`, so
 * adding fields here is backward compatible with a snapshot written by an older app version.
 */
@Serializable
data class WidgetDataSnapshot(
    // --- Gamification / hero ---
    val heroClass: CharacterClass = CharacterClass.PEASANT,
    val level: Int = 1,
    val currentXp: Int = 0,
    val xpToNext: Int = 100,
    val avatarResName: String? = null,

    // --- Mood ---
    val lastMoodEmoji: String? = null,
    val moodEntryCount: Int = 0,

    // --- Health (glanceable strings + raw for grids) ---
    val steps: String = "0",
    val stepsRaw: Long = 0,
    val stepGoal: Long = 10_000,
    val sleep: String = "--:--",
    val distanceKm: Double = 0.0,
    val activeMinutes: Long = 0,
    val caloriesBurned: Int = 0,
    val hydrationMl: Int = 0,
    val mindfulnessMinutes: Long = 0,
    val avgHeartRate: Int = 0,
    val weightKg: Double = 0.0,

    // --- Life-OS progress rings ---
    val habitsDone: Int = 0,
    val habitsTotal: Int = 0,
    val tasksDone: Int = 0,
    val tasksTotal: Int = 0,
    val choresDue: Int = 0,

    // --- Vitality chart (heart rate line + mood dots over the last 6h) ---
    val chartHeartRate: List<SnapshotPoint> = emptyList(),
    val chartMoods: List<SnapshotMood> = emptyList(),

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
