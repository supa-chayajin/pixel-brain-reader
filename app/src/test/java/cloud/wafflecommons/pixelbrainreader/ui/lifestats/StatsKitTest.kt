package cloud.wafflecommons.pixelbrainreader.ui.lifestats

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsKitTest {

    private val lightCard = Color(0xFFF3EDF7)
    private val darkCard = Color(0xFF211F26)

    @Test
    fun `a chart color too pale for its card is darkened until it reads`() {
        val lime = Color(0xFF8BC34A)
        assertTrue(contrast(lime, lightCard) < 3f)
        assertTrue(contrast(readableOn(lime, lightCard), lightCard) >= 3f)
    }

    @Test
    fun `a chart color too dark for a dark card is lightened, one that reads is kept`() {
        val purple = Color(0xFF673AB7)
        assertTrue(contrast(purple, darkCard) < 3f)
        assertTrue(contrast(readableOn(purple, darkCard), darkCard) >= 3f)
        assertEquals(purple, readableOn(purple, lightCard))
    }
}
