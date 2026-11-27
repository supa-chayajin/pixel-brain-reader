package cloud.wafflecommons.pixelbrainreader.data.games

import cloud.wafflecommons.pixelbrainreader.data.web.SharedContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 13.2: a game page's maps, from its `game_maps`; 13.3: what the edits and the share need. */
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

    // --- 13.3 ------------------------------------------------------------------------------

    @Test
    fun `accepts public https only`() {
        assertTrue(GameMaps.accepts("https://ringofash.wiki.example.com/Interactive+Map"))
        assertTrue(GameMaps.accepts("HTTPS://example.com/map"))
        assertFalse(GameMaps.accepts("http://example.com/map"))
        assertFalse(GameMaps.accepts("https://192.168.1.10/map"))
        assertFalse(GameMaps.accepts("https://localhost/map"))
        assertFalse(GameMaps.accepts("https://router.local/map"))
        // A host without a suffix names no site.
        assertFalse(GameMaps.accepts("https://intranet/map"))
        assertFalse(GameMaps.accepts("ftp://example.com/map"))
        assertFalse(GameMaps.accepts("not an address"))
        assertFalse(GameMaps.accepts(""))
    }

    @Test
    fun `preview names the map before saving`() {
        val map = GameMaps.preview(" https://ringofash.wiki.example.com/Interactive+Map ", "  Interactive   map ")!!
        assertEquals("https://ringofash.wiki.example.com/Interactive+Map", map.url)
        assertEquals("Interactive map", map.label)
        assertEquals("Example", map.site)
        // Without a label the page's name is shown, as for an entry typed in the note.
        assertEquals("Interactive Map", GameMaps.preview("https://ringofash.wiki.example.com/Interactive+Map", null)!!.label)
        assertEquals("Interactive Map", GameMaps.preview("https://ringofash.wiki.example.com/Interactive+Map", "   ")!!.label)
        assertNull(GameMaps.preview("http://example.com/map", "Map"))
        assertNull(GameMaps.preview("https://10.0.0.2/map", "Map"))
        assertEquals("Interactive map | https://ringofash.wiki.example.com/Interactive+Map", GameMaps.text(" https://ringofash.wiki.example.com/Interactive+Map", " Interactive map"))
        assertEquals("https://ringofash.wiki.example.com/Maps", GameMaps.text("https://ringofash.wiki.example.com/Maps ", null))
    }

    @Test
    fun `given keeps the typed label`() {
        val maps = GameMaps.parse(listOf("Maps (images)  |  https://two.example.org/Maps", "https://one.example.com/Map"))
        assertEquals(listOf("Maps (images)", null), maps.map { it.given })
        assertEquals(listOf("Maps (images)", "Map"), maps.map { it.label })
        assertEquals("https://two.example.org/Maps", maps.first().url)
    }

    @Test
    fun `a link shared from Chrome is offered with its title`() {
        val shared = SharedContent.of("https://ringofash.wiki.example.com/Interactive+Map", "Interactive Map | Ring Wiki")
        val offer = GameMaps.fromShare(shared.url, shared.titleHint)!!
        assertEquals(MapOffer("https://ringofash.wiki.example.com/Interactive+Map", "Interactive Map | Ring Wiki"), offer)
        // Saved as an entry, the whole shared title stays the label: the address is after the last bar.
        val map = GameMaps.preview(offer.url, offer.label)!!
        assertEquals("Interactive Map | Ring Wiki", map.label)
        assertEquals(offer.url, map.url)
    }

    @Test
    fun `a shared http or private link is no map`() {
        assertNull(GameMaps.fromShare("http://ringofash.wiki.example.com/Map", "Map"))
        assertNull(GameMaps.fromShare("https://10.0.0.2/map", "Map"))
        assertNull(GameMaps.fromShare("https://localhost/map", "Map"))
        assertNull(GameMaps.fromShare(null, "Map"))
        assertNull(GameMaps.fromShare(SharedContent.of("Just some words to keep", null).url, null))
    }

    @Test
    fun `a blank title gives no label, a long one is cut`() {
        assertNull(GameMaps.fromShare("https://example.com/map", "   ")!!.label)
        assertNull(GameMaps.fromShare("https://example.com/map", null)!!.label)
        val long = GameMaps.fromShare("https://example.com/map", "A".repeat(50) + " " + "B".repeat(50))!!.label!!
        assertEquals("A".repeat(50) + " " + "B".repeat(29), long)
        // Whitespace runs become one space, control characters go.
        assertEquals("Two words", GameMaps.cleanLabel(" Two\t\twords\n"))
        assertEquals("Bell", GameMaps.cleanLabel("Be\u0007ll"))
        // A cut never splits an emoji in two.
        val emoji = "🗺"
        assertEquals("A".repeat(79), GameMaps.cleanLabel("A".repeat(79) + emoji))
    }
}
