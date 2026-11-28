package cloud.wafflecommons.pixelbrainreader.data.games

import cloud.wafflecommons.pixelbrainreader.data.games.pictures.GamePictureSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 13.4 (GP1, P5–P7): which picture a game shows, its initials, and what the loader tries in turn. */
class GameArtTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun page(path: String, title: String) =
        GamePage(path, title, emptyList(), GameTemplate.OTHER, GameState.BACKLOG, null, null)

    private val tower = page("30_Areas/Games/Glass_Tower.md", "Glass Tower")
    private val orchard = page("30_Areas/Games/Tin_Orchard.md", "Tin Orchard")
    private val bay = page("30_Areas/Games/Lantern_Bay.md", "Lantern Bay")

    private val psnUrl = "https://image.api.playstation.com/vulcan/ap/rnd/202611/0101/tower.png"
    private val xboxHttp = "http://store-images.s-microsoft.com/image/apps.1234.tower"
    private val xboxHttps = "https://store-images.s-microsoft.com/image/apps.1234.tower"

    private fun psn(id: String, url: String?) = PsnTitleState(titleId = id, name = "Glass Tower", imageUrl = url)
    private fun xbox(id: String, url: String?) = XboxTitleState(titleId = id, name = "Glass Tower", imageUrl = url)

    @Test
    fun `the owner's picture wins, then PlayStation's, then Xbox's, then none`() {
        val own = File("/vault/30_Areas/Games/Glass_Tower (cover).jpg")

        val owner = GameArtRules.of(tower, own, listOf(psn("PPSA00001_00", psnUrl)), listOf(xbox("1001", xboxHttp)))
        assertEquals(ArtSource.OWN, owner.source)
        assertEquals(own, owner.ownFile)
        assertNull(owner.consoleUrl)

        val playStation = GameArtRules.of(tower, null, listOf(psn("PPSA00001_00", psnUrl)), listOf(xbox("1001", xboxHttp)))
        assertEquals(ArtSource.PSN, playStation.source)
        assertEquals(psnUrl, playStation.consoleUrl)
        assertNull(playStation.ownFile)

        // Xbox's http address is read through the rewrite: never http.
        val xboxOnly = GameArtRules.of(tower, null, emptyList(), listOf(xbox("1001", xboxHttp)))
        assertEquals(ArtSource.XBOX, xboxOnly.source)
        assertEquals(xboxHttps, xboxOnly.consoleUrl)

        val none = GameArtRules.of(tower, null, emptyList(), emptyList())
        assertEquals(GameArt(key = "Glass Tower", title = "Glass Tower", initials = "GT"), none)
        assertNull(none.source)

        // The first title whose address the loader may use: a title without one doesn't hide the next.
        val second = GameArtRules.of(tower, null, listOf(psn("CUSA00001_00", null), psn("PPSA00001_00", psnUrl)), emptyList())
        assertEquals(psnUrl, second.consoleUrl)
        // A PlayStation address the loader refuses gives way to Xbox's.
        val fallback = GameArtRules.of(tower, null, listOf(psn("PPSA00001_00", "http://image.api.playstation.com/a.png")), listOf(xbox("1001", xboxHttp)))
        assertEquals(ArtSource.XBOX, fallback.source)
        // The shape and tone key is the page's title, as the 13.2 badges used.
        assertEquals("Glass Tower", playStation.key)
    }

    @Test
    fun `an http or foreign-host address is no picture`() {
        assertNull(GameArtRules.forTitle("Tin Orchard", "http://image.api.playstation.com/orchard.png", GameConsole.PSN).consoleUrl)
        assertNull(GameArtRules.forTitle("Tin Orchard", "https://images.example.com/orchard.png", GameConsole.PSN).consoleUrl)
        assertNull(GameArtRules.forTitle("Tin Orchard", "http://images.example.com/orchard.png", GameConsole.XBOX).consoleUrl)
        assertNull(GameArtRules.forTitle("Tin Orchard", "https://image.api.playstation.com.example.com/o.png", GameConsole.PSN).consoleUrl)
        assertNull(GameArtRules.forTitle("Tin Orchard", null, GameConsole.XBOX).source)
        assertNull(GameArtRules.of(orchard, null, listOf(psn("PPSA00002_00", "ftp://image.api.playstation.com/o.png")), emptyList()).source)

        val listed = GameArtRules.forTitle("Tin Orchard", "https://image.api.playstation.com/orchard.png", GameConsole.PSN)
        assertEquals(ArtSource.PSN, listed.source)
        assertEquals("TO", listed.initials)
        assertEquals("Tin Orchard", listed.key)
        val fromXbox = GameArtRules.forTitle("Tin Orchard", "http://images-eds.xboxlive.com/image?url=abc", GameConsole.XBOX)
        assertEquals("https://images-eds-ssl.xboxlive.com/image?url=abc", fromXbox.consoleUrl)
        assertEquals(ArtSource.XBOX, fromXbox.source)
    }

    @Test
    fun `initials take two words' first letters`() {
        assertEquals("GT", GameArtRules.initials("Glass Tower"))
        assertEquals("LB", GameArtRules.initials("lantern bay of the north"))
        assertEquals("GT", GameArtRules.initials("Glass Tower: Lantern Bay"))
        assertEquals("TO", GameArtRules.initials("  Tin   Orchard  "))
        assertEquals("GT", GameArtRules.initials("GLASS-tower"))
        assertEquals("ÉB", GameArtRules.initials("éclat bay"))
        assertEquals("GT", GameArtRules.initials("🎮 Glass ❤️ Tower"))
    }

    @Test
    fun `one word gives one letter, digits count, marks and apostrophes don't split`() {
        assertEquals("L", GameArtRules.initials("Lantern"))
        assertEquals("1", GameArtRules.initials("1942"))
        assertEquals("TO", GameArtRules.initials("Tin Orchard 2"))
        assertEquals("2T", GameArtRules.initials("2 Tin Orchard"))
        assertEquals("GT", GameArtRules.initials("Glass™ Tower®"))
        // ™ inside a word joins it, as GameNames reads names.
        assertEquals("G", GameArtRules.initials("Glass™Tower"))
        // Either apostrophe joins its word.
        assertEquals("LB", GameArtRules.initials("Lantern's Bay"))
        assertEquals("LB", GameArtRules.initials("Lantern’s Bay"))
        assertEquals("T", GameArtRules.initials("Tin'Orchard"))
        // An invisible character or a combining mark never starts a new word.
        assertEquals("G", GameArtRules.initials("Glass\u200BTower"))
        assertEquals("EB", GameArtRules.initials("éclat Bay"))
        assertEquals("GT", GameArtRules.initials("\u202EGlass\u202C Tower"))
    }

    @Test
    fun `no letter gives no initials`() {
        assertEquals("", GameArtRules.initials(""))
        assertEquals("", GameArtRules.initials("   "))
        assertEquals("", GameArtRules.initials("™ ® ©"))
        assertEquals("", GameArtRules.initials("🎮 !! —"))
        assertEquals("", GameArtRules.initialsOnly("🎮").initials)
        assertEquals("🎮", GameArtRules.initialsOnly("🎮").title)
    }

    @Test
    fun `models try the file, the sized address, then the plain one`() {
        val own = File("/vault/Glass_Tower (cover).jpg")
        assertEquals(listOf<Any>(own), GameArtRules.models(GameArtRules.of(tower, own, listOf(psn("PPSA00001_00", psnUrl)), emptyList())))
        assertEquals(
            listOf<Any>("$psnUrl?w=512", psnUrl),
            GameArtRules.models(GameArtRules.of(tower, null, listOf(psn("PPSA00001_00", psnUrl)), emptyList()))
        )
        assertEquals(
            listOf<Any>("$xboxHttps?w=512&h=512", xboxHttps),
            GameArtRules.models(GameArtRules.of(tower, null, emptyList(), listOf(xbox("1001", xboxHttp))))
        )
        assertEquals(emptyList<Any>(), GameArtRules.models(GameArtRules.initialsOnly("Glass Tower")))
        // An address that already asks a size is tried once.
        val sized = "$psnUrl?w=256"
        assertEquals(listOf<Any>(sized), GameArtRules.models(GameArt("Glass Tower", "Glass Tower", "GT", consoleUrl = sized, source = ArtSource.PSN)))
    }

    @Test
    fun `the Now playing card asks the hosts for more pixels`() {
        // 13.4 review, the owner's choice: the card spans the window.
        assertEquals(
            listOf<Any>("$psnUrl?w=1024", psnUrl),
            GameArtRules.models(GameArtRules.of(tower, null, listOf(psn("PPSA00001_00", psnUrl)), emptyList()), GamePictures.HERO_SIZE_HINT)
        )
        assertEquals(
            listOf<Any>("$xboxHttps?w=1024&h=1024", xboxHttps),
            GameArtRules.models(GameArtRules.of(tower, null, emptyList(), listOf(xbox("1001", xboxHttp))), GamePictures.HERO_SIZE_HINT)
        )
    }

    @Test
    fun `a page's linked titles come from its own links, both consoles apart`() {
        val links = GameLinks(
            links = mapOf("PPSA00002_00" to tower.path, "PPSA00001_00" to tower.path, "PPSA00003_00" to orchard.path),
            xbox = ConsoleLinks(links = mapOf("1001" to tower.path, "1002" to bay.path))
        )
        val psnSnapshot = PsnSnapshot(titles = listOf(psn("PPSA00001_00", psnUrl), psn("PPSA00002_00", null), psn("PPSA00003_00", null)))
        val xboxSnapshot = XboxSnapshot(titles = listOf(xbox("1001", xboxHttp), xbox("1002", null)))

        val (psnTitles, xboxTitles) = GameArtRules.titlesOf(tower, links, psnSnapshot, xboxSnapshot)

        // In the links' order (their ids sorted), titles the snapshot no longer lists left out.
        assertEquals(listOf("PPSA00001_00", "PPSA00002_00"), psnTitles.map { it.titleId })
        assertEquals(listOf("1001"), xboxTitles.map { it.titleId })
        assertEquals(emptyList<PsnTitleState>() to emptyList<XboxTitleState>(), GameArtRules.titlesOf(bay, links, null, null))
    }

    @Test
    fun `each page's picture reads the links, both snapshots and the covers once`() = runBlocking {
        val own = File("/vault/30_Areas/Games/Lantern_Bay (cover).jpg")
        val games = mockk<GamesRepository> {
            coEvery { links() } returns GameLinks(
                links = mapOf("PPSA00001_00" to tower.path),
                xbox = ConsoleLinks(links = mapOf("1001" to orchard.path))
            )
            coEvery { snapshot() } returns PsnSnapshot(titles = listOf(psn("PPSA00001_00", psnUrl)))
            coEvery { xboxSnapshot() } returns XboxSnapshot(titles = listOf(xbox("1001", xboxHttp)))
            coEvery { coverFiles(any()) } returns mapOf(bay.path to own)
        }

        val arts = GamePictureSource(games).artFor(listOf(tower, orchard, bay))

        assertEquals(ArtSource.PSN, arts.getValue(tower.path).source)
        assertEquals(ArtSource.XBOX, arts.getValue(orchard.path).source)
        assertEquals(own, arts.getValue(bay.path).ownFile)
        coVerify(exactly = 1) { games.links() }
        coVerify(exactly = 1) { games.snapshot() }
        coVerify(exactly = 1) { games.xboxSnapshot() }
        coVerify(exactly = 1) { games.coverFiles(listOf(tower, orchard, bay)) }
    }

    @Test
    fun `an own picture carries its last change, so one replaced at the same path is another picture`() = runBlocking {
        val own = folder.newFile("Lantern_Bay (cover).jpg").apply { setLastModified(1_764_000_000_000L) }
        val games = mockk<GamesRepository> {
            coEvery { links() } returns GameLinks()
            coEvery { snapshot() } returns null
            coEvery { xboxSnapshot() } returns null
            coEvery { coverFiles(any()) } returns mapOf(bay.path to own)
        }
        val source = GamePictureSource(games)

        val before = source.artFor(bay)
        own.setLastModified(1_764_000_060_000L)
        val after = source.artFor(bay)

        assertEquals(1_764_000_000_000L, before.ownFileStamp)
        assertEquals(1_764_000_060_000L, after.ownFileStamp)
        assertNotEquals(before, after)
        // The pure rule leaves it unknown; only the source reads the disk.
        assertEquals(0L, GameArtRules.of(bay, own, emptyList(), emptyList()).ownFileStamp)
    }

    @Test
    fun `a picture that can't be read leaves the initials, never an error`() = runBlocking {
        val games = mockk<GamesRepository> {
            coEvery { links() } returns GameLinks()
            coEvery { snapshot() } throws IllegalStateException("the snapshot broke")
            coEvery { xboxSnapshot() } returns null
            coEvery { coverFiles(any()) } returns emptyMap()
        }
        val source = GamePictureSource(games)

        assertEquals(GameArtRules.initialsOnly("Glass Tower"), source.artFor(tower))
        assertEquals(emptyMap<String, GameArt>(), source.artFor(emptyList()))
    }
}
