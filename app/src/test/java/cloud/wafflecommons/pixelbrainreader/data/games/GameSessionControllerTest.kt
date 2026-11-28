package cloud.wafflecommons.pixelbrainreader.data.games

import android.graphics.Bitmap
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.pictures.GameNotificationPicture
import cloud.wafflecommons.pixelbrainreader.data.games.pictures.GamePictureSource
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesConfig
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Starting and stopping a session by hand, and the game night it may start (D7, D13). */
@OptIn(ExperimentalCoroutinesApi::class)
class GameSessionControllerTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val page = GamePage("30_Areas/Games/Ring/Ring.md", "Ring of Ash", emptyList(), GameTemplate.SOULS_LIKE, GameState.PLAYING, null, null)
    private val tin = GamePage("30_Areas/Games/Tin_Orchard/Tin_Orchard.md", "Tin Orchard", emptyList(), GameTemplate.OTHER, GameState.PLAYING, null, null)

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
    private val pictureSource: GamePictureSource = mockk {
        coEvery { artFor(any<GamePage>()) } answers { GameArtRules.initialsOnly(firstArg<GamePage>().title) }
    }
    private val notificationPicture: GameNotificationPicture = mockk {
        coEvery { largeIcon(any(), any()) } returns null
    }
    private val controller = GameSessionController(preferences, games, platform, modes, dashboard, pictureSource, notificationPicture)

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
        verify { platform.postRunning(any(), "Ring of Ash", false) }
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
    fun `a game night after midnight keeps that evening's shutdown banner`() = runTest {
        // Friday 20 November 2026 at 00:20, in Perso: the game night turns on, and Friday's banner
        // (from 17:30) is still to come, so no day is hidden.
        val afterMidnight = ZonedDateTime.of(2026, 11, 20, 0, 20, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(GameNightOutcome.ON, controller.start(page, afterMidnight, zone))
        coVerify(exactly = 0) { preferences.setGameNightDay(any()) }
        controller.stop(afterMidnight + hour, zone)
        // The same Friday at 19:00, the banner already up: that evening's banner goes.
        val evening = ZonedDateTime.of(2026, 11, 20, 19, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(GameNightOutcome.ON, controller.start(page, evening, zone))
        coVerify(exactly = 1) { preferences.setGameNightDay(LocalDate.of(2026, 11, 20)) }
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
        verify(exactly = 0) { platform.postRunning(any(), any(), any(), any()) }
    }

    @Test
    fun `the game played last is the latest of the sessions and PSN's last-played times`() = runTest {
        coEvery { games.pages() } returns listOf(tin, page)
        coEvery { games.links() } returns GameLinks(links = mapOf("PPSA1" to tin.path))
        every { games.resolver(any(), any()) } returns { record: GameSessionRecord -> listOf(tin, page).firstOrNull { it.path == record.page } }
        val today = LocalDate.now(zone)
        coEvery { games.sessionsBetween(any(), any()) } returns listOf(
            GameSessionRecord("m1", today.minusDays(2).toString(), "21:00", 60, GameSessionRecord.SOURCE_MANUAL, page = page.path)
        )
        // PSN saw Tin Orchard yesterday: later than the manual session two days ago.
        val yesterday = today.minusDays(1).atTime(20, 0).atZone(zone).toInstant().toString()
        coEvery { games.snapshot() } returns PsnSnapshot("x", listOf(PsnTitleState("PPSA1", "TIN ORCHARD", lastPlayed = yesterday)))
        coEvery { games.xboxSnapshot() } returns null
        assertEquals(tin, controller.defaultPage(zone))

        coEvery { games.snapshot() } returns null
        assertEquals(page, controller.defaultPage(zone))
    }

    @Test
    fun `the game played last includes Xbox's last played times`() = runTest {
        coEvery { games.pages() } returns listOf(tin, page)
        coEvery { games.links() } returns GameLinks(links = mapOf("PPSA1" to tin.path)).linkConsole(GameConsole.XBOX, "1915865634", page.path)
        every { games.resolver(any(), any()) } returns { _: GameSessionRecord -> null }
        coEvery { games.sessionsBetween(any(), any()) } returns emptyList()
        val today = LocalDate.now(zone)
        fun at(daysAgo: Long, hour: Int) = today.minusDays(daysAgo).atTime(hour, 0).atZone(zone).toInstant().toString()
        // PSN saw Tin Orchard two days ago, Xbox Ring of Ash yesterday.
        coEvery { games.snapshot() } returns PsnSnapshot("x", listOf(PsnTitleState("PPSA1", "TIN ORCHARD", lastPlayed = at(2, 20))))
        coEvery { games.xboxSnapshot() } returns XboxSnapshot(
            "y",
            titles = listOf(
                XboxTitleState("1915865634", "Ring of Ash", lastPlayed = at(1, 21)),
                // Played later, but on no page: left out.
                XboxTitleState("1234567890", "Unlinked", lastPlayed = at(0, 1))
            )
        )
        assertEquals(page, controller.defaultPage(zone))
        // An Xbox time that doesn't read is left out.
        coEvery { games.xboxSnapshot() } returns XboxSnapshot("y", titles = listOf(XboxTitleState("1915865634", "Ring of Ash", lastPlayed = "yesterday")))
        assertEquals(tin, controller.defaultPage(zone))
        // A PSN link with the same id isn't an Xbox one.
        coEvery { games.links() } returns GameLinks(links = mapOf("PPSA1" to tin.path, "1915865634" to page.path))
        coEvery { games.xboxSnapshot() } returns XboxSnapshot("y", titles = listOf(XboxTitleState("1915865634", "Ring of Ash", lastPlayed = at(1, 21))))
        assertEquals(tin, controller.defaultPage(zone))
    }

    @Test
    fun `no game page gives no game played last`() = runTest {
        coEvery { games.pages() } returns emptyList()
        assertNull(controller.defaultPage(zone))
    }

    // --- 13.1 ------------------------------------------------------------------------------

    private val hour = 3_600_000L

    @Test
    fun `a session started earlier is backdated, its game night follows the tap, and its question is set for 3 hours`() = runTest {
        controller.start(page, saturdayEvening, zone, startedMinutesAgo = 20)
        val running = controller.current()!!
        assertEquals(saturdayEvening - 20 * 60_000L, running.startMillis)
        assertEquals(SessionCheck(running.startMillis, running.startMillis + 3 * hour), SessionCheck.decode(check.value))
        verify { platform.scheduleCheck(3 * hour - 20 * 60_000L, false) }
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
        // The check's next look is appended to its own job, never a replacement of it.
        verify { platform.scheduleCheck(hour, true) }
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
        verify { platform.scheduleCheck(hour, false) }
        assertEquals(RunningGame(saturdayEvening, page.path), controller.current())
    }

    @Test
    fun `a stopped session leaves no check behind, and a check without a session does nothing`() = runTest {
        controller.start(page, saturdayEvening, zone)
        controller.stop(saturdayEvening + hour, zone)
        assertNull(check.value)
        verify { platform.cancelCheck() }
        controller.check(saturdayEvening + 3 * hour, zone)
        verify(exactly = 0) { platform.postAsk(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `coming back to the app puts the notification back and schedules a session started before 13_1`() = runTest {
        session.value = RunningGame(saturdayEvening, page.path).encode()
        controller.restore(saturdayEvening + hour)
        verify { platform.postRunning(RunningGame(saturdayEvening, page.path), any(), any()) }
        verify { platform.scheduleCheck(2 * hour, false) }
        assertEquals(SessionCheck.first(RunningGame(saturdayEvening, page.path)), SessionCheck.decode(check.value))
        // An unanswered question isn't posted again: the tab shows it.
        check.value = SessionCheck(saturdayEvening, saturdayEvening + 3 * hour, saturdayEvening + 3 * hour).encode()
        controller.restore(saturdayEvening + 3 * hour + 10 * 60_000L)
        verify(exactly = 0) { platform.postAsk(any(), any(), any(), any(), any()) }
        verify { platform.scheduleCheck(50 * 60_000L, false) }
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
        controller.refreshDays(setOf(LocalDate.of(2026, 11, 15)))
        coVerify(exactly = 0) { games.addSessions(any()) }
        coVerify(exactly = 0) { games.replaceSession(any(), any()) }
        coVerify(exactly = 0) { games.removeSession(any(), any()) }
        coVerify(exactly = 0) { games.applyPastDay(any(), any()) }
    }

    @Test
    fun `a game with a map offers it on the running session's notification (13_2)`() = runTest {
        val withMap = page.copy(maps = GameMaps.parse(listOf("Interactive map | https://ringofash.wiki.example.com/Interactive+Map")))
        controller.start(withMap, saturdayEvening, zone)
        verify { platform.postRunning(any(), "Ring of Ash", true) }
        coEvery { games.page(withMap.path) } returns withMap
        controller.restore(saturdayEvening + hour)
        verify(exactly = 2) { platform.postRunning(any(), "Ring of Ash", true) }
    }

    // --- 13.4: the stop runs to its end (the audit's critical finding) ------------------------

    @Test
    fun `stop runs to its end when its own job is cancelled`() = runTest {
        // 21:00, asked at 00:00, nobody answered by 01:00: the check ends the session at 00:00.
        controller.start(page, saturdayEvening, zone)
        controller.check(saturdayEvening + 3 * hour, zone)
        val events = mutableListOf<String>()
        lateinit var job: Job
        // What WorkManager does once the job's unique work is cancelled or replaced: it cancels the
        // job's coroutine wherever it is. Here it lands in the save, as it did in 13.3.
        every { platform.cancelCheck() } answers { job.cancel() }
        coEvery { games.addSessions(any()) } coAnswers {
            job.cancel()
            yield() // a real save suspends: a stop that could be cancelled would end here
            events += "saved"
            firstArg<List<GameSessionRecord>>().map { LocalDate.parse(it.date) }.toSet()
        }
        // DataStore's writes and the past day's edit suspend too.
        coEvery { preferences.setGameSession(any()) } coAnswers {
            yield()
            events += if (firstArg<String?>() == null) "session cleared" else "session set"
            session.value = firstArg()
        }
        coEvery { preferences.setGameSessionCheck(any()) } coAnswers {
            yield()
            check.value = firstArg()
        }
        coEvery { games.applyPastDay(any(), any()) } coAnswers {
            yield()
            events += "past day"
            true
        }
        every { platform.endGameNight(any()) } answers { events += "game night off" }

        job = launch { controller.check(saturdayEvening + 4 * hour, zone) }
        job.join()

        assertTrue(job.isCancelled)
        coVerify { games.addSessions(match { it.single().minutes == 180 && it.single().date == "2026-11-14" }) }
        // Saved first, cleared after, the evening's note edited, the game night ended: all of it.
        assertEquals(listOf("saved", "session cleared", "past day", "game night off"), events)
        assertNull(controller.current())
        assertNull(check.value)
        coVerify { games.applyPastDay(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15)) }
        verify { platform.endGameNight("rule-1") }
        verify { platform.cancelRunning() }
        verify { platform.cancelAsk() }
    }

    @Test
    fun `the check's own end never cancels its job`() = runTest {
        controller.start(page, saturdayEvening, zone)
        clearMocks(platform, answers = false)
        // Every look the check schedules is appended to its own job.
        controller.check(saturdayEvening + hour, zone)
        verify { platform.scheduleCheck(2 * hour, true) }
        controller.check(saturdayEvening + 3 * hour, zone)
        verify { platform.scheduleCheck(hour, true) }
        verify(exactly = 0) { platform.scheduleCheck(any(), false) }
        clearMocks(platform, answers = false)

        controller.check(saturdayEvening + 4 * hour, zone)
        assertNull(controller.current())
        verify(exactly = 0) { platform.cancelCheck() }
        verify(exactly = 0) { platform.scheduleCheck(any(), any()) }
        verify { platform.cancelRunning() }
        verify { platform.cancelAsk() }
    }

    @Test
    fun `a stop that can't be saved keeps the session and its notification, and ends the game night`() = runTest {
        controller.start(page, saturdayEvening, zone)
        coEvery { games.addSessions(any()) } returns null
        clearMocks(platform, answers = false)
        assertNull(controller.stop(saturdayEvening + hour, zone))
        assertEquals(RunningGame(saturdayEvening, page.path), controller.current())
        assertNotNull(SessionCheck.decode(check.value))
        verify(exactly = 0) { platform.cancelRunning() }
        verify(exactly = 0) { platform.cancelAsk() }
        verify(exactly = 0) { platform.cancelCheck() }
        verify { platform.endGameNight("rule-1") }
        coVerify(exactly = 0) { dashboard.burnToDisk(any()) }

        // A later Stop saves it under the same id, so the two tries never make two sessions.
        coEvery { games.addSessions(any()) } answers { firstArg<List<GameSessionRecord>>().map { LocalDate.parse(it.date) }.toSet() }
        val record = controller.stop(saturdayEvening + 2 * hour, zone)!!
        assertEquals(120, record.minutes)
        assertEquals(ManualSessions.idFor(page.path, saturdayEvening), record.id)
        coVerify(exactly = 2) { games.addSessions(match { it.single().id == record.id }) }
        assertNull(controller.current())
        verify { platform.cancelRunning() }
    }

    @Test
    fun `an end the check can't save is tried again a quarter of an hour later`() = runTest {
        controller.start(page, saturdayEvening, zone)
        controller.check(saturdayEvening + 3 * hour, zone)
        coEvery { games.addSessions(any()) } returns null
        clearMocks(platform, answers = false)
        controller.check(saturdayEvening + 4 * hour, zone)
        assertEquals(RunningGame(saturdayEvening, page.path), controller.current())
        verify { platform.scheduleCheck(GameSessionController.RETRY_MS, true) }
        verify(exactly = 0) { platform.cancelCheck() }
        verify(exactly = 0) { platform.cancelRunning() }
        verify { platform.endGameNight("rule-1") }

        // The next look saves it, still ending at the question.
        coEvery { games.addSessions(any()) } answers { firstArg<List<GameSessionRecord>>().map { LocalDate.parse(it.date) }.toSet() }
        controller.check(saturdayEvening + 4 * hour + GameSessionController.RETRY_MS, zone)
        assertNull(controller.current())
        coVerify(exactly = 2) { games.addSessions(match { it.single().minutes == 180 && it.single().id == ManualSessions.idFor(page.path, saturdayEvening) }) }
    }

    @Test
    fun `a stop whose note can't follow still keeps the session`() = runTest {
        controller.start(page, saturdayEvening, zone)
        coEvery { dashboard.burnToDisk(any()) } throws IOException("disk full")
        val record = controller.stop(saturdayEvening + hour, zone)
        assertEquals(60, record?.minutes)
        assertNull(controller.current())
        verify { platform.endGameNight("rule-1") }
    }

    // --- 13.4: when a session ends (E4, the backdated start) ---------------------------------

    @Test
    fun `a stop after the answer's deadline ends at the deadline`() = runTest {
        controller.start(page, saturdayEvening, zone)
        controller.check(saturdayEvening + 3 * hour, zone) // asked at 00:00, answer by 01:00
        // Doze held the end back; at 01:40 the owner taps Stop on the question.
        val record = controller.stop(saturdayEvening + 4 * hour + 40 * 60_000L, zone)!!
        assertEquals(180, record.minutes)
        assertEquals("21:00", record.start)
        assertNull(controller.current())
    }

    @Test
    fun `a stop inside the hour records until the tap`() = runTest {
        controller.start(page, saturdayEvening, zone)
        controller.check(saturdayEvening + 3 * hour, zone)
        val record = controller.stop(saturdayEvening + 3 * hour + 30 * 60_000L, zone)!!
        assertEquals(210, record.minutes)
    }

    @Test
    fun `starting a game started earlier ends the running one at the new start`() = runTest {
        controller.start(page, saturdayEvening, zone) // Ring of Ash since 21:00
        val tap = saturdayEvening + 90 * 60_000L // 22:30: Tin Orchard "started 30 min ago"
        controller.start(tin, tap, zone, startedMinutesAgo = 30)
        coVerify(exactly = 1) { games.addSessions(any()) }
        coVerify { games.addSessions(match { it.single().page == page.path && it.single().start == "21:00" && it.single().minutes == 60 }) }
        val running = RunningGame(tap - 30 * 60_000L, tin.path)
        assertEquals(running, controller.current())
        assertEquals(SessionCheck.first(running), SessionCheck.decode(check.value))
        verify { platform.postRunning(running, "Tin Orchard", false) }
    }

    @Test
    fun `a running session that started after the new start leaves no record`() = runTest {
        controller.start(page, saturdayEvening, zone) // Ring of Ash since 21:00
        val tap = saturdayEvening + 10 * 60_000L // 21:10: Tin Orchard "started 20 min ago", since 20:50
        controller.start(tin, tap, zone, startedMinutesAgo = 20)
        coVerify(exactly = 0) { games.addSessions(any()) }
        assertEquals(RunningGame(tap - 20 * 60_000L, tin.path), controller.current())
        verify { platform.cancelCheck() }
    }

    @Test
    fun `a backdated start after an unanswered deadline ends the running one at the deadline`() = runTest {
        controller.start(page, saturdayEvening, zone)
        controller.check(saturdayEvening + 3 * hour, zone) // asked at 00:00, answer by 01:00
        val tap = saturdayEvening + 4 * hour + 30 * 60_000L // 01:30, the end held back
        controller.start(tin, tap, zone, startedMinutesAgo = 10)
        coVerify { games.addSessions(match { it.single().page == page.path && it.single().minutes == 180 }) }
        assertEquals(RunningGame(tap - 10 * 60_000L, tin.path), controller.current())
    }

    @Test
    fun `a start whose running session can't be saved starts nothing`() = runTest {
        controller.start(page, saturdayEvening, zone)
        coEvery { games.addSessions(any()) } returns null
        assertNull(controller.start(tin, saturdayEvening + hour, zone))
        assertEquals(RunningGame(saturdayEvening, page.path), controller.current())
        verify(exactly = 0) { platform.postRunning(any(), "Tin Orchard", any(), any()) }
    }

    // --- 13.4: nothing left behind, nothing interleaved ----------------------------------------

    @Test
    fun `a check with no session removes the running notification`() = runTest {
        controller.check(saturdayEvening, zone)
        verify { platform.cancelRunning() }
        verify { platform.cancelAsk() }
        coVerify { preferences.setGameSessionCheck(null) }
        verify(exactly = 0) { platform.postRunning(any(), any(), any(), any()) }
        verify(exactly = 0) { platform.scheduleCheck(any(), any()) }
    }

    @Test
    fun `restore with no session clears the notification and the game night`() = runTest {
        rule.value = "rule-1"
        controller.restore(saturdayEvening)
        verify { platform.cancelRunning() }
        verify { platform.cancelAsk() }
        coVerify { preferences.setGameSessionCheck(null) }
        verify { platform.endGameNight("rule-1") }
        verify(exactly = 0) { platform.postRunning(any(), any(), any(), any()) }
        verify(exactly = 0) { platform.scheduleCheck(any(), any()) }
    }

    @Test
    fun `keep playing with no session only takes the question away`() = runTest {
        controller.keepPlaying(saturdayEvening)
        verify { platform.cancelAsk() }
        verify(exactly = 0) { platform.scheduleCheck(any(), any()) }
        coVerify(exactly = 0) { preferences.setGameSessionCheck(any()) }
    }

    @Test
    fun `a stop racing a check leaves no running notification`() = runTest {
        controller.start(page, saturdayEvening, zone)
        val events = mutableListOf<String>()
        every { platform.postRunning(any(), any(), any(), any()) } answers { events += "posted" }
        every { platform.cancelRunning() } answers { events += "cancelled" }
        val read = CompletableDeferred<GamePage?>()
        coEvery { games.page(page.path) } coAnswers { read.await() }

        val checking = launch { controller.check(saturdayEvening + hour, zone) } // a Wait: posts the notification again
        runCurrent() // the check reads the session's page
        val stopping = launch { controller.stop(saturdayEvening + hour, zone) }
        runCurrent() // the Stop comes in meanwhile
        read.complete(page)
        checking.join()
        stopping.join()

        assertEquals(listOf("posted", "cancelled"), events)
        assertNull(controller.current())
        assertNull(check.value)
    }

    @Test
    fun `a return to the app while the check ends the session posts nothing back`() = runTest {
        controller.start(page, saturdayEvening, zone)
        controller.check(saturdayEvening + 3 * hour, zone)
        val saving = CompletableDeferred<Set<LocalDate>?>()
        coEvery { games.addSessions(any()) } coAnswers { saving.await() }
        clearMocks(platform, answers = false)

        val ending = launch { controller.check(saturdayEvening + 4 * hour, zone) }
        runCurrent() // the end is saving the session
        val restoring = launch { controller.restore(saturdayEvening + 4 * hour) }
        runCurrent() // the app comes back meanwhile
        saving.complete(setOf(LocalDate.of(2026, 11, 14)))
        ending.join()
        restoring.join()

        assertNull(controller.current())
        verify(exactly = 0) { platform.postRunning(any(), any(), any(), any()) }
        // Nothing replaced (and so cancelled) the ending check's job.
        verify(exactly = 0) { platform.scheduleCheck(any(), any()) }
        verify(exactly = 0) { platform.cancelCheck() }
    }

    @Test
    fun `a check cancelled while it waits for the lock schedules no look`() = runTest {
        // 13.4 review: restore's REPLACE cancels the check's job, whose body still runs once the lock is free.
        controller.start(page, saturdayEvening, zone)
        val read = CompletableDeferred<GamePage?>()
        coEvery { games.page(page.path) } coAnswers { read.await() }
        clearMocks(platform, answers = false)

        val restoring = launch { controller.restore(saturdayEvening + hour) }
        runCurrent() // restore holds the lock, reading the page
        val checking = launch { controller.check(saturdayEvening + hour, zone) }
        runCurrent() // the check waits for the lock
        checking.cancel()
        read.complete(page)
        restoring.join()
        checking.join()

        verify(exactly = 1) { platform.scheduleCheck(any(), false) }
        verify(exactly = 0) { platform.scheduleCheck(any(), true) }
    }

    @Test
    fun `refreshing days after a relink passes the old page's key`() = runTest {
        val now = ZonedDateTime.of(2026, 11, 16, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        controller.refreshDays(setOf(LocalDate.of(2026, 11, 14)), now, zone, replacingKeys = setOf("Old_Name"))
        coVerify { games.applyPastDay(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 16), setOf("Old_Name")) }
    }

    @Test
    fun `refreshing days edits this week's notes and burns today's`() = runTest {
        val now = ZonedDateTime.of(2026, 11, 16, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        controller.refreshDays(setOf(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 16)), now, zone)
        coVerify { games.applyPastDay(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 16)) }
        coVerify { dashboard.ensureDay(LocalDate.of(2026, 11, 16)) }
        coVerify { dashboard.burnToDisk(LocalDate.of(2026, 11, 16)) }
    }

    // --- 13.4: the session notification's picture (P1) ----------------------------------------

    @Test
    fun `the large icon is posted after the first notification`() = runTest {
        val icon = mockk<Bitmap>()
        coEvery { notificationPicture.largeIcon(any(), any()) } returns icon
        controller.start(page, saturdayEvening, zone)
        verifyOrder {
            platform.postRunning(any(), "Ring of Ash", false, null)
            platform.postRunning(any(), "Ring of Ash", false, icon)
        }
        coVerify { notificationPicture.largeIcon(GameArtRules.initialsOnly("Ring of Ash"), any()) }

        // A return to the app and a look post it at once, and the question carries it too.
        clearMocks(platform, answers = false)
        coEvery { games.page(page.path) } returns page
        controller.restore(saturdayEvening + hour)
        controller.check(saturdayEvening + 3 * hour, zone)
        verify(exactly = 0) { platform.postRunning(any(), any(), any(), null) }
        verify(exactly = 2) { platform.postRunning(any(), "Ring of Ash", false, icon) }
        verify { platform.postAsk("Ring of Ash", saturdayEvening, any(), any(), icon) }

        // A page that's gone: the initials of its file name.
        coEvery { games.page(page.path) } returns null
        controller.restore(saturdayEvening + 3 * hour + 5 * 60_000L)
        coVerify { notificationPicture.largeIcon(GameArtRules.initialsOnly("Ring"), any()) }
    }

    @Test
    fun `a picture that fails never holds the session back`() = runTest {
        coEvery { notificationPicture.largeIcon(any(), any()) } throws IllegalStateException("no picture")
        assertEquals(GameNightOutcome.ON, controller.start(page, saturdayEvening, zone))
        assertEquals(RunningGame(saturdayEvening, page.path), controller.current())
        verify(exactly = 1) { platform.postRunning(any(), "Ring of Ash", false, null) }
    }
}
