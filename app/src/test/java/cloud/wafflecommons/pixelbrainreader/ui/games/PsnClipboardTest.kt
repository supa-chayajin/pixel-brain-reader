package cloud.wafflecommons.pixelbrainreader.ui.games

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 13.4 (the audit's npsso-left-on-clipboard): after Connect, the clipboard is cleared only while it
 * still holds the NPSSO typed, read the way Connect reads a paste; any other clip stays.
 */
class PsnClipboardTest {

    // Invented values in the NPSSO's shape (64 letters and digits): never a real one.
    private val npsso = "Fake0npsso0value0for0tests0only0Fake0npsso0value0for0tests0only"
    private val another = "Another0fake0npsso0value0for0tests0Another0fake0npsso0value0abc"

    @Test
    fun `a clip holding the typed NPSSO is cleared`() {
        assertTrue(clipHoldsNpsso(npsso, npsso))
        // Copied with a stray space or line break around it, as a browser's selection can.
        assertTrue(clipHoldsNpsso("  $npsso\n", npsso))
        assertTrue(clipHoldsNpsso(npsso, " $npsso "))
    }

    @Test
    fun `a clip holding other text is kept`() {
        assertFalse(clipHoldsNpsso(null, npsso))
        assertFalse(clipHoldsNpsso("", npsso))
        assertFalse(clipHoldsNpsso("A shopping list: bread, milk", npsso))
        // Another NPSSO-shaped value: not the one typed, so not the app's to clear.
        assertFalse(clipHoldsNpsso(another, npsso))
        // Nothing typed that reads as an NPSSO: nothing to compare with, the clip stays.
        assertFalse(clipHoldsNpsso(npsso, ""))
        assertFalse(clipHoldsNpsso("not an npsso", "not an npsso"))
    }

    @Test
    fun `the page's whole answer and the value alone match`() {
        val answer = "{\"npsso\":\"$npsso\"}"
        assertTrue(clipHoldsNpsso(answer, npsso))
        assertTrue(clipHoldsNpsso(npsso, answer))
        assertTrue(clipHoldsNpsso(answer, answer))
        assertFalse(clipHoldsNpsso("{\"npsso\":\"$another\"}", npsso))
    }
}
