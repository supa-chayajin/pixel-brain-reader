package cloud.wafflecommons.pixelbrainreader.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

/** The bottom bar and the side rail share one order: the user's (Settings → navigation order). */
class NavOrderTest {

    private val tabs = listOf("home", "habits", "home_os", "chat", "mood", "stats")

    @Test
    fun `the saved order is followed`() {
        assertEquals(
            listOf("stats", "chat", "home", "habits", "home_os", "mood"),
            inNavOrder(listOf("stats", "chat", "home", "habits", "home_os", "mood"), tabs) { it }
        )
    }

    @Test
    fun `a tab missing from the saved order is appended, never dropped`() {
        // An order saved before "stats" existed still shows it, last.
        assertEquals(
            listOf("chat", "home", "habits", "home_os", "mood", "stats"),
            inNavOrder(listOf("chat", "home", "habits", "home_os", "mood"), tabs) { it }
        )
    }

    @Test
    fun `an unknown route in the saved order is ignored`() {
        assertEquals(tabs, inNavOrder(listOf("gone") + tabs, tabs) { it })
    }

    @Test
    fun `no saved order keeps the default order`() {
        assertEquals(tabs, inNavOrder(emptyList(), tabs) { it })
    }
}
