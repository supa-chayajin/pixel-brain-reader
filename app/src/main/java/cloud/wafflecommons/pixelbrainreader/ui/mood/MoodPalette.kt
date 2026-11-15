package cloud.wafflecommons.pixelbrainreader.ui.mood

import androidx.compose.ui.graphics.Color

/**
 * The mood's colors (12.19, owner's M5): warm when low, green when high. One container and content
 * pair per score, for a light and a dark system theme; the text on each container stays above
 * 4.5:1 (WCAG AA).
 */
object MoodPalette {

    data class Tone(val container: Color, val content: Color)

    private val LIGHT = listOf(
        Tone(Color(0xFFFFB4A9), Color(0xFF5F1410)),
        Tone(Color(0xFFFFC58F), Color(0xFF5A2D00)),
        Tone(Color(0xFFFFE07A), Color(0xFF4A3B00)),
        Tone(Color(0xFFC5EB8E), Color(0xFF2B4400)),
        Tone(Color(0xFF8FE0A6), Color(0xFF00391C))
    )

    private val DARK = listOf(
        Tone(Color(0xFF8C1D18), Color(0xFFFFDAD6)),
        Tone(Color(0xFF7A3F00), Color(0xFFFFDCC2)),
        Tone(Color(0xFF665200), Color(0xFFFFE59A)),
        Tone(Color(0xFF3D5C10), Color(0xFFD9F7B0)),
        Tone(Color(0xFF1F6B3A), Color(0xFFB5F5C5))
    )

    /** The tone of [score], 1 (lowest) to 5 (highest); a score out of range takes the nearest end. */
    fun of(score: Int, dark: Boolean): Tone = (if (dark) DARK else LIGHT)[(score - 1).coerceIn(0, LIGHT.lastIndex)]
}
