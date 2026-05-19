package cloud.wafflecommons.pixelbrainreader.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * App-wide semantic accents that aren't part of Material's standard color scheme.
 *
 * These are NOT theme-adaptive on purpose: success-green should read as "green"
 * in both light and dark mode, and warning-amber should always look like a
 * warning. They're mid-saturation hex values picked to remain legible on both
 * the dark (#000) and light (#FBFDF5) surfaces this app uses.
 *
 * Centralized here so a future theme refresh tweaks one place instead of
 * chasing the same hex across ChoreDashboard, HeroCard, LifeOSComponents, etc.
 *
 * For pure error/destructive states use [androidx.compose.material3.ColorScheme.error]
 * instead — that one IS theme-adaptive and Material handles the contrast pair.
 */
object SemanticPalette {
    /** Positive / completed / healthy state. */
    val Success = Color(0xFF4CAF50)

    /** Caution / approaching-due / non-critical attention. */
    val Warning = Color(0xFFFFC107)

    /** XP / level / achievement gold. */
    val XpGold = Color(0xFFFFD700)

    /** Habit streak accent (the "you're on fire" orange). */
    val StreakAccent = Color(0xFFFF9800)
}

/**
 * Stable color palette for data visualizations (LifeStats, MoodTrends, etc).
 *
 * Per Material's data-viz guidance, chart series should keep stable identity
 * across light/dark mode so legend ↔ data binding stays cognitive-stable.
 * These are NOT routed through ColorScheme on purpose.
 *
 * Add new series here when adding a new chart; never inline a hex in a feature file.
 */
object ChartPalette {
    // Daily activity series (used for the LifeStats triple-ring)
    val Tasks = Color(0xFFE91E63)        // Pink
    val Habits = Color(0xFF8BC34A)       // Lime
    val Chores = Color(0xFF03A9F4)       // Cyan

    // Health metrics
    val Calories = Color(0xFFFFA726)     // Amber-orange
    val Meditation = Color(0xFF29B6F6)   // Sky blue
    val HeartRate = Color(0xFFFF5252)    // Vivid red
    val Sleep = Color(0xFF673AB7)        // Deep purple

    // Activity progress
    val Distance = Color(0xFF4CAF50)     // Green (matches Success — distance = positive progress)
    val ActiveMinutes = Color(0xFFFF9800) // Orange (matches StreakAccent — motion = momentum)
    val Completion = Color(0xFF4CAF50)   // Green (completion = positive)
}
