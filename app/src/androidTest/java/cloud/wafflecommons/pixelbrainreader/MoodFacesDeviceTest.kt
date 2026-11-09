package cloud.wafflecommons.pixelbrainreader

import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.wafflecommons.pixelbrainreader.data.mood.MoodEmojiSets
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every face of every mood emoji set draws as ONE glyph in the device's emoji font — no tofu box,
 * no keycap or variation-selector sequence split in two. The JVM tests can't see the font.
 */
@RunWith(AndroidJUnit4::class)
class MoodFacesDeviceTest {

    @Test
    fun everyFaceHasAGlyph() {
        val paint = Paint()
        val missing = MoodEmojiSets.ALL.flatMap { set ->
            set.faces.filterNot { paint.hasGlyph(it) }.map { "${set.id}: $it" }
        }
        assertTrue("faces without a glyph: $missing", missing.isEmpty())
    }
}
