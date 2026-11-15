package cloud.wafflecommons.pixelbrainreader.ui.mood

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodTagCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The check-in sheet's colors (12.19, owner's M5): readable in light and dark, one tone per mood. */
class MoodPaletteTest {

    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    @Test
    fun `each mood has its own tone, and its text stays readable in light and dark`() {
        for (dark in listOf(false, true)) {
            val tones = (1..5).map { MoodPalette.of(it, dark) }
            assertEquals(5, tones.map { it.container }.toSet().size)
            tones.forEachIndexed { i, tone ->
                assertTrue("score ${i + 1}, dark=$dark", contrast(tone.container, tone.content) >= 4.5f)
            }
        }
        assertNotEquals(MoodPalette.of(3, dark = false), MoodPalette.of(3, dark = true))
    }

    @Test
    fun `a score out of range takes the nearest end`() {
        assertEquals(MoodPalette.of(1, dark = false), MoodPalette.of(0, dark = false))
        assertEquals(MoodPalette.of(5, dark = true), MoodPalette.of(9, dark = true))
    }

    @Test
    fun `in a dark theme a light container gives way to a dark card that stays readable`() {
        // The API 37 emulator's dynamic dark scheme handed out a light tertiary container.
        val scheme = darkColorScheme(tertiaryContainer = Color(0xFFEADDFF), onTertiaryContainer = Color(0xFF21005D))
        val colors = MoodTagCategories.colors(MoodTagCategory.ACTIVITIES, scheme)

        assertTrue(colors.container.luminance() < 0.4f)
        assertTrue(contrast(colors.container, colors.content) >= 4.5f)
        // A dark container is kept as the theme gives it.
        val places = MoodTagCategories.colors(MoodTagCategory.PLACES, darkColorScheme())
        assertEquals(darkColorScheme().primaryContainer, places.container)
    }

    @Test
    fun `the People card's own pink stays readable in light and dark`() {
        for (scheme in listOf(lightColorScheme(), darkColorScheme())) {
            val colors = MoodTagCategories.colors(MoodTagCategory.PEOPLE, scheme)
            assertTrue(contrast(colors.container, colors.content) >= 4.5f)
            assertTrue(contrast(colors.strong, colors.onStrong) >= 4.5f)
        }
        assertNotEquals(
            MoodTagCategories.colors(MoodTagCategory.PEOPLE, lightColorScheme()).container,
            MoodTagCategories.colors(MoodTagCategory.PEOPLE, darkColorScheme()).container
        )
    }
}
