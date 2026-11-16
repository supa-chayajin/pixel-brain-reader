@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.graphics.shapes.RoundedPolygon
import kotlin.random.Random

/**
 * The open tab's emblem (13.2.1, the owner's picks of 2026-11-16). Each time a tab opens, its emblem
 * takes a shape drawn at random from [TabEmblems.shapes], never the one shown just before, so even
 * the same tab changes shape from one visit to the next. Its colour is the tone of the tab's place
 * in the navigation (primary, secondary, tertiary in turn), so any order of the tabs reads as a
 * sequence. The tab's page header shows the same emblem ([LocalOpenTabEmblem]). Daily keeps its
 * turning sun.
 */
@Immutable
data class OpenTabEmblem(
    /** The tab's name, which its page header uses as its emblem key. */
    val label: String,
    /** Its place among the tabs as shown, 0 for the first. */
    val place: Int,
    /** The drawn shape: an index into [TabEmblems.shapes]. */
    val shape: Int
)

/** The open tab's emblem, for its page's header: null on Daily and on pages that aren't a tab. */
val LocalOpenTabEmblem = compositionLocalOf<OpenTabEmblem?> { null }

object TabEmblems {
    /** The rounded expressive shapes an emblem is drawn from, each leaving room for its icon. */
    val shapes: List<RoundedPolygon> by lazy {
        listOf(
            MaterialShapes.Cookie4Sided,
            MaterialShapes.Cookie6Sided,
            MaterialShapes.Cookie7Sided,
            MaterialShapes.Cookie9Sided,
            MaterialShapes.Cookie12Sided,
            MaterialShapes.Clover4Leaf,
            MaterialShapes.Clover8Leaf,
            MaterialShapes.Sunny,
            MaterialShapes.SoftBurst,
            MaterialShapes.Flower,
            MaterialShapes.Puffy,
            MaterialShapes.PuffyDiamond,
            MaterialShapes.Gem,
            MaterialShapes.Pentagon
        )
    }

    /** How many tones the places go through: the scheme's primary, secondary and tertiary. */
    private const val TONES = 3

    /** The tone of [place]: 0 primary, 1 secondary, 2 tertiary, then again from primary. */
    fun toneIndex(place: Int): Int = place.mod(TONES)

    /** A shape among [count] for the next opening: any but [last], the one shown just before. */
    fun nextShape(last: Int?, count: Int, random: Random): Int {
        if (count <= 1) return 0
        if (last == null || last !in 0 until count) return random.nextInt(count)
        val pick = random.nextInt(count - 1)
        return if (pick >= last) pick + 1 else pick
    }
}

/** The tone of [place]: the device scheme's primary, secondary and tertiary containers in turn. */
@Composable
fun placeTone(place: Int): Tone = MaterialTheme.colorScheme.accentTones()[TabEmblems.toneIndex(place)]

/** Draws each opening's shape and remembers it, so the next opening never repeats it. */
class TabShapeDraws(private val random: Random = Random.Default) {
    var last: Int? = null
        private set

    fun next(count: Int = TabEmblems.shapes.size): Int = TabEmblems.nextShape(last, count, random).also { last = it }
}
