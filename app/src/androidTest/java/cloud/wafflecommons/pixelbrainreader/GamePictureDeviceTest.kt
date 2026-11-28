package cloud.wafflecommons.pixelbrainreader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.wafflecommons.pixelbrainreader.data.games.GameArtRules
import cloud.wafflecommons.pixelbrainreader.ui.components.toneFor
import cloud.wafflecommons.pixelbrainreader.ui.games.GamePicture
import cloud.wafflecommons.pixelbrainreader.ui.games.GamePictureSizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * A game picture without a picture (13.4, GP3): its initials in its own expressive shape and tone.
 * Only the initials draw here (no address, no file), so nothing loads and no Hilt graph is asked
 * for; no vault, no app state is touched. Emulator only.
 */
@RunWith(AndroidJUnit4::class)
class GamePictureDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun initialsDrawInTheGamesShapeWhenThereIsNoPicture() {
        val art = GameArtRules.initialsOnly("Glass Tower")
        var container = Color.Unspecified
        compose.setContent {
            MaterialTheme {
                container = toneFor(art.key).container
                // A backdrop of a colour no tone has: it shows wherever the shape leaves the square.
                Box(Modifier.safeDrawingPadding().size(120.dp).background(BACKDROP)) {
                    GamePicture(art, Modifier.size(120.dp).testTag(PICTURE))
                }
            }
        }

        compose.onNodeWithText("GT").assertIsDisplayed()
        compose.onNodeWithContentDescription("Picture of Glass Tower").assertIsDisplayed()

        val pixels = compose.onNodeWithTag(PICTURE).captureToImage().toPixelMap()
        var tone = 0
        var behind = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val pixel = pixels[x, y]
                when {
                    near(pixel, container) -> tone++
                    near(pixel, BACKDROP) -> behind++
                }
            }
        }
        val total = pixels.width * pixels.height
        // Mostly the game's tone, and the corners clipped away: a shape, not a square.
        assertTrue("tone share ${tone * 100 / total} %", tone > total * 0.4)
        assertTrue("backdrop share ${behind * 100 / total} %", behind > total * 0.03)
    }

    @Test
    fun aLongTitleKeepsTwoInitials() {
        val art = GameArtRules.initialsOnly("The Long Road to Lantern Bay and the Tin Orchard Beyond")
        assertEquals("TL", art.initials)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.safeDrawingPadding()) {
                    GamePicture(art, Modifier.size(GamePictureSizes.Row).testTag(SMALL))
                    GamePicture(art, Modifier.size(GamePictureSizes.Page).testTag(LARGE))
                }
            }
        }

        listOf(SMALL, LARGE).forEach { tag ->
            val letters = compose.onNode(hasText("TL") and hasAnyAncestor(hasTestTag(tag)))
            letters.assertIsDisplayed()
            // Two letters on one line, inside the picture at both sizes.
            val inner = letters.getUnclippedBoundsInRoot()
            val outer = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$tag: $inner outside $outer", inside(inner, outer))
        }
    }

    private fun inside(inner: DpRect, outer: DpRect): Boolean =
        inner.left >= outer.left && inner.top >= outer.top && inner.right <= outer.right && inner.bottom <= outer.bottom

    private fun near(a: Color, b: Color): Boolean =
        abs(a.red - b.red) < TOLERANCE && abs(a.green - b.green) < TOLERANCE && abs(a.blue - b.blue) < TOLERANCE

    private companion object {
        const val PICTURE = "picture"
        const val SMALL = "small"
        const val LARGE = "large"
        const val TOLERANCE = 0.03f
        val BACKDROP = Color(0xFFFF00FF)
    }
}
