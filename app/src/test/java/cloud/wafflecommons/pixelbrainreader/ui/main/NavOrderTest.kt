package cloud.wafflecommons.pixelbrainreader.ui.main

import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
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

    @Test
    fun `projects is a tab, and joins an order saved before it among the last ones`() {
        val defaults = UserPreferencesRepository.DEFAULT_NAVBAR_ORDER
        assertEquals(V11Routes.PROJECTS, defaults[defaults.size - 2])
        // A phone's order from 12.6.0, tabs rearranged: everything stays where it was.
        assertEquals(
            listOf("stats", "home", "chat", "habits", "home_os", "mood", "projects", "games"),
            inNavOrder(listOf("stats", "home", "chat", "habits", "home_os", "mood"), defaults) { it }
        )
    }

    @Test
    fun `games is the 8th tab and joins an order saved before it at its end`() {
        val defaults = UserPreferencesRepository.DEFAULT_NAVBAR_ORDER
        assertEquals(cloud.wafflecommons.pixelbrainreader.ui.games.GamesRoutes.GAMES, defaults.last())
        assertEquals(8, defaults.size)
        // The owner's 12.16 order, rearranged: nothing existing moves, Games comes last.
        val saved = listOf("home", "habits", "home_os", "projects", "chat", "mood", "stats")
        assertEquals(saved + "games", inNavOrder(saved, defaults) { it })
    }
}
