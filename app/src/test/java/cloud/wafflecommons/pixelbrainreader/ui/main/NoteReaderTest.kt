package cloud.wafflecommons.pixelbrainreader.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteReaderTest {

    @Test
    fun `the outline shows headings as read, not as written`() {
        assertEquals("21_Waffle — waffle-commons", plainHeading("21_Waffle — `waffle-commons`"))
        assertEquals("See the plan and Roadmap", plainHeading("See [[Notes/Plan|the plan]] and [[20_Projects/x/Roadmap]]"))
        assertEquals("Read the docs now", plainHeading("Read [the docs](https://example.org) **now**"))
    }
}
