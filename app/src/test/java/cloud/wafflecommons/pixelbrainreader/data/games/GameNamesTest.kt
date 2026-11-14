package cloud.wafflecommons.pixelbrainreader.data.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameNamesTest {

    private fun page(title: String, vararg aliases: String) =
        GamePage("${title.replace(' ', '_')}.md", title, aliases.toList(), GameTemplate.OTHER, GameState.BACKLOG, null, null)

    private val ring = page("Ring of Ash")
    private val reign = page("Ring of Ash Nightfall", "Nightfall")
    private val outlaw = page("Outlaw Trail 2", "Outlaw 2", "Outlaw Trail Online")
    private val bricks = page("Brick Wars")
    private val demon = page("Demon's Keep")
    private val pages = listOf(ring, reign, outlaw, bricks, demon)

    @Test
    fun `normalising drops marks and punctuation, keeps words`() {
        assertEquals("brick wars the saga", GameNames.normalize("BRICK® Wars™: The Saga"))
        assertEquals("demons keep", GameNames.normalize("Demon’s Keep"))
        assertEquals("demons keep", GameNames.normalize("Demon's Keep"))
        assertEquals("ring of ash", GameNames.normalize("  RING   OF ASH "))
    }

    @Test
    fun `a PSN title matches a page by its name or first words, the longest name winning`() {
        assertEquals(ring, GameNames.pageForTitle("RING OF ASH", pages))
        assertEquals(reign, GameNames.pageForTitle("RING OF ASH NIGHTFALL", pages))
        assertEquals(bricks, GameNames.pageForTitle("BRICK® Wars™: The Saga", pages))
        assertEquals(outlaw, GameNames.pageForTitle("Outlaw Trail Online", pages))
        assertEquals(demon, GameNames.pageForTitle("Demon’s Keep", pages))
        assertNull(GameNames.pageForTitle("Something Else", pages))
        // A word that only starts like a page's name is not that page.
        assertNull(GameNames.pageForTitle("Ring of Ashes", pages))
    }

    @Test
    fun `a Timeline line names a page as whole words, aliases included`() {
        assertEquals(ring, GameNames.pageInLine("Ring of Ash DLC", pages))
        assertEquals(reign, GameNames.pageInLine("Nightfall with friends", pages))
        assertEquals(outlaw, GameNames.pageInLine("Outlaw 2", pages))
        assertNull(GameNames.pageInLine("Lunch break", pages))
        assertNull(GameNames.pageInLine("Outlaw 20", pages))
    }

    @Test
    fun `two pages tied on the same name match neither`() {
        val twin = page("Ring of Ash", "RoA")
        assertNull(GameNames.pageForTitle("Ring of Ash", listOf(ring, twin.copy(path = "other.md"))))
    }
}
