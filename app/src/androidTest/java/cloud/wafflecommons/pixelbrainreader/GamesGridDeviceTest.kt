package cloud.wafflecommons.pixelbrainreader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.wafflecommons.pixelbrainreader.data.games.GameArtRules
import cloud.wafflecommons.pixelbrainreader.data.games.GameConsole
import cloud.wafflecommons.pixelbrainreader.data.games.GameGroup
import cloud.wafflecommons.pixelbrainreader.data.games.GameMap
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GameState
import cloud.wafflecommons.pixelbrainreader.data.games.GameTemplate
import cloud.wafflecommons.pixelbrainreader.ui.games.GameRow
import cloud.wafflecommons.pixelbrainreader.ui.games.GamesActions
import cloud.wafflecommons.pixelbrainreader.ui.games.GamesContent
import cloud.wafflecommons.pixelbrainreader.ui.games.GamesTags
import cloud.wafflecommons.pixelbrainreader.ui.games.GamesUiState
import cloud.wafflecommons.pixelbrainreader.ui.games.UnlinkedTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The Games tab's grid on a device (13.4, P2, the review's ui #7): 200 made-up pages with their
 * initials only (no address, no file: nothing loads and no Hilt graph is asked for), drawn through
 * the tab's stateless content. Only what is on screen is built, a far cell comes once scrolled to,
 * the headings count, a cell's map button names its game, and a pending hide takes its row out at
 * once. No vault, no app state is touched. Emulator only.
 */
@RunWith(AndroidJUnit4::class)
class GamesGridDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun onlyTheFirstCellsAreBuilt() {
        compose.setContent { Tab(library(backlog = 200)) }

        compose.onNodeWithText("Lantern Bay 001").assertIsDisplayed()
        compose.onNodeWithText("Lantern Bay 200").assertDoesNotExist()
        // A screenful of cells and a row ahead, never the 200.
        val built = compose.onAllNodes(hasText("Lantern Bay", substring = true)).fetchSemanticsNodes().size
        assertTrue("$built cells built", built in 3..60)
    }

    @Test
    fun aFarCellAppearsOnceScrolledToItsKey() {
        val state = library(backlog = 200)
        val far = state.groups.getValue(GameGroup.BACKLOG).last().page
        compose.setContent { Tab(state) }

        compose.onNodeWithText(far.title).assertDoesNotExist()
        compose.onNodeWithTag(GamesTags.GRID).performScrollToKey(GamesTags.page(far.path))
        compose.onNodeWithText(far.title).assertIsDisplayed()
    }

    @Test
    fun theHeadingsShowTheirCounts() {
        compose.setContent { Tab(library(playing = 2, next = 3, backlog = 200, done = 5)) }
        val grid = compose.onNodeWithTag(GamesTags.GRID)

        compose.onNodeWithText("Playing · 2").assertIsDisplayed()
        grid.performScrollToKey(GamesTags.heading(GameGroup.NEXT))
        compose.onNodeWithText("Next · 3").assertIsDisplayed()
        grid.performScrollToKey(GamesTags.heading(GameGroup.BACKLOG))
        compose.onNodeWithText("Backlog · 200").assertIsDisplayed()
        grid.performScrollToKey(GamesTags.heading(GameGroup.DONE))
        compose.onNodeWithText("Done · 5").assertIsDisplayed()
    }

    @Test
    fun aCellsMapButtonNamesItsGame() {
        val state = library(backlog = 3, mapOnFirst = true)
        val opened = mutableListOf<String>()
        compose.setContent { Tab(state, GamesActions(onOpenMap = { opened += it })) }

        compose.onNodeWithContentDescription("Open the map of Lantern Bay 001").assertIsDisplayed().performClick()
        assertEquals(listOf(state.groups.getValue(GameGroup.BACKLOG).first().page.path), opened)
    }

    @Test
    fun aPendingHideLeavesItsRowAtOnce() {
        val titles = listOf("Glass Tower", "Tin Orchard", "Lantern Bay").mapIndexed { i, name ->
            UnlinkedTitle("PPSA0000${i + 1}_00", name, hours = 1.0 + i, lastPlayed = TODAY.minusDays(i.toLong()), console = GameConsole.PSN)
        }
        var state by mutableStateOf(library(backlog = 1).copy(unlinked = titles))
        val hidden = mutableListOf<String>()
        val actions = GamesActions(
            onHide = { title ->
                hidden += title.titleId
                // What the ViewModel does at once, before any write: the title joins the pending hides.
                state = state.copy(pendingHidden = state.pendingHidden + title.foldKey)
            }
        )
        compose.setContent { Tab(state, actions) }
        val grid = compose.onNodeWithTag(GamesTags.GRID)

        grid.performScrollToKey(GamesTags.fold(GameConsole.PSN))
        compose.onNodeWithText("On PlayStation, not in your library (3)").performClick()
        grid.performScrollToKey(GamesTags.unlinked(titles[1]))
        compose.onNodeWithText("Tin Orchard").assertIsDisplayed()
        compose.onNode(hasText("Hide") and hasAnyAncestor(hasTestTag(GamesTags.unlinked(titles[1])))).performClick()

        assertEquals(listOf("PPSA00002_00"), hidden)
        compose.onNodeWithText("Tin Orchard").assertDoesNotExist()
        compose.onNodeWithText("On PlayStation, not in your library (2)").assertIsDisplayed()
        grid.performScrollToKey(GamesTags.unlinked(titles[2]))
        compose.onNodeWithText("Lantern Bay").assertIsDisplayed()
        compose.onNodeWithText("Glass Tower").assertIsDisplayed()
    }

    /** The tab's content at a phone's size. */
    @Composable
    private fun Tab(state: GamesUiState, actions: GamesActions = GamesActions()) {
        MaterialTheme {
            Box(Modifier.safeDrawingPadding().size(width = 412.dp, height = 800.dp)) {
                GamesContent(state, actions)
            }
        }
    }

    private fun page(name: String, state: GameState, withMap: Boolean = false): GamePage {
        val key = name.replace(' ', '_')
        return GamePage(
            path = "30_Areas/Games/$key/$key.md",
            title = name,
            aliases = emptyList(),
            template = GameTemplate.OTHER,
            state = state,
            started = null,
            finished = null,
            maps = if (withMap) listOf(GameMap(url = "https://maps.example.org/$key", label = "Map", site = "maps.example.org")) else emptyList()
        )
    }

    private fun row(page: GamePage) = GameRow(
        page = page,
        hours = null,
        trophyPercent = null,
        achievementPercent = null,
        lastPlayed = null,
        art = GameArtRules.initialsOnly(page.title)
    )

    /** Made-up pages for each group, numbered: "Lantern Bay 001" and on in Backlog. */
    private fun library(playing: Int = 0, next: Int = 0, backlog: Int = 0, done: Int = 0, mapOnFirst: Boolean = false): GamesUiState {
        fun named(base: String, count: Int, state: GameState) =
            (1..count).map { n -> page("$base ${n.toString().padStart(3, '0')}", state, withMap = mapOnFirst && n == 1) }
        val groups = mapOf(
            GameGroup.PLAYING to named("Glass Tower", playing, GameState.PLAYING),
            GameGroup.NEXT to named("Tin Orchard", next, GameState.NEXT),
            GameGroup.BACKLOG to named("Lantern Bay", backlog, GameState.BACKLOG),
            GameGroup.DONE to named("Glass Tower Redux", done, GameState.DONE)
        ).mapValues { (_, pages) -> pages.map(::row) }
        val rows = groups.values.flatten()
        return GamesUiState(today = TODAY, loading = false, rows = rows, groups = groups, pages = rows.map { it.page }, readOnly = false)
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 11, 27)
    }
}
