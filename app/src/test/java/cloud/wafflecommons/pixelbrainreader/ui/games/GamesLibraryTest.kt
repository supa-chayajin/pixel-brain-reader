package cloud.wafflecommons.pixelbrainreader.ui.games

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.ArtSource
import cloud.wafflecommons.pixelbrainreader.data.games.ConsoleLinks
import cloud.wafflecommons.pixelbrainreader.data.games.GameConsole
import cloud.wafflecommons.pixelbrainreader.data.games.GameGroup
import cloud.wafflecommons.pixelbrainreader.data.games.GameLinks
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionController
import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionRecord
import cloud.wafflecommons.pixelbrainreader.data.games.GameState
import cloud.wafflecommons.pixelbrainreader.data.games.GameTemplate
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
import cloud.wafflecommons.pixelbrainreader.data.games.LinksRead
import cloud.wafflecommons.pixelbrainreader.data.games.PsnSnapshot
import cloud.wafflecommons.pixelbrainreader.data.games.PsnTitleState
import cloud.wafflecommons.pixelbrainreader.data.games.RunningGame
import cloud.wafflecommons.pixelbrainreader.data.games.SessionCheck
import cloud.wafflecommons.pixelbrainreader.data.games.XboxSnapshot
import cloud.wafflecommons.pixelbrainreader.data.games.XboxTitleState
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnAccount
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnException
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnStatus
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnSync
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxAccount
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxException
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxStatus
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxSync
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.ui.cancelAndAwait
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The Games tab's library with both consoles (13.3, XB-E): rows, hours, achievements and the folds;
 * 13.4: the pictures, the "looks like" offer, the Hidden folds, every month's typed hours, the typed
 * sessions of a page that's gone, and the console lines' words (pure).
 */
class GamesLibraryTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 11, 27)

    private val ring = GamePage(
        "30_Areas/Games/Ring_of_Ash/Ring_of_Ash.md", "Ring of Ash", emptyList(), GameTemplate.SOULS_LIKE, GameState.PLAYING, null, null
    )
    private val bricks = GamePage(
        "30_Areas/Games/Brick_Wars/Brick_Wars.md", "Brick Wars", emptyList(), GameTemplate.LEGO, GameState.BACKLOG, null, null,
        playedBefore = 3.0
    )
    private val glass = GamePage(
        "30_Areas/Games/Glass_Tower/Glass_Tower.md", "Glass Tower", emptyList(), GameTemplate.OTHER, GameState.PLAYING, null, null
    )
    private val orchard = GamePage(
        "30_Areas/Games/Tin_Orchard/Tin_Orchard.md", "Tin Orchard", emptyList(), GameTemplate.OTHER, GameState.BACKLOG, null, null
    )

    private fun psn(
        id: String,
        name: String,
        seconds: Long,
        lastPlayed: String? = null,
        progress: Int? = null,
        imageUrl: String? = null
    ) = PsnTitleState(titleId = id, name = name, playSeconds = seconds, lastPlayed = lastPlayed, progress = progress, imageUrl = imageUrl)

    private fun xbox(
        id: String,
        name: String,
        minutes: Long?,
        lastPlayed: String? = null,
        earned: Int? = null,
        total: Int? = null,
        progress: Int? = null,
        imageUrl: String? = null
    ) = XboxTitleState(
        titleId = id,
        name = name,
        minutesPlayed = minutes,
        lastPlayed = lastPlayed,
        currentAchievements = earned,
        totalAchievements = total,
        progress = progress,
        imageUrl = imageUrl
    )

    private fun row(page: GamePage, psn: List<PsnTitleState> = emptyList(), xbox: List<XboxTitleState> = emptyList(), sessionMinutes: Int = 0) =
        GamesLibrary.row(page, psn, xbox, sessionMinutes, playedDays = emptyList(), zone = zone)

    private fun manual(id: String, date: String, start: String, minutes: Int, page: String) =
        GameSessionRecord(id = id, date = date, start = start, minutes = minutes, source = GameSessionRecord.SOURCE_MANUAL, page = page)

    /** One load's build, with the repository's resolver rule for a session: its own page, else its title's link. */
    private fun build(
        pages: List<GamePage>,
        sessions: List<GameSessionRecord> = emptyList(),
        psn: PsnSnapshot? = null,
        xbox: XboxSnapshot? = null,
        links: LinksRead = LinksRead(GameLinks(), readable = true),
        covers: Map<String, File> = emptyMap(),
        running: RunningGame? = null
    ) = GamesLibrary.build(
        pages = pages,
        psn = psn,
        xbox = xbox,
        links = links,
        sessions = sessions,
        resolve = { record -> pages.firstOrNull { it.path == links.links.pageFor(record) } },
        today = today,
        zone = zone,
        covers = covers,
        running = running
    )

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    // --- Rows ---------------------------------------------------------------------------------------

    @Test
    fun `hours add both consoles`() {
        // 2 h on PlayStation, 90 min on Xbox: the measured totals add up, the sessions don't count on top.
        val measured = row(ring, psn = listOf(psn("PPSA00001_00", "Ring of Ash", 7_200)), xbox = listOf(xbox("1915865634", "Ring of Ash", 90)), sessionMinutes = 45)
        assertEquals(3.5, measured.hours!!, 1e-9)

        // An Xbox title alone measures too.
        assertEquals(1.5, row(ring, xbox = listOf(xbox("1915865634", "Ring of Ash", 90))).hours!!, 1e-9)

        // Neither console measures: the typed hours plus the sessions (XB3).
        val typed = row(bricks, xbox = listOf(xbox("1234567890", "Brick Wars", null)), sessionMinutes = 60)
        assertEquals(4.0, typed.hours!!, 1e-9)

        // Nothing measured nor typed: no hours.
        assertNull(row(ring, xbox = listOf(xbox("1915865634", "Ring of Ash", null))).hours)
    }

    @Test
    fun `the achievement percent comes from the file's total, else titlehub's progress`() {
        // The snapshot's total is the achievements file's: 22 of 50.
        assertEquals(44, row(ring, xbox = listOf(xbox("1915865634", "Ring of Ash", 100, earned = 22, total = 50, progress = 10))).achievementPercent)
        // No file read yet (titlehub's own total reads 0 for an Xbox One title): titlehub's progress.
        assertEquals(34, row(ring, xbox = listOf(xbox("1915865634", "Ring of Ash", 100, earned = 22, total = null, progress = 34))).achievementPercent)
        assertEquals(34, row(ring, xbox = listOf(xbox("1915865634", "Ring of Ash", 100, earned = 22, total = 0, progress = 34))).achievementPercent)
        // A page holding two Xbox titles (a 360 version beside the One one) shows the best.
        val two = row(
            ring,
            xbox = listOf(
                xbox("1915865634", "Ring of Ash", 100, earned = 5, total = 50),
                xbox("1234567890", "Ring of Ash (360)", 10, earned = 10, total = 20)
            )
        )
        assertEquals(50, two.achievementPercent)
        // Without an Xbox title: no achievements, PlayStation's trophies as before.
        val psnOnly = row(ring, psn = listOf(psn("PPSA00001_00", "Ring of Ash", 3_600, progress = 80)))
        assertNull(psnOnly.achievementPercent)
        assertEquals(80, psnOnly.trophyPercent)
        // An Xbox title without any figure: none.
        assertNull(row(ring, xbox = listOf(xbox("1915865634", "Ring of Ash", 100))).achievementPercent)
    }

    @Test
    fun `the icon is PlayStation's, else Xbox's`() {
        val both = row(
            ring,
            psn = listOf(psn("PPSA00001_00", "Ring of Ash", 3_600, imageUrl = PSN_URL)),
            xbox = listOf(xbox("1915865634", "Ring of Ash", 60, imageUrl = XBOX_URL))
        )
        assertEquals(PSN_URL, both.art.consoleUrl)
        assertEquals(ArtSource.PSN, both.art.source)

        // PlayStation has none: Xbox's, its http address read as https on Microsoft's host (never http).
        val xboxOnly = row(
            ring,
            psn = listOf(psn("PPSA00001_00", "Ring of Ash", 3_600)),
            xbox = listOf(xbox("1915865634", "Ring of Ash", 60, imageUrl = "http://store-images.s-microsoft.com/image/apps.1.ring"))
        )
        assertEquals("https://store-images.s-microsoft.com/image/apps.1.ring", xboxOnly.art.consoleUrl)
        assertEquals(ArtSource.XBOX, xboxOnly.art.source)

        // A host outside the consoles' is no picture: the initials, in the game's own shape and tone.
        val foreign = row(ring, psn = listOf(psn("PPSA00001_00", "Ring of Ash", 3_600, imageUrl = "https://psn.example.com/ring.png")))
        assertNull(foreign.art.consoleUrl)
        assertNull(foreign.art.source)
        // The first letters of the first two words (GameArtRules.initials): "Ring of Ash" is RO.
        assertEquals("RO", row(ring).art.initials)
        assertEquals(ring.title, row(ring).art.key)
    }

    @Test
    fun `the owner's picture wins in a row`() {
        val cover = File("vault/30_Areas/Games/Glass_Tower/Glass_Tower (cover).jpg")
        val own = GamesLibrary.row(
            glass,
            psn = listOf(psn("PPSA00001_00", "Glass Tower", 3_600, imageUrl = PSN_URL)),
            xbox = listOf(xbox("1915865634", "Glass Tower", 60, imageUrl = XBOX_URL)),
            sessionMinutes = 0,
            playedDays = emptyList(),
            zone = zone,
            ownCover = cover,
            ownCoverStamp = 42L
        )
        assertEquals(ArtSource.OWN, own.art.source)
        assertEquals(cover, own.art.ownFile)
        assertNull(own.art.consoleUrl)
        // Its last change rides along: a picture replaced at the same path loads again.
        assertEquals(42L, own.art.ownFileStamp)

        // Through a load: the page's own cover file, by path.
        val loaded = build(pages = listOf(glass, orchard), covers = mapOf(glass.path to cover))
        assertEquals(cover, loaded.rows.first { it.page == glass }.art.ownFile)
        assertNull(loaded.rows.first { it.page == orchard }.art.ownFile)
    }

    @Test
    fun `last played is the latest of both consoles and the sessions`() {
        val consoles = row(
            ring,
            psn = listOf(psn("PPSA00001_00", "Ring of Ash", 3_600, lastPlayed = "2026-11-20T20:00:00Z")),
            xbox = listOf(xbox("1915865634", "Ring of Ash", 60, lastPlayed = "2026-11-26T23:30:00Z"))
        )
        // 23:30 UTC is past midnight in Paris: the 27th.
        assertEquals(LocalDate.of(2026, 11, 27), consoles.lastPlayed)

        val withSessions = GamesLibrary.row(
            ring,
            psn = emptyList(),
            xbox = listOf(xbox("1915865634", "Ring of Ash", 60, lastPlayed = "2026-11-20T20:00:00Z")),
            sessionMinutes = 30,
            playedDays = listOf(LocalDate.of(2026, 11, 22)),
            zone = zone
        )
        assertEquals(LocalDate.of(2026, 11, 22), withSessions.lastPlayed)
    }

    @Test
    fun `hours count every month's typed sessions`() {
        // A game only the owner tracks: a session more than a year ago still counts (13.4, not the last 365 days).
        val sessions = listOf(
            manual("manual:1741978800000:aaaaaaaa", "2025-03-14", "20:00", 90, glass.path),
            manual("manual:1795723200000:aaaaaaaa", "2026-11-20", "21:00", 30, glass.path)
        )
        val view = build(pages = listOf(glass), sessions = sessions)

        val row = view.rows.single()
        assertEquals(2.0, row.hours!!, 1e-9)
        assertEquals(LocalDate.of(2026, 11, 20), row.lastPlayed)
        assertEquals(listOf(row), view.groups[GameGroup.PLAYING])
    }

    @Test
    fun `typed sessions of a page that's gone are listed for linking`() {
        // Tin Orchard's page was renamed on the desktop: its typed sessions keep the old path.
        val gone = "30_Areas/Games/Tin_Orchard_Old/Tin_Orchard_Old.md"
        val view = build(pages = listOf(glass), sessions = listOf(manual("manual:1:bbbbbbbb", "2026-11-25", "20:00", 120, gone)))

        assertEquals(listOf(gone), view.orphans.map { it.oldPath })
        assertEquals("Tin Orchard Old", view.orphans.single().name)
        assertEquals(120, view.orphans.single().minutes)
        // They count nowhere else: no page has them.
        assertNull(view.rows.single().hours)
    }

    @Test
    fun `the game played last comes from what the load read`() {
        val sessions = listOf(
            manual("manual:1:cccccccc", "2026-11-20", "20:00", 60, glass.path),
            manual("manual:2:dddddddd", "2026-11-26", "21:00", 45, orchard.path)
        )
        assertEquals(orchard.path, build(pages = listOf(glass, orchard), sessions = sessions).lastPlayedPath)

        // Older than the tile's window (GameSessionController.defaultPage's 60 days): the first game being played.
        val old = listOf(manual("manual:3:dddddddd", "2026-08-01", "21:00", 45, orchard.path))
        assertEquals(glass.path, build(pages = listOf(glass, orchard), sessions = old).lastPlayedPath)

        // A console's last played time counts as well.
        val psnSnapshot = PsnSnapshot(titles = listOf(psn("PPSA00001_00", "Tin Orchard", 600, lastPlayed = "2026-11-27T19:00:00Z")))
        val links = LinksRead(GameLinks(links = mapOf("PPSA00001_00" to orchard.path)), readable = true)
        assertEquals(orchard.path, build(pages = listOf(glass, orchard), sessions = sessions.take(1), psn = psnSnapshot, links = links).lastPlayedPath)

        assertNull(build(pages = emptyList()).lastPlayedPath)
    }

    @Test
    fun `the running game keeps its picture, or its file name once its page is gone`() {
        val cover = File("vault/30_Areas/Games/Glass_Tower/Glass_Tower (cover).jpg")
        val view = build(pages = listOf(glass), covers = mapOf(glass.path to cover), running = RunningGame(1_000L, glass.path))
        assertEquals("Glass Tower", view.runningTitle)
        assertEquals(cover, view.runningArt?.ownFile)

        val gone = build(pages = listOf(glass), running = RunningGame(1_000L, "30_Areas/Games/Lantern_Bay/Lantern_Bay.md"))
        assertEquals("Lantern Bay", gone.runningTitle)
        assertEquals("LB", gone.runningArt?.initials)
        assertNull(gone.runningArt?.ownFile)

        // Moved to another folder on the desktop (one page with its file name): that page's picture.
        val moved = build(pages = listOf(glass), covers = mapOf(glass.path to cover), running = RunningGame(1_000L, "Old/Glass_Tower.md"))
        assertEquals("Glass Tower", moved.runningTitle)
        assertEquals(cover, moved.runningArt?.ownFile)

        assertNull(build(pages = listOf(glass)).runningArt)
    }

    // --- The folds -----------------------------------------------------------------------------------

    @Test
    fun `unlinked titles include dangling links, per console, hidden ones left out`() {
        val psnSnapshot = PsnSnapshot(
            syncedAt = "2026-11-27T22:40:00Z",
            titles = listOf(
                psn("PPSA00001_00", "Ring of Ash", 3_600, "2026-11-20T20:00:00Z"), // linked to a page: in the library
                psn("PPSA00002_00", "Brick Wars", 1_800, "2026-11-21T20:00:00Z"), // linked to a deleted page: back in the fold
                psn("CUSA00002_00", "Outlaw Trail 2", 7_200, "2026-11-22T20:00:00Z"), // hidden on PlayStation
                psn("PPSA00003_00", "Never Played", 0) // no play time: left out
            )
        )
        val xboxSnapshot = XboxSnapshot(
            syncedAt = "2026-11-27T22:40:00Z",
            titles = listOf(
                xbox("1915865634", "Ring of Ash", 120, "2026-11-23T20:00:00Z"), // a PSN key with this id doesn't claim it
                xbox("1234567890", "Brick Wars", 60, "2026-11-24T20:00:00Z"), // hidden on Xbox
                xbox("1111111111", "Outlaw Trail 2", null, "2026-11-25T20:00:00Z"), // PlayStation's hide stays PlayStation's
                xbox("2222222222", "Ring of Ash II", 30, "2026-11-26T20:00:00Z"), // linked to a page: in the library
                xbox("3333333333", "Brick Wars Classic", 5, "2026-11-19T20:00:00Z") // linked to a deleted page
            )
        )
        val links = GameLinks(
            links = mapOf(
                "PPSA00001_00" to ring.path,
                "PPSA00002_00" to "30_Areas/Games/Gone/Gone.md",
                "1915865634" to ring.path
            ),
            hidden = listOf("CUSA00002_00", "1111111111"),
            xbox = ConsoleLinks(
                links = mapOf("2222222222" to ring.path, "3333333333" to "30_Areas/Games/Gone/Gone.md"),
                hidden = listOf("1234567890", "PPSA00002_00")
            )
        )

        val titles = GamesLibrary.unlinked(psnSnapshot, xboxSnapshot, links, setOf(ring.path, bricks.path), zone)

        assertEquals(
            listOf(
                "1111111111" to GameConsole.XBOX,
                "1915865634" to GameConsole.XBOX,
                "PPSA00002_00" to GameConsole.PSN,
                "3333333333" to GameConsole.XBOX
            ),
            titles.map { it.titleId to it.console }
        )
        assertEquals(0.5, titles.first { it.titleId == "PPSA00002_00" }.hours!!, 1e-9)
        assertEquals(2.0, titles.first { it.titleId == "1915865634" }.hours!!, 1e-9)
    }

    @Test
    fun `Xbox titles with unknown minutes have no hours`() {
        val titles = GamesLibrary.unlinked(
            psn = null,
            xbox = XboxSnapshot(titles = listOf(xbox("1915865634", "Ring of Ash", null), xbox("1234567890", "Brick Wars", 90))),
            links = GameLinks(),
            pagePaths = emptySet(),
            zone = zone
        )

        assertNull(titles.first { it.titleId == "1915865634" }.hours)
        assertEquals(1.5, titles.first { it.titleId == "1234567890" }.hours!!, 1e-9)
        assertEquals(setOf(GameConsole.XBOX), titles.map { it.console }.toSet())
    }

    @Test
    fun `newest first`() {
        val titles = GamesLibrary.unlinked(
            psn = PsnSnapshot(
                titles = listOf(
                    psn("PPSA00001_00", "Ring of Ash", 60, "2026-11-20T20:00:00Z"),
                    psn("PPSA00002_00", "Brick Wars", 60, null)
                )
            ),
            xbox = XboxSnapshot(
                titles = listOf(
                    xbox("1234567890", "Brick Wars", 10, "2026-11-20T21:00:00Z"),
                    xbox("1915865634", "Outlaw Trail 2", 10, "2026-11-26T23:30:00.4411936Z")
                )
            ),
            links = GameLinks(),
            pagePaths = emptySet(),
            zone = zone
        )

        // Both consoles in one order, the latest played first; a title without a time last.
        assertEquals(listOf("1915865634", "1234567890", "PPSA00001_00", "PPSA00002_00"), titles.map { it.titleId })
        // The day is the zone's: 23:30 UTC on the 26th is the 27th in Paris.
        assertEquals(LocalDate.of(2026, 11, 27), titles.first().lastPlayed)
        assertNull(titles.last().lastPlayed)
    }

    @Test
    fun `fold titles carry their console's picture`() {
        val titles = GamesLibrary.unlinked(
            psn = PsnSnapshot(titles = listOf(psn("PPSA00001_00", "Glass Tower", 600, imageUrl = PSN_URL))),
            xbox = XboxSnapshot(titles = listOf(xbox("1234567890", "Lantern Bay", 10, imageUrl = "http://images-eds.xboxlive.com/image?url=bay"))),
            links = GameLinks(),
            pagePaths = emptySet(),
            zone = zone
        )
        assertEquals(PSN_URL, titles.first { it.console == GameConsole.PSN }.art.consoleUrl)
        // images-eds is asked for over https on its ssl host.
        assertEquals("https://images-eds-ssl.xboxlive.com/image?url=bay", titles.first { it.console == GameConsole.XBOX }.art.consoleUrl)
    }

    @Test
    fun `an unlinked title that looks like a page says so`() {
        // The console listed the game again under a shorter name: the page sync created is marked exact.
        val online = GamePage(
            "30_Areas/Games/Glass_Tower_Online/Glass_Tower_Online.md", "Glass Tower Online", emptyList(),
            GameTemplate.OTHER, GameState.BACKLOG, null, null, exactName = true
        )
        val pages = listOf(online, orchard)
        val xboxSnapshot = XboxSnapshot(
            titles = listOf(
                xbox("1918679142", "Glass Tower Online", null, "2026-11-20T20:00:00Z"), // linked to its page
                xbox("402049843", "Glass Tower", 30, "2026-11-25T20:00:00Z"), // the same game, shorter: asked
                xbox("1111111111", "Tin Orchard", 10, "2026-11-24T20:00:00Z"), // a page answers it by name: the next sync links it
                xbox("2222222222", "Lantern Bay", 5, "2026-11-23T20:00:00Z") // no page alike
            )
        )
        val links = GameLinks(xbox = ConsoleLinks(links = mapOf("1918679142" to online.path)))

        val titles = GamesLibrary.unlinked(null, xboxSnapshot, links, pages.mapTo(HashSet()) { it.path }, zone, pages)

        assertEquals(listOf("402049843", "1111111111", "2222222222"), titles.map { it.titleId })
        assertEquals(online, titles.first { it.titleId == "402049843" }.looksLike)
        assertNull(titles.first { it.titleId == "1111111111" }.looksLike)
        assertNull(titles.first { it.titleId == "2222222222" }.looksLike)
        // Without the pages, nothing looks like anything.
        assertNull(GamesLibrary.unlinked(null, xboxSnapshot, links, emptySet(), zone).first { it.titleId == "402049843" }.looksLike)
    }

    @Test
    fun `hidden titles come back in the hidden list`() {
        val psnSnapshot = PsnSnapshot(
            titles = listOf(
                psn("PPSA00001_00", "Glass Tower", 3_600, "2026-11-20T20:00:00Z"),
                psn("PPSA00002_00", "Tin Orchard", 1_800, "2026-11-22T20:00:00Z")
            )
        )
        val xboxSnapshot = XboxSnapshot(
            titles = listOf(
                xbox("1234567890", "Lantern Bay", null, "2026-11-21T20:00:00Z"),
                xbox("2222222222", "Glass Tower", 60, "2026-11-23T20:00:00Z")
            )
        )
        // A hide keeps the link (13.4): Glass Tower's still points at its page.
        val links = GameLinks(
            links = mapOf("PPSA00001_00" to glass.path),
            hidden = listOf("PPSA00001_00", "CUSA09999_00"),
            xbox = ConsoleLinks(hidden = listOf("1234567890"))
        )

        val hidden = GamesLibrary.hidden(psnSnapshot, xboxSnapshot, links, zone)

        // Newest first; a hidden id no snapshot names has no name to show.
        assertEquals(listOf("1234567890" to GameConsole.XBOX, "PPSA00001_00" to GameConsole.PSN), hidden.map { it.titleId to it.console })
        assertEquals(1.0, hidden.first { it.titleId == "PPSA00001_00" }.hours!!, 1e-9)
        assertNull(hidden.first { it.titleId == "1234567890" }.hours)
        // Neither shows in the folds meanwhile.
        val unlinked = GamesLibrary.unlinked(psnSnapshot, xboxSnapshot, links, emptySet(), zone)
        assertEquals(listOf("2222222222", "PPSA00002_00"), unlinked.map { it.titleId })

        // Through a load, both lists come together.
        val view = build(pages = listOf(orchard), psn = psnSnapshot, xbox = xboxSnapshot, links = LinksRead(links, readable = true))
        assertEquals(hidden.map { it.foldKey }, view.hidden.map { it.foldKey })
    }

    @Test
    fun `an unreadable links json hides the folds and lists nothing as unclaimed`() {
        val psnSnapshot = PsnSnapshot(titles = listOf(psn("PPSA00009_00", "Lantern Bay", 600, "2026-11-25T20:00:00Z")))
        val xboxSnapshot = XboxSnapshot(titles = listOf(xbox("1234567890", "Tin Orchard", 10, "2026-11-25T20:00:00Z")))

        val view = build(pages = listOf(glass), psn = psnSnapshot, xbox = xboxSnapshot, links = LinksRead(GameLinks(), readable = false))

        // Every title would look unclaimed: the folds wait for the owner's fix on the desktop.
        assertTrue(view.unlinked.isEmpty())
        assertTrue(view.hidden.isEmpty())
        // The library itself still shows.
        assertEquals(listOf(glass.path), view.rows.map { it.page.path })

        // Readable, the same titles are in the fold.
        val readable = build(pages = listOf(glass), psn = psnSnapshot, xbox = xboxSnapshot)
        assertEquals(setOf("PPSA00009_00", "1234567890"), readable.unlinked.map { it.titleId }.toSet())
    }

    @Test
    fun `a pending hide stays out until its title leaves the fold`() {
        val glassTitle = UnlinkedTitle("PPSA00001_00", "Glass Tower", 1.0, null, GameConsole.PSN)
        val orchardTitle = UnlinkedTitle("1234567890", "Tin Orchard", null, null, GameConsole.XBOX)
        val pending = setOf(glassTitle.foldKey, orchardTitle.foldKey)

        // Glass Tower's hide isn't read back yet (still in the fold): it stays out; Tin Orchard's landed.
        assertEquals(setOf(glassTitle.foldKey), GamesLibrary.stillPending(pending, listOf(glassTitle)))
        assertEquals(emptySet<String>(), GamesLibrary.stillPending(pending, emptyList()))
        // The key names the console: the same id on the other console is another title.
        assertEquals("PSN:PPSA00001_00", glassTitle.foldKey)
        assertFalse(glassTitle.copy(console = GameConsole.XBOX).foldKey in pending)
    }

    @Test
    fun `an undone link goes back as it was, unless it moved since`() {
        val linked = GameLinks().linkConsole(GameConsole.XBOX, "402049843", glass.path)
        assertNull(GamesLibrary.linkedBack(linked, GameConsole.XBOX, "402049843", glass.path, previous = null).consolePage(GameConsole.XBOX, "402049843"))

        // A link to a deleted page comes back as it was: it keeps the sync from making the page again.
        val dangling = GameLinks(links = mapOf("PPSA00002_00" to "30_Areas/Games/Gone/Gone.md"))
            .linkConsole(GameConsole.PSN, "PPSA00002_00", glass.path)
        val back = GamesLibrary.linkedBack(dangling, GameConsole.PSN, "PPSA00002_00", glass.path, previous = "30_Areas/Games/Gone/Gone.md")
        assertEquals("30_Areas/Games/Gone/Gone.md", back.pageOf("PPSA00002_00"))

        // Linked elsewhere since: that change stays.
        val moved = linked.linkConsole(GameConsole.XBOX, "402049843", orchard.path)
        assertEquals(moved, GamesLibrary.linkedBack(moved, GameConsole.XBOX, "402049843", glass.path, previous = null))
    }

    // --- The console lines ----------------------------------------------------------------------------

    private fun psnLine(
        connected: Boolean = true,
        status: PsnStatus? = null,
        syncing: Boolean = false,
        readOnly: Boolean = false,
        expires: Long? = null
    ) = PsnLine(configured = true, connected = connected, status = status, expiresInDays = expires, syncing = syncing, readOnly = readOnly)

    private fun xboxLine(connected: Boolean = true, status: XboxStatus? = null, syncing: Boolean = false, readOnly: Boolean = false) =
        XboxLine(configured = true, connected = connected, status = status, syncing = syncing, readOnly = readOnly)

    private val psnFailed = PsnStatus(ok = false, atMillis = 1L, kind = PsnException.Kind.API, message = "Server error")
    private val xboxFailed = XboxStatus(ok = false, atMillis = 1L, kind = XboxException.Kind.API, message = "Server error")

    @Test
    fun `a read-only device says the consoles sync on the phone`() {
        assertEquals("PlayStation syncs on your phone", GamesLines.psn(psnLine(connected = false, readOnly = true), today, zone))
        assertEquals("Xbox syncs on your phone", GamesLines.xbox(xboxLine(connected = false, readOnly = true), today, zone))
        assertFalse(GamesLines.failed(psnLine(readOnly = true, status = psnFailed)))
        assertFalse(GamesLines.failed(xboxLine(readOnly = true, status = xboxFailed)))
    }

    @Test
    fun `a running sync says so, and a sync says its day`() {
        val threeDaysAgo = millis(2026, 11, 24, 23, 40)
        assertEquals(
            "PSN ✓ 24 Nov 23:40 · token expires in 54 days",
            GamesLines.psn(psnLine(status = PsnStatus(ok = true, atMillis = threeDaysAgo), expires = 54), today, zone)
        )
        assertEquals(
            "PSN ✓ yesterday 23:40 · token expires in 1 day",
            GamesLines.psn(psnLine(status = PsnStatus(ok = true, atMillis = millis(2026, 11, 26, 23, 40)), expires = 1), today, zone)
        )
        assertEquals("Xbox ✓ today 06:00", GamesLines.xbox(xboxLine(status = XboxStatus(ok = true, atMillis = millis(2026, 11, 27, 6, 0))), today, zone))
        assertEquals("PSN: syncing…", GamesLines.psn(psnLine(status = psnFailed, syncing = true), today, zone))
        assertEquals("Xbox: syncing…", GamesLines.xbox(xboxLine(status = xboxFailed, syncing = true), today, zone))
    }

    @Test
    fun `only a connected console whose last sync failed is red`() {
        assertTrue(GamesLines.failed(psnLine(status = psnFailed)))
        assertTrue(GamesLines.failed(xboxLine(status = xboxFailed)))
        // Disconnected after a failure: the Connect line is never red.
        assertFalse(GamesLines.failed(psnLine(connected = false, status = psnFailed)))
        assertEquals("Connect PlayStation to bring play time and trophies", GamesLines.psn(psnLine(connected = false, status = psnFailed), today, zone))
        assertFalse(GamesLines.failed(xboxLine(connected = false, status = xboxFailed)))
        // Nor while a sync runs, nor after a good one.
        assertFalse(GamesLines.failed(psnLine(status = psnFailed, syncing = true)))
        assertFalse(GamesLines.failed(psnLine(status = PsnStatus(ok = true, atMillis = 1L))))
        assertEquals("PSN: last sync failed (Server error)", GamesLines.psn(psnLine(status = psnFailed), today, zone))
    }

    @Test
    fun `the lines say when links json can't be read`() {
        val psn = PsnStatus(ok = false, atMillis = 1L, kind = PsnException.Kind.LINKS_UNREADABLE, message = GamesRepository.LINKS_UNREADABLE_MESSAGE)
        val xbox = XboxStatus(ok = false, atMillis = 1L, kind = XboxException.Kind.LINKS_UNREADABLE, message = GamesRepository.LINKS_UNREADABLE_MESSAGE)
        assertEquals("PSN: links.json can't be read: fix it on the desktop", GamesLines.psn(psnLine(status = psn), today, zone))
        assertEquals("Xbox: links.json can't be read: fix it on the desktop", GamesLines.xbox(xboxLine(status = xbox), today, zone))
    }

    private companion object {
        const val PSN_URL = "https://image.api.playstation.com/cdn/UP0001/PPSA00001_00/cover.png"
        const val XBOX_URL = "https://images-eds-ssl.xboxlive.com/image?url=cover"
    }
}

/**
 * The tab's ViewModel (13.4): it reads nothing until the screen watches, a burst of writes loads
 * once, a status that goes away clears its line, midnight moves the tab's day, and a hide leaves its
 * fold at once with an Undo, over mocked repositories.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GamesTabViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val ticks = MutableStateFlow(0)
    private val running = MutableStateFlow<RunningGame?>(null)
    private val checks = MutableStateFlow<SessionCheck?>(null)
    private val psnStatus = MutableStateFlow<PsnStatus?>(null)
    private val xboxStatus = MutableStateFlow<XboxStatus?>(null)
    private val psnSyncing = MutableStateFlow(false)
    private val xboxSyncing = MutableStateFlow(false)
    private val days = MutableStateFlow(LocalDate.of(2026, 11, 27))

    private val glass = GamePage(
        "30_Areas/Games/Glass_Tower/Glass_Tower.md", "Glass Tower", emptyList(), GameTemplate.OTHER, GameState.PLAYING, null, null
    )
    private val orchard = GamePage(
        "30_Areas/Games/Tin_Orchard/Tin_Orchard.md", "Tin Orchard", emptyList(), GameTemplate.OTHER, GameState.BACKLOG, null, null
    )

    private val games: GamesRepository = mockk()
    private val controller: GameSessionController = mockk()
    private val account: PsnAccount = mockk()
    private val xboxAccount: XboxAccount = mockk()
    private val clock: AppClock = mockk()
    private val psnSync: PsnSync = mockk()
    private val xboxSync: XboxSync = mockk()
    private val modes: ModesRepository = mockk()
    private var viewModel: GamesViewModel? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        VaultWriteGate.readOnly = false
        every { games.changes } returns ticks
        coEvery { games.pages() } returns listOf(glass)
        coEvery { games.snapshot() } returns null
        coEvery { games.xboxSnapshot() } returns null
        coEvery { games.linksRead() } returns LinksRead(GameLinks(), readable = true)
        coEvery { games.allSessions() } returns emptyList()
        coEvery { games.coverFiles(any()) } returns emptyMap()
        every { games.resolver(any(), any()) } returns { _: GameSessionRecord -> null }
        every { controller.running } returns running
        every { controller.runningCheck } returns checks
        every { account.configured } returns true
        every { account.status } returns psnStatus
        coEvery { account.tokens() } returns null
        every { xboxAccount.configured } returns true
        every { xboxAccount.status } returns xboxStatus
        coEvery { xboxAccount.isConnected() } returns false
        every { clock.today } returns days
        every { psnSync.syncing } returns psnSyncing
        every { xboxSync.syncing } returns xboxSyncing
        every { modes.visibility } returns MutableStateFlow(Visibility())
    }

    @After
    fun tearDown() {
        viewModel?.cancelAndAwait(testDispatcher.scheduler)
        Dispatchers.resetMain()
        VaultWriteGate.readOnly = false
    }

    private fun viewModel() = GamesViewModel(games, controller, account, xboxAccount, clock, psnSync, xboxSync, modes).also { viewModel = it }

    /** Waits on a real thread: the rows are built on Dispatchers.Default. */
    private suspend fun GamesViewModel.awaitState(predicate: (GamesUiState) -> Boolean): GamesUiState =
        withContext(Dispatchers.Default) { withTimeout(5_000) { state.first { predicate(it) } } }

    @Test
    fun `nothing is read until the tab watches, then a load reads the pages once`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        // No collector at construction: a tab left in the back stack reads nothing.
        coVerify(exactly = 0) { games.pages() }
        assertTrue(vm.state.value.loading)

        backgroundScope.launch { vm.watch() }
        val loaded = vm.awaitState { !it.loading }

        assertEquals(listOf(glass), loaded.pages)
        assertEquals(LocalDate.of(2026, 11, 27), loaded.today)
        // The game played last comes from that one read of the pages, never a second (the controller's).
        assertEquals(glass.path, loaded.lastPlayedPath)
        coVerify(exactly = 1) { games.pages() }
        coVerify(exactly = 0) { controller.defaultPage(any()) }
    }

    @Test
    fun `a burst of writes loads once, after the last`() = runTest(testDispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.watch() }
        vm.awaitState { !it.loading }
        coEvery { games.pages() } returns listOf(glass, orchard)

        // A sync's writes, one after the other.
        ticks.value = 1
        ticks.value = 2
        ticks.value = 3

        assertEquals(2, vm.awaitState { it.pages.size == 2 }.pages.size)
        coVerify(exactly = 2) { games.pages() }
    }

    @Test
    fun `a status that goes away clears its line`() = runTest(testDispatcher) {
        psnStatus.value = PsnStatus(ok = false, atMillis = 1L, kind = PsnException.Kind.API, message = "Server error")
        val vm = viewModel()
        backgroundScope.launch { vm.watch() }
        assertEquals(PsnException.Kind.API, vm.awaitState { it.psn.status != null }.psn.status?.kind)

        // Disconnect writes no status: the line forgets the failure instead of keeping it.
        psnStatus.value = null
        assertNull(vm.awaitState { !it.loading && it.psn.status == null }.psn.status)
    }

    @Test
    fun `midnight moves the tab's day`() = runTest(testDispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.watch() }
        assertEquals(LocalDate.of(2026, 11, 27), vm.awaitState { !it.loading }.today)

        // Nothing else changed, yet a state of its own: "today" and "yesterday" follow it.
        days.value = LocalDate.of(2026, 11, 28)
        assertEquals(LocalDate.of(2026, 11, 28), vm.awaitState { it.today == LocalDate.of(2026, 11, 28) }.today)
    }

    @Test
    fun `a hide leaves the fold at once, keeps the link and offers Undo`() = runTest(testDispatcher) {
        val title = UnlinkedTitle("PPSA00001_00", "Glass Tower", 2.0, null, GameConsole.PSN)
        val changes = mutableListOf<(GameLinks) -> GameLinks>()
        coEvery { games.updateLinks(any()) } answers {
            val change = firstArg<(GameLinks) -> GameLinks>()
            changes += change
            change(GameLinks())
        }
        val vm = viewModel()

        vm.hide(title)
        // At once, before the write runs: the row is out of its fold.
        assertTrue(title.foldKey in vm.state.value.pendingHidden)
        advanceUntilIdle()

        val message = vm.messages.first()
        assertEquals("Glass Tower hidden", message.text)
        assertEquals(GamesUndo.Unhide(title), message.undo)
        // The write hides the title and keeps a link it had (13.4): a hide made while the notes' mirror was empty deletes none.
        val linked = GameLinks(links = mapOf(title.titleId to glass.path))
        val hidden = changes.single()(linked)
        assertTrue(hidden.isHidden(GameConsole.PSN, title.titleId))
        assertEquals(glass.path, hidden.pageOf(title.titleId))

        vm.undo(GamesUndo.Unhide(title))
        assertFalse(title.foldKey in vm.state.value.pendingHidden)
        advanceUntilIdle()
        val back = changes.last()(hidden)
        assertFalse(back.isHidden(GameConsole.PSN, title.titleId))
        assertEquals(glass.path, back.pageOf(title.titleId))
    }

    @Test
    fun `a refused hide comes back and says why`() = runTest(testDispatcher) {
        val title = UnlinkedTitle("1234567890", "Tin Orchard", null, null, GameConsole.XBOX)
        coEvery { games.updateLinks(any()) } returns null
        coEvery { games.linksRead() } returns LinksRead(GameLinks(), readable = false)
        val vm = viewModel()

        vm.hide(title)
        advanceUntilIdle()

        assertFalse(title.foldKey in vm.state.value.pendingHidden)
        assertEquals(GamesLines.LINKS_UNREADABLE, vm.messages.first().text)
    }
}
