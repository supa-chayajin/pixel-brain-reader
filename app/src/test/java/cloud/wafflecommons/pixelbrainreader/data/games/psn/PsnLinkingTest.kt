package cloud.wafflecommons.pixelbrainreader.data.games.psn

import cloud.wafflecommons.pixelbrainreader.data.games.GameLinks
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GameState
import cloud.wafflecommons.pixelbrainreader.data.games.GameTemplate
import cloud.wafflecommons.pixelbrainreader.data.games.PsnSnapshot
import cloud.wafflecommons.pixelbrainreader.data.games.PsnTitleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 13.4: PSN's links by name and concept, to a fixed point. */
class PsnLinkingTest {

    /** A page the Xbox sync made: it answers its exact name only. */
    private val tower = GamePage(
        "30_Areas/Games/Glass_Tower/Glass_Tower.md", "Glass Tower", emptyList(), GameTemplate.OTHER, GameState.BACKLOG, null, null, exactName = true
    )
    private val concept = listOf("PPSA00002_00", "CUSA00002_00")

    /** Most recently played first: the PS5 version, whose name matches no page, before the PS4 one, whose name does. */
    private val current = PsnSnapshot(
        "2026-11-27T22:40:00Z",
        listOf(
            PsnTitleState("PPSA00002_00", "GLASS TOWER REMASTERED", conceptTitleIds = concept),
            PsnTitleState("CUSA00002_00", "Glass Tower", conceptTitleIds = concept)
        )
    )

    @Test
    fun `a PS5 title follows its PS4 sibling linked later in the same pass`() {
        val links = PsnLinking.autoLinked(GameLinks(), current, listOf(tower))
        assertEquals(tower.path, links.pageOf("CUSA00002_00"))
        assertEquals(tower.path, links.pageOf("PPSA00002_00"))
        // So neither is left to get a page of its own.
        assertTrue(current.titles.none { links.pageOf(it.titleId) == null })
        // A hidden version stays out; the other still links.
        val hidden = PsnLinking.autoLinked(GameLinks(hidden = listOf("PPSA00002_00")), current, listOf(tower))
        assertNull(hidden.pageOf("PPSA00002_00"))
        assertEquals(tower.path, hidden.pageOf("CUSA00002_00"))
        // A version linked by hand keeps its page, and its sibling follows it.
        val byHand = PsnLinking.autoLinked(GameLinks(links = mapOf("PPSA00002_00" to "elsewhere.md")), current, listOf(tower))
        assertEquals("elsewhere.md", byHand.pageOf("PPSA00002_00"))
        assertEquals("elsewhere.md", byHand.pageOf("CUSA00002_00"))
        // Nothing to link: the links as they were.
        assertEquals(GameLinks(), PsnLinking.autoLinked(GameLinks(), current, emptyList()))
    }
}
