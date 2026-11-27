package cloud.wafflecommons.pixelbrainreader.data.games

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 13.3: `links.json` keeps PSN's keys as 12.17 wrote them and holds Xbox's apart. */
class GameLinksTest {

    /** The repository's own settings: unknown keys ignored, defaults not written. */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    /** What an app older than 13.3 reads and writes. */
    @Serializable
    private data class LinksV1(val links: Map<String, String> = emptyMap(), val hidden: List<String> = emptyList())

    private val ring = "30_Areas/Games/Ring_of_Ash/Ring_of_Ash.md"
    private val bricks = "30_Areas/Games/Brick_Wars/Brick_Wars.md"

    @Test
    fun `PSN's keys read and write as in 13_2`() {
        val older = """{"links":{"PPSA00001_00":"$ring"},"hidden":["CUSA00002_00"]}"""
        val links = json.decodeFromString(GameLinks.serializer(), older)
        assertEquals(ring, links.pageOf("PPSA00001_00"))
        assertEquals(listOf("CUSA00002_00"), links.hidden)
        assertEquals(ConsoleLinks(), links.xbox)
        // Without an Xbox title, the file is written exactly as 13.2 wrote it.
        val written = json.encodeToString(GameLinks.serializer(), links)
        assertFalse(written.contains("xbox"))
        assertEquals(json.encodeToString(LinksV1.serializer(), LinksV1(links.links, links.hidden)), written)
        // An empty Xbox block is the default: never written.
        assertFalse(json.encodeToString(GameLinks.serializer(), links.copy(xbox = ConsoleLinks(emptyMap(), emptyList()))).contains("xbox"))
        // An Xbox block holding only hidden titles writes only them.
        val hiddenOnly = json.encodeToString(GameLinks.serializer(), links.hideConsole(GameConsole.XBOX, "1234567890"))
        assertTrue(hiddenOnly.contains("\"xbox\""))
        assertFalse(hiddenOnly.substringAfter("\"xbox\"").contains("\"links\""))
    }

    @Test
    fun `an older app's shape still reads PSN's links from a file with Xbox keys`() {
        val links = GameLinks(links = mapOf("PPSA00001_00" to ring), hidden = listOf("CUSA00002_00"))
            .linkConsole(GameConsole.XBOX, "1915865634", ring)
            .hideConsole(GameConsole.XBOX, "1234567890")
        val written = json.encodeToString(GameLinks.serializer(), links)
        assertTrue(written.contains("\"xbox\""))
        val older = json.decodeFromString(LinksV1.serializer(), written)
        assertEquals(mapOf("PPSA00001_00" to ring), older.links)
        assertEquals(listOf("CUSA00002_00"), older.hidden)
        // And it reads back whole.
        assertEquals(links, json.decodeFromString(GameLinks.serializer(), written))
        assertEquals(ring, json.decodeFromString(GameLinks.serializer(), written).consolePage(GameConsole.XBOX, "1915865634"))
    }

    @Test
    fun `console links, hides and pages are apart`() {
        var links = GameLinks()
            .link("PPSA00001_00", ring)
            .linkConsole(GameConsole.XBOX, "1915865634", ring)
            .linkConsole(GameConsole.XBOX, "1234567890", bricks)
        assertEquals(ring, links.consolePage(GameConsole.PSN, "PPSA00001_00"))
        assertNull(links.consolePage(GameConsole.XBOX, "PPSA00001_00"))
        assertNull(links.pageOf("1915865634"))
        assertEquals(listOf("1915865634"), links.consoleTitles(GameConsole.XBOX, ring))
        assertEquals(listOf("PPSA00001_00"), links.consoleTitles(GameConsole.PSN, ring))
        assertEquals(listOf("PPSA00001_00"), links.titlesOf(ring))

        links = links.hideConsole(GameConsole.XBOX, "1234567890")
        assertTrue(links.isHidden(GameConsole.XBOX, "1234567890"))
        assertFalse(links.isHidden(GameConsole.PSN, "1234567890"))
        assertNull(links.consolePage(GameConsole.XBOX, "1234567890"))
        // Hidden twice, listed once; linked again, back in the library.
        links = links.hideConsole(GameConsole.XBOX, "1234567890")
        assertEquals(listOf("1234567890"), links.xbox.hidden)
        links = links.linkConsole(GameConsole.XBOX, "1234567890", bricks)
        assertFalse(links.isHidden(GameConsole.XBOX, "1234567890"))
        assertEquals(bricks, links.consolePage(GameConsole.XBOX, "1234567890"))

        // PSN through the console functions is PSN's own side.
        val psnHidden = links.hideConsole(GameConsole.PSN, "PPSA00001_00")
        assertEquals(listOf("PPSA00001_00"), psnHidden.hidden)
        assertTrue(psnHidden.isHidden(GameConsole.PSN, "PPSA00001_00"))
        assertNull(psnHidden.pageOf("PPSA00001_00"))
        assertEquals(links.xbox, psnHidden.xbox)
        assertEquals(ring, psnHidden.linkConsole(GameConsole.PSN, "PPSA00001_00", ring).pageOf("PPSA00001_00"))
        assertEquals(GameSessionRecord.SOURCE_PSN, GameConsole.PSN.source)
        assertEquals(GameSessionRecord.SOURCE_XBOX, GameConsole.XBOX.source)
        assertEquals("Xbox", GameConsole.XBOX.label)
    }

    @Test
    fun `pageFor follows the session's source`() {
        // The same id on both consoles: each session finds its own console's page.
        val links = GameLinks().link("1915865634", bricks).linkConsole(GameConsole.XBOX, "1915865634", ring)
        fun session(source: String, titleId: String? = "1915865634", page: String? = null) =
            GameSessionRecord("s", "2026-11-27", "20:00", 30, source, titleId = titleId, page = page)
        assertEquals(bricks, links.pageFor(session(GameSessionRecord.SOURCE_PSN)))
        assertEquals(ring, links.pageFor(session(GameSessionRecord.SOURCE_XBOX)))
        assertEquals("manual.md", links.pageFor(session(GameSessionRecord.SOURCE_MANUAL, titleId = null, page = "manual.md")))
        assertNull(links.pageFor(session(GameSessionRecord.SOURCE_XBOX, titleId = "999")))
        assertNull(links.pageFor(session(GameSessionRecord.SOURCE_MANUAL, titleId = null)))
    }
}
