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
    private val preferences: UserPreferencesRepository = mockk(relaxed = true) {
        every { gameSession } returns session
        every { gameNightRule } returns rule
        coEvery { setGameSession(any()) } answers { session.value = firstArg() }
        coEvery { setGameNightRule(any()) } answers { rule.value = firstArg() }
    }
    private val games: GamesRepository = mockk(relaxed = true) {
        coEvery { addSessions(any()) } answers { firstArg<List<GameSessionRecord>>().map { LocalDate.parse(it.date) }.toSet() }
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
}
