package cloud.wafflecommons.pixelbrainreader.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's floating bar hides while a page scrolls down and comes back as it scrolls up (13.2). */
class NavBarHideOnScrollTest {

    private val bar = NavBarHideOnScroll(thresholdPx = 24f)

    @Test
    fun `a short scroll down keeps the bar`() {
        bar.onMoved(-10f)
        assertFalse(bar.hidden)
    }

    @Test
    fun `scrolling down past the threshold hides it and scrolling back up shows it`() {
        bar.onMoved(-10f)
        bar.onMoved(-20f)
        assertTrue(bar.hidden)
        bar.onMoved(10f)
        assertTrue("a change of direction starts the count again", bar.hidden)
        bar.onMoved(20f)
        assertFalse(bar.hidden)
    }

    @Test
    fun `a new page shows it at once`() {
        bar.onMoved(-40f)
        assertTrue(bar.hidden)
        bar.show()
        assertFalse(bar.hidden)
    }

    @Test
    fun `content that doesn't move changes nothing`() {
        bar.onMoved(-40f)
        bar.onMoved(0f)
        assertTrue(bar.hidden)
    }
}
