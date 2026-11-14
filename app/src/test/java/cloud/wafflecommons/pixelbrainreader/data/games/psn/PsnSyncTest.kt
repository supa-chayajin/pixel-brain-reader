package cloud.wafflecommons.pixelbrainreader.data.games.psn

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.GameLinks
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionRecord
import cloud.wafflecommons.pixelbrainreader.data.games.GameState
import cloud.wafflecommons.pixelbrainreader.data.games.GameTemplate
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
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
}
