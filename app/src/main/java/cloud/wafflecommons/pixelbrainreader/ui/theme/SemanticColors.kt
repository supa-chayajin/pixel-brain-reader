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

/**
 * Pastel swatches offered in the quick-capture note colour picker. Fixed
 * (non-theme-adaptive) on purpose so a saved note keeps its colour across
 * light/dark. Centralized here instead of inlined in DailyNoteScreen.
 */
object NotePastels {
    val Red = Color(0xFFFFB4AB)
    val Blue = Color(0xFFC2E7FF)
    val Green = Color(0xFFD3EBCD)
    val Purple = Color(0xFFF3E5F5)

    /** The picker's fixed swatches (a theme surface default is prepended at the call site). */
    val swatches = listOf(Red, Blue, Green, Purple)
}

/**
 * Identity accents used to color-code chore ROOMS (mirrors how habits carry a per-item colour).
 * A room's colour is assigned randomly from these on creation and persisted as a hex string in
 * [cloud.wafflecommons.pixelbrainreader.data.local.entity.HomeRoomEntity.color] (so it round-trips
 * through the vault). Non-theme-adaptive on purpose so a room keeps its identity across light/dark.
 *
 * These are stored as hex strings (not Compose [Color]) to match the entity's `color: String` field.
 */
object RoomPalette {
    /** The neutral placeholder a room gets before a colour is assigned. */
    const val DEFAULT_HEX = "#808080"

    val hexSwatches: List<String> = listOf(
        "#EF5350", // red
        "#EC407A", // pink
        "#AB47BC", // purple
        "#5C6BC0", // indigo
        "#42A5F5", // blue
        "#26A69A", // teal
        "#66BB6A", // green
        "#9CCC65", // lime
        "#FFA726", // orange
        "#8D6E63"  // brown
    )

    /** A random swatch — assigned when a room is first created. */
    fun randomHex(): String = hexSwatches.random()

    /**
     * Resolve a room's display [Color]: use its stored hex when it's a real (non-default, parseable)
     * value, otherwise fall back to a stable colour derived from the room name so legacy rooms that
     * predate colour assignment still read as distinct.
     */
    fun resolveColor(hex: String?, roomName: String): Color {
        if (!hex.isNullOrBlank() && hex != DEFAULT_HEX) {
            runCatching { return Color(android.graphics.Color.parseColor(hex)) }
        }
        return colorForName(roomName)
    }

    /** Stable colour derived from a seed (room name) — deterministic across runs. */
    fun colorForName(seed: String): Color {
        if (hexSwatches.isEmpty()) return Color(0xFF808080)
        val idx = ((seed.hashCode() % hexSwatches.size) + hexSwatches.size) % hexSwatches.size
        return runCatching { Color(android.graphics.Color.parseColor(hexSwatches[idx])) }
            .getOrDefault(Color(0xFF808080))
    }
}
