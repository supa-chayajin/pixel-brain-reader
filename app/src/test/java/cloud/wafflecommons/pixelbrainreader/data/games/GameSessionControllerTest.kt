package cloud.wafflecommons.pixelbrainreader.data.games

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesConfig
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Starting and stopping a session by hand, and the game night it may start (D7, D13). */
class GameSessionControllerTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val page = GamePage("30_Areas/Games/Ring/Ring.md", "Ring of Ash", emptyList(), GameTemplate.SOULS_LIKE, GameState.PLAYING, null, null)

    /** Saturday 14 November 2026, 21:00, and Wednesday the 11th at 10:00 (work hours). */
    private val saturdayEvening = ZonedDateTime.of(2026, 11, 14, 21, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val wednesdayMorning = ZonedDateTime.of(2026, 11, 11, 10, 0, 0, 0, zone).toInstant().toEpochMilli()

    private val session = MutableStateFlow<String?>(null)
    private val rule = MutableStateFlow<String?>(null)
    private val check = MutableStateFlow<String?>(null)
    private val preferences: UserPreferencesRepository = mockk(relaxed = true) {
        every { gameSession } returns session
        every { gameNightRule } returns rule
        every { gameSessionCheck } returns check
        coEvery { setGameSession(any()) } answers { session.value = firstArg() }
        coEvery { setGameNightRule(any()) } answers { rule.value = firstArg() }
        coEvery { setGameSessionCheck(any()) } answers { check.value = firstArg() }
    }
    private val games: GamesRepository = mockk(relaxed = true) {
        coEvery { addSessions(any()) } answers { firstArg<List<GameSessionRecord>>().map { LocalDate.parse(it.date) }.toSet() }
        coEvery { page(any()) } returns null
    }
    private val platform: GamePlatform = mockk(relaxed = true) {
        every { canGuard() } returns true
        every { startGameNight(any()) } returns "rule-1"
    }
    private val modes: ModesRepository = mockk {
        coEvery { currentConfig() } returns ModesConfig()
        coEvery { currentVisibility() } returns Visibility(mode = ContextMode.PERSONAL)
    }
    private val dashboard: DailyDashboardRepository = mockk(relaxed = true)
    private val controller = GameSessionController(preferences, games, platform, modes, dashboard)

    @Before
    fun setUp() {
        VaultWriteGate.readOnly = false
    }

    @After
    fun tearDown() {
        VaultWriteGate.readOnly = false
    }

    @Test
    fun `a Perso evening turns the game night on and remembers the day`() = runTest {
        assertEquals(GameNightOutcome.ON, controller.start(page, saturdayEvening, zone))
        assertEquals(RunningGame(saturdayEvening, page.path), controller.current())
        verify { platform.postRunning(any(), "Ring of Ash") }
        coVerify { preferences.setGameNightDay(LocalDate.of(2026, 11, 14)) }
        assertEquals("rule-1", rule.value)
    }

    @Test
    fun `without Do Not Disturb access the session runs and the owner is asked`() = runTest {
        every { platform.canGuard() } returns false
        assertEquals(GameNightOutcome.NEEDS_ACCESS, controller.start(page, saturdayEvening, zone))
        verify(exactly = 0) { platform.startGameNight(any()) }
        assertEquals(RunningGame(saturdayEvening, page.path), controller.current())
    }

    @Test
    fun `never in Off, never in work hours`() = runTest {
        coEvery { modes.currentVisibility() } returns Visibility(mode = ContextMode.OFF)
        assertEquals(GameNightOutcome.NONE, controller.start(page, saturdayEvening, zone))
        coEvery { modes.currentVisibility() } returns Visibility(mode = ContextMode.PERSONAL)
        coEvery { modes.currentConfig() } returns ModesConfig(scheduleEnabled = true)
        assertEquals(GameNightOutcome.NONE, controller.start(page, wednesdayMorning, zone))
        verify(exactly = 0) { platform.startGameNight(any()) }
        coVerify(exactly = 0) { preferences.setGameNightDay(any()) }
    }

    @Test
    fun `a stop keeps the session, ends the game night and writes today's note`() = runTest {
        controller.start(page, saturdayEvening, zone)
        val record = controller.stop(saturdayEvening + 95 * 60_000L, zone)!!
        assertEquals(95, record.minutes)
        assertEquals("21:00", record.start)
        assertNull(controller.current())
        verify { platform.cancelRunning() }
        verify { platform.endGameNight("rule-1") }
        coVerify { dashboard.ensureDay(LocalDate.of(2026, 11, 14)) }
        coVerify { dashboard.burnToDisk(LocalDate.of(2026, 11, 14)) }
        coVerify(exactly = 0) { games.applyPastDay(any(), any()) }
    }

    @Test
    fun `a session that ran past midnight goes to the evening it started`() = runTest {
        controller.start(page, saturdayEvening, zone)
        controller.stop(saturdayEvening + 4 * 3_600_000L, zone)
        coVerify { games.applyPastDay(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15)) }
        coVerify(exactly = 0) { dashboard.burnToDisk(any()) }
    }

    @Test
    fun `a read-only device starts nothing`() = runTest {
        VaultWriteGate.readOnly = true
        assertNull(controller.start(page, saturdayEvening, zone))
        assertNull(controller.current())
        verify(exactly = 0) { platform.postRunning(any(), any()) }
    }

    @Test
    fun `the game played last is the latest of the sessions and PSN's last-played times`() = runTest {
        val other = GamePage("30_Areas/Games/Bricks/Bricks.md", "Brick Wars", emptyList(), GameTemplate.LEGO, GameState.BACKLOG, null, null)
        coEvery { games.pages() } returns listOf(other, page)
        coEvery { games.links() } returns GameLinks(links = mapOf("PPSA1" to other.path))
        every { games.resolver(any(), any()) } returns { record: GameSessionRecord -> listOf(other, page).firstOrNull { it.path == record.page } }
        val today = LocalDate.now(zone)
        coEvery { games.sessionsBetween(any(), any()) } returns listOf(
            GameSessionRecord("m1", today.minusDays(2).toString(), "21:00", 60, GameSessionRecord.SOURCE_MANUAL, page = page.path)
        )
        // PSN saw Brick Wars yesterday: later than the manual session two days ago.
        val yesterday = today.minusDays(1).atTime(20, 0).atZone(zone).toInstant().toString()
        coEvery { games.snapshot() } returns PsnSnapshot("x", listOf(PsnTitleState("PPSA1", "BRICK WARS", lastPlayed = yesterday)))
        assertEquals(other, controller.defaultPage(zone))

        coEvery { games.snapshot() } returns null
        assertEquals(page, controller.defaultPage(zone))
    }

    // --- 13.1 ------------------------------------------------------------------------------

    private val hour = 3_600_000L

    @Test
    fun `a session started earlier is backdated, its game night follows the tap, and its question is set for 3 hours`() = runTest {
        controller.start(page, saturdayEvening, zone, startedMinutesAgo = 20)
        val running = controller.current()!!
        assertEquals(saturdayEvening - 20 * 60_000L, running.startMillis)
        assertEquals(SessionCheck(running.startMillis, running.startMillis + 3 * hour), SessionCheck.decode(check.value))
        verify { platform.scheduleCheck(3 * hour - 20 * 60_000L) }
        assertEquals("rule-1", rule.value)
        // Never more than an hour back.
        controller.start(page, saturdayEvening, zone, startedMinutesAgo = 600)
        assertEquals(saturdayEvening - 60 * 60_000L, controller.current()!!.startMillis)
    }

    @Test
    fun `the question comes at 3 hours, and no answer ends the session at the question`() = runTest {
        controller.start(page, saturdayEvening, zone)
        controller.check(saturdayEvening + 3 * hour, zone)
        verify { platform.postAsk(any(), saturdayEvening, endsAt = saturdayEvening + 3 * hour, answerBy = saturdayEvening + 4 * hour) }
        verify { platform.scheduleCheck(hour) }
        assertEquals(saturdayEvening + 3 * hour, SessionCheck.decode(check.value)!!.askedAt)

        // Nobody answered: at 01:00 the session is kept as 21:00–00:00 and goes to Saturday's note.
        controller.check(saturdayEvening + 4 * hour, zone)
        assertNull(controller.current())
        assertNull(check.value)
        coVerify { games.addSessions(match { it.single().minutes == 180 && it.single().date == "2026-11-14" }) }
        coVerify { games.applyPastDay(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15)) }
        coVerify(exactly = 0) { dashboard.burnToDisk(any()) }
        verify { platform.cancelAsk() }
        verify { platform.endGameNight("rule-1") }
    }

    @Test
    fun `keep playing takes the question away and asks again an hour later`() = runTest {
        controller.start(page, saturdayEvening, zone)
        controller.check(saturdayEvening + 3 * hour, zone)
        controller.keepPlaying(saturdayEvening + 3 * hour + 5 * 60_000L)
        val kept = SessionCheck.decode(check.value)!!
        assertNull(kept.askedAt)
        assertEquals(saturdayEvening + 4 * hour + 5 * 60_000L, kept.nextAskAt)
        verify { platform.cancelAsk() }
        verify { platform.scheduleCheck(hour) }
        assertEquals(RunningGame(saturdayEvening, page.path), controller.current())
    }

    @Test
    fun `a stopped session leaves no check behind, and a check without a session does nothing`() = runTest {
        controller.start(page, saturdayEvening, zone)
        controller.stop(saturdayEvening + hour, zone)
        assertNull(check.value)
        verify { platform.cancelCheck() }
        controller.check(saturdayEvening + 3 * hour, zone)
        verify(exactly = 0) { platform.postAsk(any(), any(), any(), any()) }
    }

    @Test
    fun `coming back to the app puts the notification back and schedules a session started before 13_1`() = runTest {
        session.value = RunningGame(saturdayEvening, page.path).encode()
        controller.restore(saturdayEvening + hour)
        verify { platform.postRunning(RunningGame(saturdayEvening, page.path), any()) }
        verify { platform.scheduleCheck(2 * hour) }
        assertEquals(SessionCheck.first(RunningGame(saturdayEvening, page.path)), SessionCheck.decode(check.value))
        // An unanswered question isn't posted again: the tab shows it.
        check.value = SessionCheck(saturdayEvening, saturdayEvening + 3 * hour, saturdayEvening + 3 * hour).encode()
        controller.restore(saturdayEvening + 3 * hour + 10 * 60_000L)
        verify(exactly = 0) { platform.postAsk(any(), any(), any(), any()) }
        verify { platform.scheduleCheck(50 * 60_000L) }
    }

    @Test
    fun `a forgotten session lands on its day, today burned, this week edited, older kept in the file`() = runTest {
        val now = ZonedDateTime.of(2026, 11, 16, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        controller.addPastSession(page, LocalDate.of(2026, 11, 16), java.time.LocalTime.of(7, 0), 60, now, zone)
        coVerify { dashboard.burnToDisk(LocalDate.of(2026, 11, 16)) }
        controller.addPastSession(page, LocalDate.of(2026, 11, 14), java.time.LocalTime.of(21, 0), 150, now, zone)
        coVerify { games.applyPastDay(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 16)) }
        val old = controller.addPastSession(page, LocalDate.of(2026, 10, 1), java.time.LocalTime.of(21, 0), 150, now, zone)!!
        assertEquals("2026-10-01", old.date)
        coVerify { games.applyPastDay(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 16)) }
        coVerify(exactly = 1) { dashboard.burnToDisk(any()) }
        assertNull(controller.addPastSession(page, LocalDate.of(2026, 11, 14), java.time.LocalTime.of(21, 0), 0, now, zone))
    }

    @Test
    fun `a fixed session replaces the old one, a deleted one goes, and PSN's stay as measured`() = runTest {
        val now = ZonedDateTime.of(2026, 11, 16, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val old = ManualSessions.record(page.path, LocalDate.of(2026, 11, 15), java.time.LocalTime.of(21, 0), 540, zone)!!
        coEvery { games.replaceSession(any(), any()) } returns true
        val fixed = controller.editSession(old, java.time.LocalTime.of(21, 0), 150, now, zone)!!
        assertEquals(150, fixed.minutes)
        coVerify { games.replaceSession(old, fixed) }
        coVerify { games.applyPastDay(LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 16)) }

        coEvery { games.removeSession(old.id, LocalDate.of(2026, 11, 15)) } returns true
        assertEquals(true, controller.deleteSession(old, now, zone))
        coVerify { games.removeSession(old.id, LocalDate.of(2026, 11, 15)) }

        val psn = old.copy(id = "psn:PPSA1:x", source = GameSessionRecord.SOURCE_PSN, page = null, titleId = "PPSA1")
        assertNull(controller.editSession(psn, java.time.LocalTime.of(20, 0), 60, now, zone))
    }

    @Test
    fun `a read-only device fixes nothing`() = runTest {
        VaultWriteGate.readOnly = true
        val old = ManualSessions.record(page.path, LocalDate.of(2026, 11, 15), java.time.LocalTime.of(21, 0), 60, zone)!!
        assertNull(controller.addPastSession(page, LocalDate.of(2026, 11, 15), java.time.LocalTime.of(21, 0), 60))
        assertNull(controller.editSession(old, java.time.LocalTime.of(21, 0), 30))
        assertEquals(false, controller.deleteSession(old))
        coVerify(exactly = 0) { games.addSessions(any()) }
        coVerify(exactly = 0) { games.replaceSession(any(), any()) }
        coVerify(exactly = 0) { games.removeSession(any(), any()) }
    }
}
