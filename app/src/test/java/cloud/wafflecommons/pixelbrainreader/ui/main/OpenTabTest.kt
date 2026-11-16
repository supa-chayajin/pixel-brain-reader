package cloud.wafflecommons.pixelbrainreader.ui.main

import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.ui.games.GamesRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The open tab and its place among the tabs as shown: its emblem's colour follows the place (13.2.1). */
class OpenTabTest {

    private val defaultOrder = UserPreferencesRepository.DEFAULT_NAVBAR_ORDER

    private fun open(route: String?, order: List<String> = defaultOrder, hidden: Set<String> = emptySet(), daily: Boolean = false) =
        openTabOf(navDestinations(route, isViewingDailyNote = daily, order = order, hidden = hidden))

    @Test
    fun `a tab's place is its index in the user's order`() {
        assertEquals(OpenTab("Repo", 0), open(Screen.Home))
        assertEquals(OpenTab("Habits", 1), open("habits"))
        assertEquals(OpenTab("Games", 7), open(GamesRoutes.GAMES))
    }

    @Test
    fun `a reordered tab takes its new place`() {
        val order = listOf("games", "home", "habits")
        assertEquals(OpenTab("Games", 0), open(GamesRoutes.GAMES, order))
        assertEquals(OpenTab("Repo", 1), open(Screen.Home, order))
    }

    @Test
    fun `a page under a tab keeps the tab's place`() {
        assertEquals(OpenTab("Games", 7), open(GamesRoutes.GAME))
    }

    @Test
    fun `a tab the mode hides moves the next ones up a place`() {
        assertEquals(OpenTab("Chores", 1), open(Screen.HomeOS, hidden = setOf("habits")))
    }

    @Test
    fun `Daily and today's note have no tab`() {
        assertNull(open(Screen.DailyNote))
        assertNull(open(Screen.Home, daily = true))
    }
}
