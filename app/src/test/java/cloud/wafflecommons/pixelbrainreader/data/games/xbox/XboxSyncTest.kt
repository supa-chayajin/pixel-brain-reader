package cloud.wafflecommons.pixelbrainreader.data.games.xbox

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.AchievementRecord
import cloud.wafflecommons.pixelbrainreader.data.games.AchievementSection
import cloud.wafflecommons.pixelbrainreader.data.games.AchievementSetFile
import cloud.wafflecommons.pixelbrainreader.data.games.ConsoleLinks
import cloud.wafflecommons.pixelbrainreader.data.games.GameConsole
import cloud.wafflecommons.pixelbrainreader.data.games.GameLinks
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GamePages
import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionRecord
import cloud.wafflecommons.pixelbrainreader.data.games.GameState
import cloud.wafflecommons.pixelbrainreader.data.games.GameTemplate
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
import cloud.wafflecommons.pixelbrainreader.data.games.LinksRead
import cloud.wafflecommons.pixelbrainreader.data.games.NewGamePage
import cloud.wafflecommons.pixelbrainreader.data.games.XboxSnapshot
import cloud.wafflecommons.pixelbrainreader.data.games.XboxTitleState
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.GitSyncCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The Xbox sync's order and its failure rules (XB-D), with the Xbox client, the sign-in and the vault mocked. */
class XboxSyncTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val now = Instant.parse("2026-11-27T22:40:00Z").toEpochMilli()

    /** The writes happen when the sync starts (13.4: the day of the writes is the clock's). */
    private val clock: () -> Long = { now }
    private val xuid = "2533274800000001"
    private val xbl = XboxSession("UHS", "XSTS", xuid, expiresAt = now + 8 * 3_600_000L)
    private val ring = "1915865634"
    private val bricks = "1234567890"
    private val page = GamePage(
        "30_Areas/Games/Ring_of_Ash/Ring_of_Ash.md", "Ring of Ash", emptyList(), GameTemplate.SOULS_LIKE, GameState.PLAYING, null, null
    )

    private fun title(
        id: String,
        name: String,
        earned: Int = 0,
        score: Int = 0,
        lastPlayed: String = "2026-11-27T21:13:45.4411936Z",
        source: Int = 2
    ) = XboxTitle(
        titleId = id,
        name = name,
        type = "Game",
        mediaItemType = "Application",
        displayImage = "https://store-images.s-microsoft.com/image/apps.$id",
        achievement = XboxTitleAchievement(currentAchievements = earned, currentGamerscore = score, sourceVersion = source),
        titleHistory = XboxTitleHistory(lastTimePlayed = lastPlayed)
    )

    private val account: XboxAccount = mockk(relaxUnitFun = true) {
        every { configured } returns true
        coEvery { isConnected() } returns true
        coEvery { session(any(), any()) } returns xbl
        coEvery { baselinePending() } returns false
    }
    private val client: XboxClient = mockk {
        coEvery { titleHistory(any()) } returns XboxTitleList(listOf(title(ring, "RING OF ASH")), full = false)
        coEvery { minutesPlayed(any(), any()) } returns mapOf(ring to 1220L)
        coEvery { achievements(any(), any(), any()) } returns emptyList()
    }
    private val games: GamesRepository = mockk(relaxed = true) {
        coEvery { pages() } returns listOf(page)
        coEvery { links() } returns GameLinks()
        coEvery { linksRead() } returns LinksRead(GameLinks(), readable = true)
        coEvery { updateLinks(any()) } returns GameLinks()
        coEvery { xboxSnapshot() } returns null
        coEvery { saveXboxSnapshot(any()) } returns true
        coEvery { achievementSet(any()) } returns null
        coEvery { saveAchievementSet(any()) } returns true
        coEvery { addSessions(any()) } returns emptySet()
        coEvery { notePathsUnder(any()) } returns emptyList()
        coEvery { otherTemplate() } returns null
        coEvery { createPages(any()) } answers { firstArg<List<NewGamePage>>().mapNotNull { GamePages.parse(it.plan.path, it.content) } }
        coEvery { editPage(any(), any()) } returns true
    }
    private val dashboard: DailyDashboardRepository = mockk(relaxed = true)
    private val sync = XboxSync(account, client, games, GitSyncCoordinator(), dashboard)

    @Before
    fun setUp() {
        VaultWriteGate.readOnly = false
    }

    @After
    fun tearDown() {
        VaultWriteGate.readOnly = false
    }

    @Test
    fun `the first sync records a baseline and no session`() = runTest {
        val result = sync.sync(now, zone, writeClock = clock)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().titles)
        coVerify { games.addSessions(emptyList()) }
        coVerify {
            games.saveXboxSnapshot(match { s ->
                val t = s.titles.single()
                s.syncedAt == "2026-11-27T22:40:00Z" && s.complete && t.minutesPlayed == 1220L &&
                    t.lastPlayed == "2026-11-27T21:13:45.441193600Z" && t.imageUrl == "https://store-images.s-microsoft.com/image/apps.$ring"
            })
        }
        coVerify { account.setStatus(XboxStatus(true, now)) }
    }

    @Test
    fun `the next sync turns the minutes added into a session, then saves the snapshot last`() = runTest {
        coEvery { games.xboxSnapshot() } returns XboxSnapshot(
            "2026-11-26T22:40:00Z",
            titles = listOf(XboxTitleState(ring, "RING OF ASH", achievementSource = 2, minutesPlayed = 1100))
        )
        val added = slot<List<GameSessionRecord>>()
        coEvery { games.addSessions(capture(added)) } returns setOf(LocalDate.of(2026, 11, 27))

        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)

        val session = added.captured.single()
        assertEquals(120, session.minutes)
        assertEquals("2026-11-27", session.date)
        assertEquals("20:13", session.start)
        assertEquals("xbox:$ring:2026-11-26T22:40:00Z", session.id)
        assertEquals(GameSessionRecord.SOURCE_XBOX, session.source)
        coVerifyOrder {
            games.createPages(any())
            games.updateLinks(any())
            games.addSessions(any())
            games.saveXboxSnapshot(any())
        }
        // Today's note gets its line at once (13.4: the sync may run after the 23:50 burn); no past day here.
        coVerify(exactly = 0) { games.applyPastDay(any(), any()) }
        coVerifyOrder {
            dashboard.ensureDay(LocalDate.of(2026, 11, 27))
            dashboard.burnToDisk(LocalDate.of(2026, 11, 27))
        }
    }

    @Test
    fun `a new sign-in's first sync is a baseline, whatever snapshot it finds`() = runTest {
        // The snapshot from before a Disconnect: the play since is not one session per title.
        coEvery { games.xboxSnapshot() } returns XboxSnapshot(
            "2026-10-01T22:40:00Z",
            titles = listOf(XboxTitleState(ring, "RING OF ASH", achievementSource = 2, minutesPlayed = 100))
        )

        val result = sync.sync(now, zone, baseline = true, writeClock = clock)

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow().sessions)
        coVerify { games.addSessions(emptyList()) }
        // Everything else is saved as usual, the snapshot (where the count starts) last.
        coVerifyOrder {
            games.createPages(any())
            games.updateLinks(any())
            games.saveXboxSnapshot(match { s -> s.syncedAt == "2026-11-27T22:40:00Z" && s.titles.single().minutesPlayed == 1220L })
            // The baseline ends once its snapshot is on disk.
            account.baselineSaved()
        }
        coVerify(exactly = 0) { dashboard.ensureDay(any()) }
        coVerify { account.setStatus(XboxStatus(true, now)) }

        // The nightly runs and Sync now pass no baseline: the same snapshot gives the play since as a session.
        val added = slot<List<GameSessionRecord>>()
        coEvery { games.addSessions(capture(added)) } returns setOf(LocalDate.of(2026, 11, 27))
        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)
        assertEquals(1120, added.captured.single().minutes)
        coVerify(exactly = 1) { account.baselineSaved() }
    }

    @Test
    fun `a baseline whose first sync didn't finish makes the next run one, until a snapshot is saved`() = runTest {
        // Connect's first sync stopped (a 429, or the page left): the sign-in's mark is still there.
        coEvery { account.baselinePending() } returns true
        coEvery { games.xboxSnapshot() } returns XboxSnapshot(
            "2026-10-01T22:40:00Z",
            titles = listOf(XboxTitleState(ring, "RING OF ASH", achievementSource = 2, minutesPlayed = 100))
        )
        coEvery { client.titleHistory(any()) } throws XboxException(XboxException.Kind.RATE_LIMITED, "Xbox asked to wait.")

        assertTrue(sync.sync(now, zone, writeClock = clock).isFailure)
        coVerify(exactly = 0) { games.saveXboxSnapshot(any()) }
        coVerify(exactly = 0) { account.baselineSaved() }

        // The nightly run (no baseline passed) is the baseline: the month since the Disconnect is no session.
        coEvery { client.titleHistory(any()) } returns XboxTitleList(listOf(title(ring, "RING OF ASH")), full = false)
        coEvery { games.saveXboxSnapshot(any()) } returns false
        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)
        coVerify { games.addSessions(emptyList()) }
        // A snapshot that couldn't be written ends nothing: the next run is a baseline again.
        coVerify(exactly = 0) { account.baselineSaved() }

        coEvery { games.saveXboxSnapshot(any()) } returns true
        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)
        coVerify(exactly = 0) { games.addSessions(match { it.isNotEmpty() }) }
        coVerifyOrder {
            games.saveXboxSnapshot(any())
            account.baselineSaved()
        }
    }

    @Test
    fun `a baseline keeps no minutes from before it`() = runTest {
        // The minutes batch met a 429: the title's count before the new sign-in isn't carried into the baseline.
        coEvery { games.xboxSnapshot() } returns XboxSnapshot(
            "2026-10-01T22:40:00Z",
            titles = listOf(XboxTitleState(ring, "RING OF ASH", achievementSource = 2, minutesPlayed = 100))
        )
        coEvery { client.minutesPlayed(any(), any()) } returns emptyMap()
        val saved = mutableListOf<XboxSnapshot>()
        coEvery { games.saveXboxSnapshot(capture(saved)) } returns true

        assertTrue(sync.sync(now, zone, baseline = true, writeClock = clock).isSuccess)
        assertNull(saved.last().titles.single().minutesPlayed)

        // An ordinary run keeps the last count for a title it couldn't read: no play is lost or made up.
        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)
        assertEquals(100L, saved.last().titles.single().minutesPlayed)
    }

    @Test
    fun `sessions that can't be saved keep the old baseline`() = runTest {
        coEvery { games.xboxSnapshot() } returns XboxSnapshot(
            "2026-11-26T22:40:00Z",
            titles = listOf(XboxTitleState(ring, "RING OF ASH", achievementSource = 2, minutesPlayed = 1100))
        )
        coEvery { games.addSessions(any()) } returns null

        val result = sync.sync(now, zone, writeClock = clock)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { games.saveXboxSnapshot(any()) }
        coVerify { account.setStatus(match { !it.ok && it.kind == XboxException.Kind.API }) }
    }

    @Test
    fun `a refused sign-in pauses Xbox and writes nothing`() = runTest {
        coEvery { account.session(any(), any()) } throws
            XboxException(XboxException.Kind.SIGN_IN_EXPIRED, "The Xbox sign-in has expired: Connect again.")

        val result = sync.sync(now, zone, writeClock = clock)

        assertTrue(result.isFailure)
        coVerify { account.setStatus(match { !it.ok && it.needsSignIn && it.message == "The Xbox sign-in has expired: Connect again." }) }
        coVerify(exactly = 0) { games.saveXboxSnapshot(any()) }
        coVerify(exactly = 0) { games.addSessions(any()) }
        coVerify(exactly = 0) { games.updateLinks(any()) }
        coVerify(exactly = 0) { games.createPages(any()) }
        coVerify(exactly = 0) { games.saveAchievementSet(any()) }
        coVerify(exactly = 0) { games.editPage(any(), any()) }
    }

    @Test
    fun `a read-only device never syncs`() = runTest {
        VaultWriteGate.readOnly = true

        assertTrue(sync.sync(now, zone, writeClock = clock).isFailure)

        coVerify(exactly = 0) { account.session(any(), any()) }
        coVerify(exactly = 0) { client.titleHistory(any()) }
        coVerify(exactly = 0) { games.saveXboxSnapshot(any()) }
        coVerify(exactly = 0) { account.setStatus(any()) }
    }

    @Test
    fun `without a sign-in nothing runs and no status is written`() = runTest {
        coEvery { account.isConnected() } returns false

        val error = sync.sync(now, zone, writeClock = clock).exceptionOrNull() as XboxException

        assertEquals(XboxException.Kind.NOT_CONFIGURED, error.kind)
        coVerify(exactly = 0) { client.titleHistory(any()) }
        coVerify(exactly = 0) { account.setStatus(any()) }
    }

    @Test
    fun `a played title no page claims gets a page and its achievements in the same sync`() = runTest {
        coEvery { client.titleHistory(any()) } returns XboxTitleList(
            listOf(title(ring, "RING OF ASH"), title(bricks, "Brick Wars™", earned = 1, score = 10, lastPlayed = "2026-11-27T20:00:00Z")),
            full = false
        )
        coEvery { games.notePathsUnder("30_Areas/Games") } returns listOf(page.path)
        coEvery { client.achievements(any(), bricks, 2) } returns listOf(
            FetchedAchievement("1", "First Brick", "Lay a brick.", 10, secret = false, earned = true, unlockedAt = Instant.parse("2026-11-02T20:10:00Z")),
            FetchedAchievement("2", "Last Brick", "Lay them all.", 90, secret = true, earned = false, unlockedAt = null)
        )
        val written = slot<List<NewGamePage>>()
        coEvery { games.createPages(capture(written)) } answers {
            firstArg<List<NewGamePage>>().mapNotNull { GamePages.parse(it.plan.path, it.content) }
        }
        val change = slot<(GameLinks) -> GameLinks>()
        coEvery { games.updateLinks(capture(change)) } returns GameLinks()

        val result = sync.sync(now, zone, writeClock = clock)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().pagesCreated)
        val made = written.captured.single()
        assertEquals("30_Areas/Games/Brick_Wars/Brick_Wars.md", made.plan.path)
        assertEquals("Brick Wars", made.plan.title)
        assertEquals(listOf("Brick Wars™"), made.plan.aliases)
        assertEquals(listOf(bricks), made.plan.titleIds)
        assertEquals(GameConsole.XBOX, made.plan.console)
        // Started: its first unlock, before the last time it was played.
        assertTrue(made.content.contains("\nstarted: '2026-11-02'\n"))
        assertTrue(made.content.contains("\ngame_match: exact\n"))
        // Its achievements were read in this sync, the unearned one's name kept in the file only.
        coVerify {
            games.saveAchievementSet(match {
                it.titleId == bricks && it.achievements.size == 2 && it.earnedCount == 1 &&
                    it.achievements[0].earnedAt == "2026-11-02T20:10:00Z" && it.achievements[1].name == "Last Brick"
            })
        }
        // The links written: Ring of Ash by its name, the new page by its id; what the owner did meanwhile stays.
        val linked = change.captured(GameLinks())
        assertEquals(page.path, linked.consolePage(GameConsole.XBOX, ring))
        assertEquals(made.plan.path, linked.consolePage(GameConsole.XBOX, bricks))
        assertTrue(linked.links.isEmpty())
        val elsewhere = GameLinks(xbox = ConsoleLinks(links = mapOf(bricks to "elsewhere.md")))
        assertEquals("elsewhere.md", change.captured(elsewhere).consolePage(GameConsole.XBOX, bricks))
        assertNull(change.captured(GameLinks(xbox = ConsoleLinks(hidden = listOf(bricks)))).consolePage(GameConsole.XBOX, bricks))
        // The snapshot takes the file's totals (titlehub's read 0 for a One title).
        coVerify {
            games.saveXboxSnapshot(match { s ->
                s.titles.first { it.titleId == bricks }.let { it.totalAchievements == 2 && it.totalGamerscore == 100 }
            })
        }
        // The pages were read again after the new one was written.
        coVerify(atLeast = 2) { games.pages() }
    }

    @Test
    fun `one title's failed achievements don't stop the sync`() = runTest {
        coEvery { client.titleHistory(any()) } returns XboxTitleList(
            listOf(title(ring, "RING OF ASH", lastPlayed = "2026-11-27T21:00:00Z"), title(bricks, "Brick Wars", lastPlayed = "2026-11-26T21:00:00Z")),
            full = false
        )
        coEvery { games.linksRead() } returns LinksRead(GameLinks(xbox = ConsoleLinks(links = mapOf(bricks to "30_Areas/Games/Brick_Wars/Brick_Wars.md"))), readable = true)
        coEvery { client.achievements(any(), ring, any()) } throws XboxException(XboxException.Kind.API, "The Xbox network answered HTTP 500.")
        coEvery { client.achievements(any(), bricks, any()) } returns listOf(
            FetchedAchievement("1", "First Brick", "", 10, secret = false, earned = true, unlockedAt = Instant.parse("2026-11-02T20:10:00Z"))
        )

        val result = sync.sync(now, zone, writeClock = clock)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().achievementSets)
        coVerify { games.saveAchievementSet(match { it.titleId == bricks }) }
        coVerify(exactly = 0) { games.saveAchievementSet(match { it.titleId == ring }) }
        coVerify { games.saveXboxSnapshot(any()) }
        coVerify { account.setStatus(XboxStatus(true, now)) }
    }

    @Test
    fun `an empty achievements answer keeps the file it would replace`() = runTest {
        // A 360 title's file: an unlock made offline was dated by the sync that first saw it, a date Xbox never gives again.
        val file = AchievementSetFile(
            ring, "Ring of Ash", 1,
            listOf(
                AchievementRecord("1", "Old Hero", "Beat chapter one.", 20, earnedAt = "2026-11-02T20:10:00Z"),
                AchievementRecord("2", "Secret Ending", "", 50, secret = true)
            )
        )
        coEvery { games.linksRead() } returns LinksRead(GameLinks(xbox = ConsoleLinks(links = mapOf(ring to page.path))), readable = true)
        coEvery { games.achievementSet(ring) } returns file
        // titlehub counts one more earned: the title moved, its achievements are asked again, and both lists come back empty.
        coEvery { client.titleHistory(any()) } returns
            XboxTitleList(listOf(title(ring, "RING OF ASH", earned = 2, score = 70, source = 1)), full = false)
        coEvery { client.achievements(any(), ring, 1) } returns emptyList()

        val result = sync.sync(now, zone, writeClock = clock)

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow().achievementSets)
        coVerify { client.achievements(any(), ring, 1) }
        coVerify(exactly = 0) { games.saveAchievementSet(any()) }
        // The page keeps its section, from the file kept, and the rest of the sync goes on.
        coVerify { games.editPage(page.path, any()) }
        coVerify { games.saveXboxSnapshot(any()) }
        coVerify { account.setStatus(XboxStatus(true, now)) }
    }

    @Test
    fun `too many requests stops reading achievements, the rest is saved`() = runTest {
        coEvery { client.titleHistory(any()) } returns XboxTitleList(
            listOf(title(ring, "RING OF ASH", lastPlayed = "2026-11-27T21:00:00Z"), title(bricks, "Brick Wars", lastPlayed = "2026-11-26T21:00:00Z")),
            full = false
        )
        coEvery { games.linksRead() } returns LinksRead(GameLinks(xbox = ConsoleLinks(links = mapOf(bricks to "30_Areas/Games/Brick_Wars/Brick_Wars.md"))), readable = true)
        // Ring of Ash, played last, is asked first.
        coEvery { client.achievements(any(), ring, any()) } throws
            XboxException(XboxException.Kind.RATE_LIMITED, "The Xbox network asks to wait (too many requests).")

        val result = sync.sync(now, zone, writeClock = clock)

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { client.achievements(any(), bricks, any()) }
        coVerify(exactly = 0) { games.saveAchievementSet(any()) }
        coVerify { games.saveXboxSnapshot(any()) }
        coVerify { account.setStatus(XboxStatus(true, now)) }
    }

    @Test
    fun `a 401 renews the session once`() = runTest {
        val renewed = XboxSession("UHS", "XSTS-2", xuid, now + 8 * 3_600_000L)
        coEvery { account.session(any(), true) } returns renewed
        coEvery { client.titleHistory(xbl) } throws XboxException(XboxException.Kind.TOKEN_EXPIRED, "The Xbox network refused the sign-in token.")
        coEvery { client.titleHistory(renewed) } returns XboxTitleList(listOf(title(ring, "RING OF ASH")), full = false)

        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)

        coVerify(exactly = 1) { account.session(any(), true) }
        coVerify { client.titleHistory(renewed) }

        // Refused again after the renewal: the sign-in has ended, and nothing more is written.
        coEvery { client.titleHistory(renewed) } throws XboxException(XboxException.Kind.TOKEN_EXPIRED, "The Xbox network refused the sign-in token.")
        val again = sync.sync(now, zone, writeClock = clock).exceptionOrNull() as XboxException
        assertEquals(XboxException.Kind.SIGN_IN_EXPIRED, again.kind)
        coVerify(exactly = 1) { games.saveXboxSnapshot(any()) }
        coVerify { account.setStatus(match { it.needsSignIn }) }
    }

    @Test
    fun `titles the history no longer lists are kept`() = runTest {
        val old = XboxTitleState(
            "987654321", "Outlaw Trail 2", achievementSource = 2, minutesPlayed = 600, lastPlayed = "2019-02-21T20:00:00Z", totalAchievements = 40
        )
        coEvery { games.xboxSnapshot() } returns XboxSnapshot(
            "2026-11-26T22:40:00Z",
            complete = false,
            titles = listOf(XboxTitleState(ring, "RING OF ASH", achievementSource = 2, minutesPlayed = 1220), old)
        )
        coEvery { games.linksRead() } returns LinksRead(GameLinks(xbox = ConsoleLinks(hidden = listOf("987654321"))), readable = true)
        // The history came back full: older titles fell off it.
        coEvery { client.titleHistory(any()) } returns XboxTitleList(listOf(title(ring, "RING OF ASH")), full = true)

        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)

        coVerify {
            games.saveXboxSnapshot(match { s -> !s.complete && s.titles.map { it.titleId } == listOf(ring, "987654321") && s.titles[1] == old })
        }
        coVerify { games.addSessions(emptyList()) }
    }

    @Test
    fun `a page gets its achievements section from its files`() = runTest {
        val file = AchievementSetFile(
            ring, "Ring of Ash", 2,
            listOf(
                AchievementRecord("1", "First Steps", "Finish the prologue.", 10, earnedAt = "2026-11-02T20:10:00Z"),
                AchievementRecord("2", "The Hidden Boss", "Find it.", 50, secret = true),
                AchievementRecord("3", "Lord of Ash", "", 30, earnedAt = "2026-11-01T19:00:00Z")
            )
        )
        coEvery { games.linksRead() } returns LinksRead(GameLinks(xbox = ConsoleLinks(links = mapOf(ring to page.path))), readable = true)
        coEvery { games.achievementSet(ring) } returns file
        // titlehub counts what the file holds: nothing to read again.
        coEvery { client.titleHistory(any()) } returns XboxTitleList(listOf(title(ring, "RING OF ASH", earned = 2, score = 40)), full = false)
        val change = slot<(String) -> String>()
        coEvery { games.editPage(page.path, capture(change)) } returns true

        val result = sync.sync(now, zone, writeClock = clock)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().pages)
        coVerify(exactly = 0) { client.achievements(any(), any(), any()) }
        val icon = AchievementSection.ICON
        val before = "---\ntitle: \"Ring of Ash\"\ngame_template: souls-like\n---\n# Ring of Ash\n\n" +
            "- [ ] Beat the first knight <!--achievement:$ring#1-->\n- [ ] Find the boss <!--achievement:$ring#2-->\n"
        assertEquals(
            "---\ntitle: \"Ring of Ash\"\ngame_template: souls-like\n---\n# Ring of Ash\n\n" +
                "- [x] Beat the first knight ✅ 2026-11-02 <!--achievement:$ring#1-->\n- [ ] Find the boss <!--achievement:$ring#2-->\n\n" +
                AchievementSection.HEADING + "\n\n*2 of 3 achievements · 40 of 90 G · 66 %*\n\n" +
                "- $icon Lord of Ash — 2026-11-01 · 30 G\n- $icon First Steps — 2026-11-02 · 10 G\n",
            change.captured(before)
        )
    }

    // --- 13.4 ------------------------------------------------------------------------------

    private val tower = "1915865690"
    private val lastSync = XboxSnapshot(
        "2026-11-26T22:40:00Z",
        titles = listOf(XboxTitleState(ring, "RING OF ASH", achievementSource = 2, minutesPlayed = 1100))
    )

    @Test
    fun `an images-eds address becomes images-eds-ssl`() = runTest {
        coEvery { games.xboxSnapshot() } returns XboxSnapshot(
            "2026-11-26T22:40:00Z",
            titles = listOf(XboxTitleState(bricks, "Brick Wars", imageUrl = "https://store-images.s-microsoft.com/image/apps.kept"))
        )
        coEvery { client.titleHistory(any()) } returns XboxTitleList(
            listOf(
                title(ring, "RING OF ASH").copy(displayImage = "http://images-eds.xboxlive.com/image?url=AbC%2F&format=png"),
                // A cleartext address on another host is no picture: the one kept stays.
                title(bricks, "Brick Wars").copy(displayImage = "http://example.com/icon.png")
            ),
            full = false
        )

        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)

        coVerify {
            games.saveXboxSnapshot(match { s ->
                s.titles.first { it.titleId == ring }.imageUrl == "https://images-eds-ssl.xboxlive.com/image?url=AbC%2F&format=png" &&
                    s.titles.first { it.titleId == bricks }.imageUrl == "https://store-images.s-microsoft.com/image/apps.kept"
            })
        }
    }

    @Test
    fun `an unreadable links json keeps the play and says so`() = runTest {
        coEvery { games.xboxSnapshot() } returns lastSync
        coEvery { games.linksRead() } returns LinksRead(GameLinks(), readable = false)
        val added = slot<List<GameSessionRecord>>()
        coEvery { games.addSessions(capture(added)) } returns setOf(LocalDate.of(2026, 11, 27))

        val result = sync.sync(now, zone, writeClock = clock)

        assertEquals(XboxException.Kind.LINKS_UNREADABLE, (result.exceptionOrNull() as XboxException).kind)
        assertEquals(120, added.captured.single().minutes)
        coVerify { games.saveXboxSnapshot(any()) }
        coVerify(exactly = 0) { games.createPages(any()) }
        coVerify(exactly = 0) { games.updateLinks(any()) }
        coVerify(exactly = 0) { games.editPage(any(), any()) }
        coVerify(exactly = 0) { client.achievements(any(), any(), any()) }
        coVerify {
            account.setStatus(match {
                !it.ok && it.kind == XboxException.Kind.LINKS_UNREADABLE && it.message == GamesRepository.LINKS_UNREADABLE_MESSAGE &&
                    !it.needsOwner && !it.needsSignIn
            })
        }
    }

    @Test
    fun `planned titles read their achievements first`() = runTest {
        // Brick Wars, linked by hand and played last; Glass Tower, about to get its page, played a week ago.
        coEvery { client.titleHistory(any()) } returns XboxTitleList(
            listOf(
                title(bricks, "Brick Wars", earned = 1, score = 10, lastPlayed = "2026-11-27T21:00:00Z"),
                title(tower, "Glass Tower", earned = 1, score = 10, lastPlayed = "2026-11-20T21:00:00Z")
            ),
            full = false
        )
        coEvery { games.linksRead() } returns
            LinksRead(GameLinks(xbox = ConsoleLinks(links = mapOf(bricks to "30_Areas/Games/Brick_Wars/Brick_Wars.md"))), readable = true)
        coEvery { client.achievements(any(), any(), any()) } returns
            listOf(FetchedAchievement("1", "Ground Floor", "", 10, secret = false, earned = true, unlockedAt = Instant.parse("2026-03-01T12:00:00Z")))
        val written = slot<List<NewGamePage>>()
        coEvery { games.createPages(capture(written)) } answers { firstArg<List<NewGamePage>>().mapNotNull { GamePages.parse(it.plan.path, it.content) } }

        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)

        coVerifyOrder {
            client.achievements(any(), tower, any())
            client.achievements(any(), bricks, any())
        }
        // Its page starts on its first unlock, read in this sync.
        assertTrue(written.captured.single().content.contains("\nstarted: '2026-03-01'\n"))
    }

    @Test
    fun `a created page's start waits for its achievements`() = runTest {
        coEvery { client.titleHistory(any()) } returns XboxTitleList(
            listOf(title(tower, "Glass Tower", earned = 3, score = 30, lastPlayed = "2026-11-27T21:00:00Z")),
            full = false
        )
        // Its achievements couldn't be read: the start isn't guessed from the last played day.
        coEvery { client.achievements(any(), tower, any()) } throws XboxException(XboxException.Kind.API, "The Xbox network answered HTTP 500.")
        val written = slot<List<NewGamePage>>()
        coEvery { games.createPages(capture(written)) } answers { firstArg<List<NewGamePage>>().mapNotNull { GamePages.parse(it.plan.path, it.content) } }

        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)
        assertFalse(written.captured.single().content.contains("started:"))

        // A title without an earned achievement starts on its last played day, as before.
        coEvery { client.titleHistory(any()) } returns XboxTitleList(
            listOf(title(tower, "Glass Tower", lastPlayed = "2026-11-27T21:00:00Z")),
            full = false
        )
        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)
        assertTrue(written.captured.single().content.contains("\nstarted: '2026-11-27'\n"))
    }

    @Test
    fun `an exact page's start moves to its first unlock once`() = runTest {
        val towerPage = GamePage(
            "30_Areas/Games/Glass_Tower/Glass_Tower.md", "Glass Tower", emptyList(), GameTemplate.OTHER, GameState.BACKLOG,
            LocalDate.of(2026, 11, 27), null, exactName = true
        )
        coEvery { games.pages() } returns listOf(page, towerPage)
        coEvery { games.linksRead() } returns LinksRead(GameLinks(xbox = ConsoleLinks(links = mapOf(tower to towerPage.path))), readable = true)
        coEvery { client.titleHistory(any()) } returns XboxTitleList(
            listOf(title(tower, "Glass Tower", earned = 1, score = 10, lastPlayed = "2026-11-27T21:00:00Z")),
            full = false
        )
        coEvery { client.achievements(any(), tower, 2) } returns
            listOf(FetchedAchievement("1", "Ground Floor", "", 10, secret = false, earned = true, unlockedAt = Instant.parse("2026-03-01T12:00:00Z")))
        val edits = mutableListOf<(String) -> String>()
        coEvery { games.editPage(towerPage.path, capture(edits)) } returns true

        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)

        // The page as the sync made it: started on its last played day.
        val created = "---\ntitle: \"Glass Tower\"\ngame_template: other\ngame_match: exact\nstarted: '2026-11-27'\n---\n# Glass Tower\n"
        // The start's edit comes first, before the page's section.
        val startEdit = edits.first()
        val moved = startEdit(created)
        assertEquals(created.replace("started: '2026-11-27'", "started: '2026-03-01'"), moved)
        // Once moved it stays, and an owner's date is never touched.
        assertEquals(moved, startEdit(moved))
        val owners = created.replace("started: '2026-11-27'", "started: '2026-10-15'")
        assertEquals(owners, startEdit(owners))
        // A page the owner made (no exact mark) keeps its start: no edit of it.
        coEvery { games.pages() } returns listOf(page, towerPage.copy(exactName = false))
        edits.clear()
        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)
        assertTrue(edits.isEmpty())
    }

    @Test
    fun `a sync writing after the burn burns today`() = runTest {
        coEvery { games.xboxSnapshot() } returns lastSync
        coEvery { games.addSessions(any()) } returns setOf(LocalDate.of(2026, 11, 27))
        // Started at 23:40, writing at 23:55: the 23:50 burn has already run.
        assertTrue(sync.sync(now, zone, writeClock = { now + 15 * 60_000L }).isSuccess)
        coVerifyOrder {
            games.addSessions(any())
            dashboard.ensureDay(LocalDate.of(2026, 11, 27))
            dashboard.burnToDisk(LocalDate.of(2026, 11, 27))
            games.saveXboxSnapshot(any())
        }
        coVerify(exactly = 0) { games.applyPastDay(any(), any()) }
        // Writing after midnight: the evening is a past day.
        assertTrue(sync.sync(now, zone, writeClock = { now + 25 * 60_000L }).isSuccess)
        coVerify { games.applyPastDay(LocalDate.of(2026, 11, 27), LocalDate.of(2026, 11, 28)) }
    }

    @Test
    fun `a newcomer seen without its minutes keeps the previous sync's time`() = runTest {
        coEvery { games.xboxSnapshot() } returns lastSync
        coEvery { client.titleHistory(any()) } returns XboxTitleList(
            listOf(title(ring, "RING OF ASH"), title(bricks, "Brick Wars", lastPlayed = "2026-11-27T21:00:00Z")),
            full = false
        )
        // Its batch met a 429: no minutes for the new title.
        coEvery { client.minutesPlayed(any(), any()) } returns mapOf(ring to 1220L)
        val saved = mutableListOf<XboxSnapshot>()
        coEvery { games.saveXboxSnapshot(capture(saved)) } returns true

        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)

        val newcomer = saved.last().titles.first { it.titleId == bricks }
        assertNull(newcomer.minutesPlayed)
        assertEquals("2026-11-26T22:40:00Z", newcomer.newcomerSince)
        // A title seen before, or a baseline, keeps none.
        assertNull(saved.last().titles.first { it.titleId == ring }.newcomerSince)
        assertTrue(sync.sync(now, zone, baseline = true, writeClock = clock).isSuccess)
        assertNull(saved.last().titles.first { it.titleId == bricks }.newcomerSince)
    }

    @Test
    fun `a title hidden or linked during the sync gets no page`() = runTest {
        coEvery { client.titleHistory(any()) } returns XboxTitleList(
            listOf(title(ring, "RING OF ASH"), title(bricks, "Brick Wars", lastPlayed = "2026-11-27T20:00:00Z")),
            full = false
        )
        val written = mutableListOf<List<NewGamePage>>()
        coEvery { games.createPages(capture(written)) } returns emptyList()
        // Planned from the links read first, hidden from the fold while the achievements were read.
        coEvery { games.linksRead() } returnsMany
            listOf(LinksRead(GameLinks(), true), LinksRead(GameLinks(xbox = ConsoleLinks(hidden = listOf(bricks))), true))
        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)
        assertTrue(written.last().isEmpty())
        // Linked to a page by hand meanwhile.
        coEvery { games.linksRead() } returnsMany
            listOf(LinksRead(GameLinks(), true), LinksRead(GameLinks(xbox = ConsoleLinks(links = mapOf(bricks to page.path))), true))
        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)
        assertTrue(written.last().isEmpty())
        // Nothing meanwhile: the page is made.
        coEvery { games.linksRead() } returns LinksRead(GameLinks(), true)
        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)
        assertEquals(listOf("30_Areas/Games/Brick_Wars/Brick_Wars.md"), written.last().map { it.plan.path })
    }

    @Test
    fun `the syncing flag is up while a sync runs`() = runTest {
        var during: Boolean? = null
        coEvery { games.addSessions(any()) } answers {
            during = sync.syncing.value
            emptySet()
        }
        assertFalse(sync.syncing.value)
        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)
        assertEquals(true, during)
        assertFalse(sync.syncing.value)
        // A failed sync takes it down too.
        coEvery { client.titleHistory(any()) } throws XboxException(XboxException.Kind.NETWORK, "No connection to the Xbox network.")
        assertTrue(sync.sync(now, zone, writeClock = clock).isFailure)
        assertFalse(sync.syncing.value)
    }
}
