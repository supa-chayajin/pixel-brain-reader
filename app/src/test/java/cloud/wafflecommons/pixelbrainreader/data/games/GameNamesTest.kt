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

    @Test
    fun `an exact-name page never takes a longer title by its first words`() {
        // 13.3: a page the sync created for a short name answers to that name only.
        val tower = page("Glass Tower", "GT").copy(exactName = true)
        assertEquals(tower, GameNames.pageForTitle("GLASS TOWER™", listOf(tower)))
        assertEquals(tower, GameNames.pageForTitle("gt", listOf(tower)))
        assertNull(GameNames.pageForTitle("Glass Tower Dungeons", listOf(tower)))
        // Without the mark, the first words still match.
        assertEquals(page("Glass Tower"), GameNames.pageForTitle("Glass Tower Dungeons", listOf(page("Glass Tower"))))
        // Beside an owner's page, the longer title finds that page only.
        val dungeons = page("Glass Tower Dungeons")
        assertEquals(dungeons, GameNames.pageForTitle("Glass Tower Dungeons: Deluxe", listOf(tower, dungeons)))
        // A Timeline line still names it anywhere, as whole words.
        assertEquals(tower, GameNames.pageInLine("Glass Tower with friends", listOf(tower)))
    }

    // --- 13.4 ------------------------------------------------------------------------------

    @Test
    fun `a zero-width space inside a word still matches the page`() {
        val tower = page("Glass Tower")
        assertEquals("glass tower", GameNames.normalize("Gla\u200Bss\u00AD Tow\u2060er\u202E"))
        assertEquals(tower, GameNames.pageForTitle("GLA\u200BSS TOWER", listOf(tower)))
        assertEquals(tower, GameNames.pageInLine("Evening at Glass\uFEFF Tower", listOf(tower)))
        // A joiner isn't invisible: it still parts two words, as before.
        assertEquals("glass tower", GameNames.normalize("Glass\u200DTower"))
    }

    @Test
    fun `a title that is the first words of a page's name looks like it`() {
        val online = page("Lantern Bay Online").copy(exactName = true)
        val sequel = page("Glass Tower 2")
        val pages = listOf(online, sequel, page("Tin Orchard"))
        // A console that listed the game again under a shorter name, or the base game beside its sequel's page.
        assertEquals(online, GameNames.lookalike("LANTERN BAY™", pages))
        assertEquals(sequel, GameNames.lookalike("Glass Tower", pages))
        // Whole words only.
        assertNull(GameNames.lookalike("Lantern", listOf(page("Lanterns of the Bay"))))
        assertNull(GameNames.lookalike("Coral Reef", pages))
        assertNull(GameNames.lookalike("™", pages))
        // The page whose such name is shortest, an alias too.
        val aliased = page("Glass Tower Deluxe Edition", "Glass Tower Deluxe")
        assertEquals(aliased, GameNames.lookalike("Glass Tower", listOf(page("Glass Tower Deluxe Edition Plus"), aliased)))
    }

    @Test
    fun `a page that answers the title is never a lookalike`() {
        val tower = page("Glass Tower")
        val sequel = page("Glass Tower 2")
        assertNull(GameNames.lookalike("Glass Tower", listOf(tower, sequel)))
        // A page the sync made answers its exact name too.
        assertNull(GameNames.lookalike("GLASS TOWER", listOf(tower.copy(exactName = true), sequel)))
        // Two pages tied on the name still answer it.
        assertNull(GameNames.lookalike("Glass Tower", listOf(tower, tower.copy(path = "other.md"), sequel)))
    }

    @Test
    fun `a sequel still links by the prefix rule`() {
        val tower = page("Glass Tower")
        assertEquals(tower, GameNames.pageForTitle("Glass Tower 2", listOf(tower)))
        assertNull(GameNames.lookalike("Glass Tower 2", listOf(tower)))
        // A page the sync made for the short name answers its exact names only: the sequel looks like nothing.
        val exact = tower.copy(exactName = true)
        assertNull(GameNames.pageForTitle("Glass Tower 2", listOf(exact)))
        assertNull(GameNames.lookalike("Glass Tower 2", listOf(exact)))
    }
}
