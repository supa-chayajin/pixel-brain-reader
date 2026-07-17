package cloud.wafflecommons.pixelbrainreader.widget.data

import android.graphics.Bitmap

/**
 * UI-ready state for the Companion widget: the raw glanceable numbers from [WidgetDataSnapshot]
 * plus the two pre-rasterized bitmaps (avatar + vitality graph) Glance can render directly.
 */
data class CompanionWidgetState(
    val heroClass: String = "Novice",
    val currentLevel: Int = 1,
    val currentXp: Int = 0,
    val maxXp: Int = 100,
    val lastMoodEmoji: String? = null,
    val moodEntryCount: Int = 0,
    val stepsCount: String = "0",
    val sleepDuration: String = "0h",
    val graphBitmap: Bitmap? = null,
    val avatarBitmap: Bitmap? = null,
    val isLoading: Boolean = false,
    // Enriched life-OS + health fields (populated from the snapshot).
    val habitsDone: Int = 0,
    val habitsTotal: Int = 0,
    val tasksDone: Int = 0,
    val tasksTotal: Int = 0,
    val choresDue: Int = 0,
    val activeMinutes: Long = 0,
    val caloriesBurned: Int = 0,
    val distanceKm: Double = 0.0,
    val hydrationMl: Int = 0,
    val mindfulnessMinutes: Long = 0,
    val avgHeartRate: Int = 0
)
