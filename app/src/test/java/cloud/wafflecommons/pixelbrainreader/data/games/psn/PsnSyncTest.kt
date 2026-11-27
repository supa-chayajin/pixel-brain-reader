package cloud.wafflecommons.pixelbrainreader.data.games.psn

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.GameLinks
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GamePages
import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionRecord
import cloud.wafflecommons.pixelbrainreader.data.games.GameState
import cloud.wafflecommons.pixelbrainreader.data.games.GameTemplate
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
import cloud.wafflecommons.pixelbrainreader.data.games.NewGamePage
import cloud.wafflecommons.pixelbrainreader.data.games.PsnSnapshot
import cloud.wafflecommons.pixelbrainreader.data.games.PsnTitleState
import cloud.wafflecommons.pixelbrainreader.data.sync.GitSyncCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
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
    private val page = GamePage("30_Areas/Games/Ring/Ring.md", "Ring of Ash", emptyList(), GameTemplate.SOULS_LIKE, GameState.PLAYING, null, null)

    private val account: PsnAccount = mockk(relaxUnitFun = true) {
        coEvery { configured } returns true
        coEvery { isConnected() } returns true
        coEvery { accessToken(any(), any()) } returns "ACCESS"
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
        assertTrue(sync.sync(now, zone).isSuccess)
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

        assertTrue(sync.sync(now, zone).isSuccess)

        val session = added.captured.single()
        assertEquals(120, session.minutes)
        assertEquals("2026-11-14", session.date)
        assertEquals("21:30", session.start)
        coVerify { games.saveSnapshot(any()) }
        // A title already looked up isn't asked for its trophies again.
        coVerify(exactly = 0) { client.setsForTitles(any(), any()) }
        // Today's line waits for the burn, on a board that now exists: only a past day's note is edited here.
        coVerify(exactly = 0) { games.applyPastDay(any(), any()) }
        coVerify { dashboard.ensureDay(LocalDate.of(2026, 11, 14)) }
    }

    @Test
    fun `sessions that can't be saved keep the old baseline, so the next run replays them`() = runTest {
        coEvery { games.snapshot() } returns PsnSnapshot("2026-11-13T22:40:00Z", listOf(PsnTitleState("PPSA1", "RING OF ASH", playSeconds = 0L)))
        coEvery { games.addSessions(any()) } returns null

        val result = sync.sync(now, zone)

        assertFalse(result.isSuccess)
        coVerify(exactly = 0) { games.saveSnapshot(any()) }
        coVerify { account.setStatus(match { !it.ok && it.kind == PsnException.Kind.API }) }
    }

    @Test
    fun `a refused sign-in pauses PSN and writes nothing to the vault`() = runTest {
        coEvery { account.accessToken(any(), any()) } throws PsnException(PsnException.Kind.TOKEN_EXPIRED, "expired")
        val result = sync.sync(now, zone)
        assertFalse(result.isSuccess)
        coVerify { account.setStatus(match { !it.ok && it.needsOwner }) }
        coVerify(exactly = 0) { games.saveSnapshot(any()) }
        coVerify(exactly = 0) { games.addSessions(any()) }
    }

    @Test
    fun `a read-only device never syncs`() = runTest {
        VaultWriteGate.readOnly = true
        assertFalse(sync.sync(now, zone).isSuccess)
        coVerify(exactly = 0) { client.playedGames(any()) }
        coVerify(exactly = 0) { games.saveSnapshot(any()) }
    }

    @Test
    fun `a title no page claims is linked by its name`() = runTest {
        coEvery { games.snapshot() } returns null
        val change = slot<(GameLinks) -> GameLinks>()
        coEvery { games.updateLinks(capture(change)) } returns GameLinks()
        sync.sync(now, zone)
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

        val result = sync.sync(now, zone)

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
        coEvery { games.links() } returns GameLinks(links = mapOf("PPSA3" to "30_Areas/Games/Deleted/Deleted.md"), hidden = listOf("PPSA2"))

        val result = sync.sync(now, zone)

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

        assertTrue(sync.sync(now, zone).isSuccess)

        coVerify { games.saveTrophySet(match { it.set == "NPWR00002_00" && it.trophies.single().earnedAt == "2026-11-13T20:00:00Z" }) }
        // The page came from the vault's template, its aliases where the vault keeps them.
        assertEquals(
            "---\ntitle: \"GLASS TOWER\"\naliases:\n  - \"GLASS TOWER™\"\ngame_template: other\ngame_match: exact\n" +
                "started: '2026-03-01'\ngame_state: backlog\n---\n# GLASS TOWER\n",
            written.captured.single().content
        )
    }
}
