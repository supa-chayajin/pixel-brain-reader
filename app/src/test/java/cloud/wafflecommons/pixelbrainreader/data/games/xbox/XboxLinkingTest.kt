package cloud.wafflecommons.pixelbrainreader.data.games.xbox

import cloud.wafflecommons.pixelbrainreader.data.games.ConsoleLinks
import cloud.wafflecommons.pixelbrainreader.data.games.GameConsole
import cloud.wafflecommons.pixelbrainreader.data.games.GameLinks
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GamePageCreation
import cloud.wafflecommons.pixelbrainreader.data.games.GameState
import cloud.wafflecommons.pixelbrainreader.data.games.GameTemplate
import cloud.wafflecommons.pixelbrainreader.data.games.XboxSnapshot
import cloud.wafflecommons.pixelbrainreader.data.games.XboxTitleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Which page an Xbox title belongs to, and which titles get a page of their own (XB4). */
class XboxLinkingTest {

    private val ringPage = GamePage(
        "30_Areas/Games/Ring_of_Ash/Ring_of_Ash.md", "Ring of Ash", emptyList(), GameTemplate.SOULS_LIKE, GameState.PLAYING, null, null
    )
    private val trailPage = GamePage(
        "30_Areas/Games/Outlaw_Trail_2/Outlaw_Trail_2.md", "Outlaw Trail 2", listOf("OT2"), GameTemplate.OTHER, GameState.BACKLOG, null, null
    )

    private val ring = "1915865634"
    private val legacy = "1480000001"
    private val modern = "1915865635"

    private fun title(id: String, name: String, modernId: String? = null, visible: Boolean = true, lastPlayed: String? = null) =
        XboxTitleState(titleId = id, name = name, achievementSource = 2, modernTitleId = modernId, lastPlayed = lastPlayed, visible = visible)

    private fun snapshot(vararg titles: XboxTitleState) = XboxSnapshot("2026-11-27T22:40:00Z", titles = titles.toList())

    @Test
    fun `a title is linked by its name`() {
        val current = snapshot(title(ring, "RING OF ASH™"), title("1234567890", "Brick Wars"))

        val links = XboxLinking.autoLinked(GameLinks(), current, listOf(ringPage, trailPage))

        assertEquals(ringPage.path, links.consolePage(GameConsole.XBOX, ring))
        assertNull(links.consolePage(GameConsole.XBOX, "1234567890"))
        // PSN's keys are untouched.
        assertTrue(links.links.isEmpty())
        // A title linked already keeps its page, even when its name says another.
        val byHand = GameLinks(xbox = ConsoleLinks(links = mapOf(ring to trailPage.path)))
        assertEquals(trailPage.path, XboxLinking.autoLinked(byHand, current, listOf(ringPage)).consolePage(GameConsole.XBOX, ring))
    }

    @Test
    fun `a linked sibling takes the other id along`() {
        // A 360 game played on the One: listed under its 360 id, with its modern id.
        val current = snapshot(title(legacy, "Outlaw Trail II", modernId = modern), title(modern, "Outlaw Trail 2"))

        // The modern id linked by hand: the 360 one follows it.
        val modernLinked = GameLinks(xbox = ConsoleLinks(links = mapOf(modern to ringPage.path)))
        assertEquals(ringPage.path, XboxLinking.autoLinked(modernLinked, current, listOf(trailPage)).consolePage(GameConsole.XBOX, legacy))

        // The 360 id linked: the modern one follows it, before its own name is looked at.
        val legacyLinked = GameLinks(xbox = ConsoleLinks(links = mapOf(legacy to ringPage.path)))
        assertEquals(ringPage.path, XboxLinking.autoLinked(legacyLinked, current, listOf(trailPage)).consolePage(GameConsole.XBOX, modern))

        // Neither linked: the modern one by its name, and the 360 one, listed before it, along with it.
        val found = XboxLinking.autoLinked(GameLinks(), current, listOf(ringPage, trailPage))
        assertEquals(trailPage.path, found.consolePage(GameConsole.XBOX, modern))
        assertEquals(trailPage.path, found.consolePage(GameConsole.XBOX, legacy))
    }

    @Test
    fun `hidden titles stay out`() {
        val current = snapshot(title(ring, "Ring of Ash"))

        val hidden = GameLinks(xbox = ConsoleLinks(hidden = listOf(ring)))
        assertNull(XboxLinking.autoLinked(hidden, current, listOf(ringPage)).consolePage(GameConsole.XBOX, ring))

        // A PSN title hidden under the same id doesn't hide the Xbox one.
        val psnHidden = GameLinks(hidden = listOf(ring))
        assertEquals(ringPage.path, XboxLinking.autoLinked(psnHidden, current, listOf(ringPage)).consolePage(GameConsole.XBOX, ring))
    }

    @Test
    fun `candidates leave out linked (dangling too), hidden and profile-hidden titles`() {
        val current = snapshot(
            title(ring, "Ring of Ash"),
            title("1234567890", "Brick Wars", lastPlayed = "2026-11-27T21:00:00Z"),
            title("1234567891", "Gone Game"),
            title("1234567892", "Folded Game"),
            title("1234567893", "Private Game", visible = false),
            title(legacy, "Outlaw Trail II", modernId = modern),
            title(modern, "Outlaw Trail 2")
        )
        val links = GameLinks(
            xbox = ConsoleLinks(
                // Gone Game's page was deleted: the owner deleted it, so no new page.
                links = mapOf(ring to ringPage.path, "1234567891" to "30_Areas/Games/Gone_Game/Gone_Game.md"),
                hidden = listOf("1234567892")
            )
        )

        val candidates = XboxLinking.candidates(links, current)

        assertEquals(listOf("1234567890", legacy, modern), candidates.map { it.titleId })
        assertEquals(Instant.parse("2026-11-27T21:00:00Z"), candidates.first().lastPlayed)
        assertNull(candidates[1].lastPlayed)
        // The two ids of one game know each other, so they make one page.
        assertEquals(listOf(modern), candidates[1].siblings)
        assertEquals(listOf(legacy), candidates[2].siblings)
        val plans = GamePageCreation.plan(candidates, listOf(ringPage), takenPaths = emptyList(), console = GameConsole.XBOX)
        assertEquals(listOf(listOf("1234567890"), listOf(legacy, modern)), plans.map { it.titleIds })
        assertTrue(plans.all { it.console == GameConsole.XBOX })
    }
}
