package cloud.wafflecommons.pixelbrainreader.ui.daily

import org.junit.Assert.assertEquals
import org.junit.Test

/** How old a headline reads on the tech watch. */
class WatchAgeTest {
    private val now = 10L * 24 * 3_600_000

    @Test
    fun `an age reads in the largest unit that fits`() {
        assertEquals("Just now", ageOf(now - 30_000, now))
        assertEquals("40 min ago", ageOf(now - 40 * 60_000L, now))
        assertEquals("5 h ago", ageOf(now - 5 * 3_600_000L, now))
        assertEquals("Yesterday", ageOf(now - 30 * 3_600_000L, now))
        assertEquals("3 days ago", ageOf(now - 3 * 24 * 3_600_000L, now))
        assertEquals("Just now", ageOf(now + 60_000, now)) // a feed dated in the future
    }
}
