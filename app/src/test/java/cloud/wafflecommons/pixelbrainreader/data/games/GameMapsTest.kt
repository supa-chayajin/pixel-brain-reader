package cloud.wafflecommons.pixelbrainreader.data.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 13.2: a game page's maps, from its `game_maps`. */
class GameMapsTest {

    @Test
    fun `an address alone is named by its site and its page`() {
        val map = GameMaps.parse(listOf("https://ringofash.wiki.example.com/Interactive+Map")).single()
        assertEquals("https://ringofash.wiki.example.com/Interactive+Map", map.url)
        assertEquals("Interactive Map", map.label)
        assertEquals("Example", map.site)
        assertEquals("Overall World Maps", GameMaps.parse(listOf("https://www.example.com/Overall_World_Maps/")).single().label)
        assertEquals("Example", GameMaps.parse(listOf("https://example.com/")).single().label)
    }

    @Test
    fun `a label before the bar says what the page is`() {
        val map = GameMaps.parse(listOf("Maps (images) | https://ringofash.wiki.example.com/Maps")).single()
        assertEquals("Maps (images)", map.label)
        assertEquals("https://ringofash.wiki.example.com/Maps", map.url)
        // An address keeps its query.
        assertEquals("https://example.com/map?x=1&y=2", GameMaps.parse(listOf("Map | https://example.com/map?x=1&y=2")).single().url)
    }

    @Test
    fun `only public https addresses reach the app, in the note's order, once each`() {
        val maps = GameMaps.parse(
            listOf(
                "http://example.com/map",
                "https://192.168.1.10/map",
                "https://localhost/map",
                "https://127.0.0.1/map",
                "ftp://example.com/map",
                "not an address",
                "",
                "Second | https://two.example.org/Map",
                "https://one.example.com/Map",
                "https://one.example.com/Map"
            )
        )
        assertEquals(listOf("https://two.example.org/Map", "https://one.example.com/Map"), maps.map { it.url })
        assertTrue(GameMaps.parse(emptyList()).isEmpty())
    }

    @Test
    fun `a page reads its maps from game_maps`() {
        val content = "---\ngame_template: souls-like\ngame_maps:\n  - \"Interactive map | https://ringofash.wiki.example.com/Interactive+Map\"\n  - \"https://ringofash.wiki.example.com/Maps\"\n---\n# Ring\n"
        val page = GamePages.parse("30_Areas/Games/Ring/Ring.md", content)!!
        assertEquals(listOf("Interactive map", "Maps"), page.maps.map { it.label })
        assertTrue(GamePages.parse("30_Areas/Games/Ring/Ring.md", "---\ngame_template: lego\ngame_maps: []\n---\n")!!.maps.isEmpty())
    }
}
