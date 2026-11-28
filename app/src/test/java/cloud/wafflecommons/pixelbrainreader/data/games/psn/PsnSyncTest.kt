package cloud.wafflecommons.pixelbrainreader.data.games.psn

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.GameLinks
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GamePages
import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionRecord
import cloud.wafflecommons.pixelbrainreader.data.games.GameState
import cloud.wafflecommons.pixelbrainreader.data.games.GameTemplate
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
import cloud.wafflecommons.pixelbrainreader.data.games.LinksRead
import cloud.wafflecommons.pixelbrainreader.data.games.NewGamePage
import cloud.wafflecommons.pixelbrainreader.data.games.PsnSnapshot
import cloud.wafflecommons.pixelbrainreader.data.games.PsnTitleState
import cloud.wafflecommons.pixelbrainreader.data.sync.GitSyncCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The sync's order and its failure rules (RFC-009 §7), with the PSN client and the vault mocked. */
class PsnSyncTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val now = Instant.parse("2026-11-14T22:40:00Z").toEpochMilli()

    /** The writes happen when the sync starts (13.4: the day of the writes is the clock's). */
    private val clock: () -> Long = { now }
    private val page = GamePage("30_Areas/Games/Ring/Ring.md", "Ring of Ash", emptyList(), GameTemplate.SOULS_LIKE, GameState.PLAYING, null, null)

    private val account: PsnAccount = mockk(relaxUnitFun = true) {
        coEvery { configured } returns true
        coEvery { isConnected() } returns true
        coEvery { accessToken(any(), any()) } returns "ACCESS"
        coEvery { baselinePending() } returns false
    }
    private val client: PsnClient = mockk {
        coEvery { playedGames("ACCESS") } returns listOf(
            PsnPlayedTitle(titleId = "PPSA1", name = "RING OF ASH", category = "ps5_native_game", playDuration = "PT12H", lastPlayedDateTime = "2026-11-14T22:30:00Z")
        )
        coEvery { trophyTitles("ACCESS") } returns emptyList()
        coEvery { setsForTitles("ACCESS", any()) } returns emptyMap()
    }
    private val games: GamesRepository = mockk(relaxed = true) {
        coEvery { pages() } returns listOf(page)
        coEvery { links() } returns GameLinks()
        coEvery { linksRead() } returns LinksRead(GameLinks(), readable = true)
        coEvery { updateLinks(any()) } returns GameLinks()
        coEvery { trophySet(any()) } returns null
        coEvery { addSessions(any()) } returns emptySet()
        coEvery { saveSnapshot(any()) } returns true
        // 13.3 (XB4): pages for played games.
        coEvery { notePathsUnder(any()) } returns emptyList()
        coEvery { otherTemplate() } returns null
        coEvery { createPages(any()) } answers { firstArg<List<NewGamePage>>().mapNotNull { GamePages.parse(it.plan.path, it.content) } }
    }
    private val dashboard: cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository = mockk(relaxed = true)
    private val sync = PsnSync(account, client, games, GitSyncCoordinator(), dashboard)

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
        coEvery { games.snapshot() } returns null
        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)
        coVerify { games.addSessions(emptyList()) }
        coVerify { games.saveSnapshot(match { it.titles.single().playSeconds == 12 * 3600L }) }
        coVerify { account.setStatus(PsnStatus(true, now)) }
    }

    @Test
    fun `the next sync turns the play added into a session, then saves the new baseline`() = runTest {
        coEvery { games.snapshot() } returns PsnSnapshot(
            "2026-11-13T22:40:00Z",
            listOf(PsnTitleState("PPSA1", "RING OF ASH", playSeconds = 10 * 3600L, trophyLookedUp = true))
        )
        val added = slot<List<GameSessionRecord>>()
        coEvery { games.addSessions(capture(added)) } returns setOf(LocalDate.of(2026, 11, 14))

        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)

        val session = added.captured.single()
        assertEquals(120, session.minutes)
        assertEquals("2026-11-14", session.date)
        assertEquals("21:30", session.start)
        coVerify { games.saveSnapshot(any()) }
        // A title already looked up isn't asked for its trophies again.
        coVerify(exactly = 0) { client.setsForTitles(any(), any()) }
        // Today's note gets its line at once (13.4: the sync may run after the 23:50 burn); no past day here.
        coVerify(exactly = 0) { games.applyPastDay(any(), any()) }
        coVerifyOrder {
            games.addSessions(any())
            dashboard.ensureDay(LocalDate.of(2026, 11, 14))
            dashboard.burnToDisk(LocalDate.of(2026, 11, 14))
            games.saveSnapshot(any())
        }
    }

    @Test
    fun `a baseline whose first sync didn't finish makes the next run one, until a snapshot is saved`() = runTest {
        // Connect's first sync stopped (no network, or the page left): the sign-in's mark is still there.
        coEvery { account.baselinePending() } returns true
        coEvery { games.snapshot() } returns PsnSnapshot(
            "2026-10-01T22:40:00Z",
            listOf(PsnTitleState("PPSA1", "RING OF ASH", playSeconds = 2 * 3600L, trophyLookedUp = true))
        )
        coEvery { client.playedGames("ACCESS") } throws PsnException(PsnException.Kind.NETWORK, "No network.")

        assertTrue(sync.sync(now, zone, writeClock = clock).isFailure)
        coVerify(exactly = 0) { games.saveSnapshot(any()) }
        coVerify(exactly = 0) { account.baselineSaved() }

        // The nightly run (no baseline passed) is the baseline: the weeks since the Disconnect are no session.
        coEvery { client.playedGames("ACCESS") } returns listOf(
            PsnPlayedTitle(titleId = "PPSA1", name = "RING OF ASH", category = "ps5_native_game", playDuration = "PT12H", lastPlayedDateTime = "2026-11-14T22:30:00Z")
        )
        coEvery { games.saveSnapshot(any()) } returns false
        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)
        coVerify { games.addSessions(emptyList()) }
        // A snapshot that couldn't be written ends nothing: the next run is a baseline again.
        coVerify(exactly = 0) { account.baselineSaved() }

        coEvery { games.saveSnapshot(any()) } returns true
        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)
        coVerify(exactly = 0) { games.addSessions(match { it.isNotEmpty() }) }
        coVerifyOrder {
            games.saveSnapshot(any())
            account.baselineSaved()
        }
    }

    @Test
    fun `a new sign-in's first sync is a baseline, whatever snapshot it finds`() = runTest {
        // The snapshot from before a Disconnect: the play since is not one session per title.
        coEvery { games.snapshot() } returns PsnSnapshot(
            "2026-10-01T22:40:00Z",
            listOf(PsnTitleState("PPSA1", "RING OF ASH", playSeconds = 2 * 3600L, trophyLookedUp = true))
        )

        val result = sync.sync(now, zone, baseline = true, writeClock = clock)

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow().sessions)
        coVerify { games.addSessions(emptyList()) }
        // Everything else is saved as usual, the snapshot (where the count starts) last.
        coVerifyOrder {
            games.createPages(any())
            games.updateLinks(any())
            games.saveSnapshot(match { it.syncedAt == "2026-11-14T22:40:00Z" && it.titles.single().playSeconds == 12 * 3600L })
            // The baseline ends once its snapshot is on disk.
            account.baselineSaved()
        }
        coVerify(exactly = 0) { dashboard.ensureDay(any()) }
        coVerify { account.setStatus(PsnStatus(true, now)) }

        // The nightly runs and Sync now pass no baseline: the same snapshot gives the play since as a session.
        val added = slot<List<GameSessionRecord>>()
        coEvery { games.addSessions(capture(added)) } returns setOf(LocalDate.of(2026, 11, 14))
        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)
        assertEquals(600, added.captured.single().minutes)
    }

    @Test
    fun `sessions that can't be saved keep the old baseline, so the next run replays them`() = runTest {
        coEvery { games.snapshot() } returns PsnSnapshot("2026-11-13T22:40:00Z", listOf(PsnTitleState("PPSA1", "RING OF ASH", playSeconds = 0L)))
        coEvery { games.addSessions(any()) } returns null

        val result = sync.sync(now, zone, writeClock = clock)

        assertFalse(result.isSuccess)
        coVerify(exactly = 0) { games.saveSnapshot(any()) }
        coVerify { account.setStatus(match { !it.ok && it.kind == PsnException.Kind.API }) }
    }

    @Test
    fun `a refused sign-in pauses PSN and writes nothing to the vault`() = runTest {
        coEvery { account.accessToken(any(), any()) } throws PsnException(PsnException.Kind.TOKEN_EXPIRED, "expired")
        val result = sync.sync(now, zone, writeClock = clock)
        assertFalse(result.isSuccess)
        coVerify { account.setStatus(match { !it.ok && it.needsOwner }) }
        coVerify(exactly = 0) { games.saveSnapshot(any()) }
        coVerify(exactly = 0) { games.addSessions(any()) }
    }

    @Test
    fun `a read-only device never syncs`() = runTest {
        VaultWriteGate.readOnly = true
        assertFalse(sync.sync(now, zone, writeClock = clock).isSuccess)
        coVerify(exactly = 0) { client.playedGames(any()) }
        coVerify(exactly = 0) { games.saveSnapshot(any()) }
    }

    @Test
    fun `a title no page claims is linked by its name`() = runTest {
        coEvery { games.snapshot() } returns null
        val change = slot<(GameLinks) -> GameLinks>()
        coEvery { games.updateLinks(capture(change)) } returns GameLinks()
        sync.sync(now, zone, writeClock = clock)
        assertEquals(page.path, change.captured(GameLinks()).pageOf("PPSA1"))
        assertEquals(null, change.captured(GameLinks(hidden = listOf("PPSA1"))).pageOf("PPSA1"))
    }

    // --- 13.3 (XB4): pages for played games ----------------------------------------------------

    private val tower = PsnPlayedTitle(
        titleId = "PPSA2", name = "GLASS TOWER™", category = "ps5_native_game", playDuration = "PT3H",
        firstPlayedDateTime = "2026-03-01T10:00:00Z", lastPlayedDateTime = "2026-11-13T21:00:00Z",
        concept = PsnConcept(titleIds = listOf("PPSA2", "CUSA2"))
    )

    @Test
    fun `a played title no page claims gets a page, linked in the same sync`() = runTest {
        coEvery { client.playedGames("ACCESS") } returns listOf(
            PsnPlayedTitle(titleId = "PPSA1", name = "RING OF ASH", playDuration = "PT12H", lastPlayedDateTime = "2026-11-14T22:30:00Z"),
            tower
        )
        coEvery { games.snapshot() } returns null
        coEvery { games.notePathsUnder("30_Areas/Games") } returns listOf(page.path)
        val written = slot<List<NewGamePage>>()
        coEvery { games.createPages(capture(written)) } answers { firstArg<List<NewGamePage>>().mapNotNull { GamePages.parse(it.plan.path, it.content) } }
        val change = slot<(GameLinks) -> GameLinks>()
        coEvery { games.updateLinks(capture(change)) } returns GameLinks()

        val result = sync.sync(now, zone, writeClock = clock)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().pagesCreated)
        val made = written.captured.single()
        assertEquals("30_Areas/Games/GLASS_TOWER/GLASS_TOWER.md", made.plan.path)
        assertEquals("GLASS TOWER", made.plan.title)
        assertEquals(listOf("PPSA2"), made.plan.titleIds)
        assertEquals(listOf("GLASS TOWER™"), made.plan.aliases)
        assertTrue(made.content.contains("\nstarted: '2026-03-01'\n"))
        assertTrue(made.content.contains("\ngame_match: exact\n"))
        // The links written: Ring of Ash by its name, the new page by its id; what the owner linked meanwhile stays.
        val linked = change.captured(GameLinks())
        assertEquals(page.path, linked.pageOf("PPSA1"))
        assertEquals(made.plan.path, linked.pageOf("PPSA2"))
        assertEquals("elsewhere.md", change.captured(GameLinks(links = mapOf("PPSA2" to "elsewhere.md"))).pageOf("PPSA2"))
        assertEquals(null, change.captured(GameLinks(hidden = listOf("PPSA2"))).pageOf("PPSA2"))
        // The page was read again after it was written.
        coVerify(atLeast = 2) { games.pages() }
    }

    @Test
    fun `a hidden title or a title linked to a deleted page gets none`() = runTest {
        coEvery { client.playedGames("ACCESS") } returns listOf(
            tower,
            PsnPlayedTitle(titleId = "PPSA3", name = "OUTLAW TRAIL 2", playDuration = "PT5H"),
            PsnPlayedTitle(titleId = "PPSA4", name = "CORAL REEF", playDuration = "PT0S")
        )
        coEvery { games.snapshot() } returns null
        coEvery { games.linksRead() } returns
            LinksRead(GameLinks(links = mapOf("PPSA3" to "30_Areas/Games/Deleted/Deleted.md"), hidden = listOf("PPSA2")), readable = true)

        val result = sync.sync(now, zone, writeClock = clock)

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow().pagesCreated)
        // Coral Reef was never played: no page either.
        coVerify { games.createPages(emptyList()) }
        coVerify(exactly = 0) { games.otherTemplate() }
    }

    @Test
    fun `the trophies of a page created in this sync are read in it`() = runTest {
        coEvery { client.playedGames("ACCESS") } returns listOf(tower)
        coEvery { client.setsForTitles("ACCESS", any()) } returns mapOf(
            "PPSA2" to listOf(PsnTrophyTitle(npServiceName = "trophy2", npCommunicationId = "NPWR00002_00", trophyTitleName = "Glass Tower"))
        )
        coEvery { client.trophies("ACCESS", "NPWR00002_00", "trophy2") } returns
            PsnTrophyList(trophies = listOf(PsnTrophy(1, trophyType = "bronze", trophyName = "First Floor")))
        coEvery { client.earned("ACCESS", "NPWR00002_00", "trophy2") } returns
            PsnTrophyList(trophies = listOf(PsnTrophy(1, earned = true, earnedDateTime = "2026-11-13T20:00:00Z")))
        coEvery { games.snapshot() } returns null
        val template = "---\ntitle: \"{{title}}\"\ngame_template: other\n---\n# {{title}}\n"
        coEvery { games.otherTemplate() } returns template
        val written = slot<List<NewGamePage>>()
        coEvery { games.createPages(capture(written)) } answers { firstArg<List<NewGamePage>>().mapNotNull { GamePages.parse(it.plan.path, it.content) } }

        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)

        coVerify { games.saveTrophySet(match { it.set == "NPWR00002_00" && it.trophies.single().earnedAt == "2026-11-13T20:00:00Z" }) }
        // The page came from the vault's template, its aliases where the vault keeps them.
        assertEquals(
            "---\ntitle: \"GLASS TOWER\"\naliases:\n  - \"GLASS TOWER™\"\ngame_template: other\ngame_match: exact\n" +
                "started: '2026-03-01'\ngame_state: backlog\n---\n# GLASS TOWER\n",
            written.captured.single().content
        )
    }

    // --- 13.4 (GA3): the day of the writes, an unreadable links.json, a token mid-sync, every trophy list ---

    private val played = PsnSnapshot("2026-11-13T22:40:00Z", listOf(PsnTitleState("PPSA1", "RING OF ASH", playSeconds = 10 * 3600L, trophyLookedUp = true)))

    @Test
    fun `a sync writing after the burn burns today`() = runTest {
        coEvery { games.snapshot() } returns played
        coEvery { games.addSessions(any()) } returns setOf(LocalDate.of(2026, 11, 14))
        // Started at 23:40, writing at 23:55: the 23:50 burn has already run.
        assertTrue(sync.sync(now, zone, writeClock = { now + 15 * 60_000L }).isSuccess)
        coVerifyOrder {
            games.addSessions(any())
            dashboard.ensureDay(LocalDate.of(2026, 11, 14))
            dashboard.burnToDisk(LocalDate.of(2026, 11, 14))
            games.saveSnapshot(any())
        }
        coVerify(exactly = 0) { games.applyPastDay(any(), any()) }
    }

    @Test
    fun `a sync started before midnight and writing after edits yesterday's note`() = runTest {
        coEvery { games.snapshot() } returns played
        coEvery { games.addSessions(any()) } returns setOf(LocalDate.of(2026, 11, 14))
        // Started at 23:40, its writes at 00:05: the evening is now a past day.
        assertTrue(sync.sync(now, zone, writeClock = { now + 25 * 60_000L }).isSuccess)
        coVerify { games.applyPastDay(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15)) }
        coVerify(exactly = 0) { dashboard.ensureDay(any()) }
        coVerify(exactly = 0) { dashboard.burnToDisk(any()) }
    }

    @Test
    fun `an unreadable links json creates no page, writes no link, keeps the play and says so`() = runTest {
        coEvery { client.playedGames("ACCESS") } returns listOf(
            PsnPlayedTitle(titleId = "PPSA1", name = "RING OF ASH", playDuration = "PT12H", lastPlayedDateTime = "2026-11-14T22:30:00Z"),
            tower
        )
        coEvery { games.snapshot() } returns played
        coEvery { games.linksRead() } returns LinksRead(GameLinks(), readable = false)
        val added = slot<List<GameSessionRecord>>()
        coEvery { games.addSessions(capture(added)) } returns setOf(LocalDate.of(2026, 11, 14))

        val result = sync.sync(now, zone, writeClock = clock)

        assertEquals(PsnException.Kind.LINKS_UNREADABLE, (result.exceptionOrNull() as PsnException).kind)
        // The play is kept: its session and the snapshot are saved, nothing counted twice next time.
        assertEquals(120, added.captured.single().minutes)
        coVerify { games.saveSnapshot(any()) }
        // Nothing linked, hidden or created, no page's section rewritten.
        coVerify(exactly = 0) { games.createPages(any()) }
        coVerify(exactly = 0) { games.updateLinks(any()) }
        coVerify(exactly = 0) { games.editPage(any(), any()) }
        coVerify(exactly = 0) { games.otherTemplate() }
        coVerify {
            account.setStatus(match {
                !it.ok && it.kind == PsnException.Kind.LINKS_UNREADABLE && it.message == GamesRepository.LINKS_UNREADABLE_MESSAGE &&
                    !it.needsOwner && !it.needsNpsso
            })
        }
    }

    @Test
    fun `a 401 after the game list refreshes once and the sync succeeds`() = runTest {
        coEvery { games.snapshot() } returns null
        coEvery { account.accessToken(any(), true) } returns "ACCESS-2"
        // The token had a minute left when the sync began; it ran out after the game list.
        coEvery { client.trophyTitles("ACCESS") } throws PsnException(PsnException.Kind.TOKEN_EXPIRED, "The PlayStation access token was refused.")
        coEvery { client.trophyTitles("ACCESS-2") } returns emptyList()
        coEvery { client.setsForTitles("ACCESS-2", any()) } returns emptyMap()

        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)

        coVerify(exactly = 1) { account.accessToken(any(), true) }
        // The new token is kept for the rest of the sync.
        coVerify { client.setsForTitles("ACCESS-2", listOf("PPSA1")) }
        coVerify(exactly = 0) { client.setsForTitles("ACCESS", any()) }
        coVerify { account.setStatus(PsnStatus(true, now)) }
    }

    @Test
    fun `a second 401 still pauses PSN`() = runTest {
        coEvery { account.accessToken(any(), true) } returns "ACCESS-2"
        coEvery { client.trophyTitles(any()) } throws PsnException(PsnException.Kind.TOKEN_EXPIRED, "The PlayStation access token was refused.")

        val result = sync.sync(now, zone, writeClock = clock)

        assertEquals(PsnException.Kind.TOKEN_EXPIRED, (result.exceptionOrNull() as PsnException).kind)
        coVerify(exactly = 1) { account.accessToken(any(), true) }
        coVerify { account.setStatus(match { !it.ok && it.needsNpsso }) }
        coVerify(exactly = 0) { games.saveSnapshot(any()) }
        coVerify(exactly = 0) { games.addSessions(any()) }
    }

    @Test
    fun `every trophy list of a title is read and listed`() = runTest {
        coEvery { games.snapshot() } returns null
        coEvery { games.linksRead() } returns LinksRead(GameLinks(links = mapOf("PPSA1" to page.path)), readable = true)
        // One title id, two lists: a collection's two games.
        coEvery { client.setsForTitles("ACCESS", listOf("PPSA1")) } returns mapOf(
            "PPSA1" to listOf(
                PsnTrophyTitle(npServiceName = "trophy2", npCommunicationId = "NPWR00010_00", trophyTitleName = "Glass Tower"),
                PsnTrophyTitle(npServiceName = "trophy2", npCommunicationId = "NPWR00011_00", trophyTitleName = "Tin Orchard")
            )
        )
        coEvery { client.trophies("ACCESS", any(), "trophy2") } returns
            PsnTrophyList(trophies = listOf(PsnTrophy(1, trophyType = "bronze", trophyName = "First Floor")))
        coEvery { client.earned("ACCESS", any(), "trophy2") } returns
            PsnTrophyList(trophies = listOf(PsnTrophy(1, earned = true, earnedDateTime = "2026-11-13T20:00:00Z")))

        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)

        coVerify { games.saveTrophySet(match { it.set == "NPWR00010_00" && it.title == "Glass Tower" && it.trophies.single().earnedAt == "2026-11-13T20:00:00Z" }) }
        coVerify { games.saveTrophySet(match { it.set == "NPWR00011_00" && it.title == "Tin Orchard" }) }
        coVerify {
            games.saveSnapshot(match { s ->
                s.titles.single().let { it.trophySets == listOf("NPWR00010_00", "NPWR00011_00") && it.trophySet == "NPWR00010_00" }
            })
        }
        // The page's section counts both lists.
        coVerify { games.trophySet("NPWR00011_00") }
    }

    @Test
    fun `a title kept with one list is asked once more`() = runTest {
        // Saved by 13.3: its one list, looked up, no list of lists.
        val old = PsnTitleState("PPSA1", "RING OF ASH", playSeconds = 12 * 3600L, trophySet = "NPWR00010_00", trophyLookedUp = true)
        coEvery { games.snapshot() } returns PsnSnapshot("2026-11-13T22:40:00Z", listOf(old))
        coEvery { client.setsForTitles("ACCESS", listOf("PPSA1")) } returns mapOf(
            "PPSA1" to listOf(PsnTrophyTitle(npCommunicationId = "NPWR00011_00"), PsnTrophyTitle(npCommunicationId = "NPWR00010_00"))
        )
        // Linked by its name, its lists are read too.
        coEvery { client.trophies("ACCESS", any(), any()) } returns PsnTrophyList(trophies = listOf(PsnTrophy(1, trophyType = "bronze", trophyName = "First Floor")))
        coEvery { client.earned("ACCESS", any(), any()) } returns PsnTrophyList()
        val saved = mutableListOf<PsnSnapshot>()
        coEvery { games.saveSnapshot(capture(saved)) } returns true

        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)

        // The list it had stays first (what an older app reads), the other joins it.
        assertEquals(listOf("NPWR00010_00", "NPWR00011_00"), saved.last().titles.single().trophySets)
        assertEquals("NPWR00010_00", saved.last().titles.single().trophySet)
        // The next sync, the lists known, asks nothing.
        coEvery { games.snapshot() } returns saved.last()
        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)
        coVerify(exactly = 1) { client.setsForTitles(any(), any()) }
        // Asked again with nothing in the answer, it keeps its one list and isn't asked a third time.
        coEvery { games.snapshot() } returns PsnSnapshot("2026-11-13T22:40:00Z", listOf(old))
        coEvery { client.setsForTitles("ACCESS", listOf("PPSA1")) } returns emptyMap()
        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)
        assertEquals(listOf("NPWR00010_00"), saved.last().titles.single().trophySets)
        coEvery { games.snapshot() } returns saved.last()
        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)
        coVerify(exactly = 2) { client.setsForTitles(any(), any()) }
    }

    @Test
    fun `a title hidden or linked during the sync gets no page`() = runTest {
        coEvery { client.playedGames("ACCESS") } returns listOf(tower)
        coEvery { games.snapshot() } returns null
        coEvery { games.notePathsUnder("30_Areas/Games") } returns listOf(page.path)
        val written = mutableListOf<List<NewGamePage>>()
        coEvery { games.createPages(capture(written)) } returns emptyList()
        // Planned from the links read first, hidden from the fold while the trophies were read.
        coEvery { games.linksRead() } returnsMany listOf(LinksRead(GameLinks(), true), LinksRead(GameLinks(hidden = listOf("PPSA2")), true))
        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)
        assertTrue(written.last().isEmpty())
        // Linked to a page by hand meanwhile.
        coEvery { games.linksRead() } returnsMany listOf(LinksRead(GameLinks(), true), LinksRead(GameLinks(links = mapOf("PPSA2" to page.path)), true))
        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)
        assertTrue(written.last().isEmpty())
        // Nothing meanwhile: the page is made.
        coEvery { games.linksRead() } returns LinksRead(GameLinks(), true)
        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)
        assertEquals(listOf("30_Areas/Games/GLASS_TOWER/GLASS_TOWER.md"), written.last().map { it.plan.path })
    }

    @Test
    fun `a replay's moved day is applied again`() = runTest {
        coEvery { games.snapshot() } returns played
        // The record the replay replaced was dated the 12th: that day's note loses its stale line.
        coEvery { games.addSessions(any()) } returns setOf(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 12))
        assertTrue(sync.sync(now, zone, writeClock = clock).isSuccess)
        coVerify { games.applyPastDay(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 14)) }
        coVerify { dashboard.burnToDisk(LocalDate.of(2026, 11, 14)) }
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
        coEvery { client.playedGames("ACCESS") } throws PsnException(PsnException.Kind.NETWORK, "No network.")
        assertTrue(sync.sync(now, zone, writeClock = clock).isFailure)
        assertFalse(sync.syncing.value)
    }
}
