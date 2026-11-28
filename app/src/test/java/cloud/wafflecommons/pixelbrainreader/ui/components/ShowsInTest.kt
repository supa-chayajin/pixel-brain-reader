package cloud.wafflecommons.pixelbrainreader.ui.components

import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** "Shows in" (13.4, F4 and S3): a feed keeps one mode at least, a tab may hide in all of them. */
class ShowsInTest {

    @Test
    fun `toggling adds and removes a mode`() {
        assertEquals(
            setOf(ContextMode.WORK, ContextMode.PERSONAL),
            ShowsIn.toggled(setOf(ContextMode.WORK), ContextMode.PERSONAL, keepOne = true)
        )
        assertEquals(
            setOf(ContextMode.PERSONAL),
            ShowsIn.toggled(setOf(ContextMode.WORK, ContextMode.PERSONAL), ContextMode.WORK, keepOne = true)
        )
        // In the modes' order, whatever the order they were switched on in.
        assertEquals(
            listOf(ContextMode.WORK, ContextMode.PERSONAL, ContextMode.OFF),
            ShowsIn.toggled(setOf(ContextMode.OFF, ContextMode.PERSONAL), ContextMode.WORK, keepOne = true).toList()
        )
    }

    @Test
    fun `the last mode on stays on when one must remain`() {
        assertEquals(setOf(ContextMode.OFF), ShowsIn.toggled(setOf(ContextMode.OFF), ContextMode.OFF, keepOne = true))
    }

    @Test
    fun `without keepOne every mode can go`() {
        assertEquals(emptySet<ContextMode>(), ShowsIn.toggled(setOf(ContextMode.OFF), ContextMode.OFF, keepOne = false))
        assertEquals(setOf(ContextMode.WORK), ShowsIn.toggled(emptySet(), ContextMode.WORK, keepOne = false))
    }
}
