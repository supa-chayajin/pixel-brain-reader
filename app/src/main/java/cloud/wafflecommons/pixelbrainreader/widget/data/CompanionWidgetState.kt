package cloud.wafflecommons.pixelbrainreader.widget.data

import android.graphics.Bitmap

data class CompanionWidgetState(
    val heroClass: String = "Novice",
    val currentLevel: Int = 1,
    val currentXp: Int = 0,
    val maxXp: Int = 100,
    val lastMoodEmoji: String? = null,
    val stepsCount: String = "0",
    val sleepDuration: String = "0h",
    val graphBitmap: Bitmap? = null,
    val avatarBitmap: Bitmap? = null,
    val isLoading: Boolean = false
)
