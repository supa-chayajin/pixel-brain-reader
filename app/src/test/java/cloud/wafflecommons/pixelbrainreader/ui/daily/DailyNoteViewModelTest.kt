package cloud.wafflecommons.pixelbrainreader.ui.daily

import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyDashboardEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyNoteRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.HealthMetricsRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.ScratchRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.WeatherData
import cloud.wafflecommons.pixelbrainreader.data.repository.WeatherRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncOrchestrator
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncState
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pins the Daily board's refresh contract: it follows the real day, saves every edit to the day it
 * was typed on, never clobbers unsaved typing, and keeps the weather card alive across failures,
 * pull-to-refresh and returns to the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DailyNoteViewModelTest {

    private val day1 = LocalDate.of(2026, 9, 10)
    private val day2 = day1.plusDays(1)

    private val testDispatcher = StandardTestDispatcher()
    private val today = MutableStateFlow(day1)
    private val foregroundEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Stored dashboards per day, as Room exposes them. */
    private val dashboards = mutableMapOf<LocalDate, MutableStateFlow<DailyDashboardEntity?>>()

    private lateinit var dashboardRepository: DailyDashboardRepository
    private lateinit var weatherRepository: WeatherRepository
    private lateinit var syncOrchestrator: SyncOrchestrator

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        dashboardRepository = mockk(relaxed = true)
        every { dashboardRepository.getDashboard(any()) } answers { dashboardFlow(firstArg()) }
        every { dashboardRepository.getLiveTimeline(any()) } returns flowOf(emptyList())
        every { dashboardRepository.getLiveTasks(any()) } returns flowOf(emptyList())
        coEvery { dashboardRepository.hasBuffer(any()) } returns true
        coEvery { dashboardRepository.updateSecondBrain(any(), any(), any()) } coAnswers {
            val flow = dashboardFlow(firstArg())
            val current = flow.value ?: DailyDashboardEntity(date = firstArg())
            flow.value = if (secondArg<String>() == "IDEAS") {
                current.copy(ideasContent = thirdArg())
            } else {
                current.copy(notesContent = thirdArg())
            }
        }

        weatherRepository = mockk()
        coEvery { weatherRepository.getCachedWeather(any()) } returns null
        coEvery { weatherRepository.getWeather(any(), any()) } returns SUNNY
        every { weatherRepository.readingsFor(any()) } returns flowOf(null)

        syncOrchestrator = mockk(relaxed = true)
        every { syncOrchestrator.syncState } returns MutableStateFlow(SyncState.Idle)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a reading saved by another caller replaces the unavailable state`() = runTest(testDispatcher) {
        val saved = MutableStateFlow<WeatherData?>(null)
        every { weatherRepository.readingsFor(day1) } returns saved
        coEvery { weatherRepository.getWeather(any(), any()) } returns null
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        runCurrent()
        assertEquals(WEATHER_UNAVAILABLE, viewModel.uiState.value.weather)

        // e.g. the board's burn fetched a reading while the card's own refresh had failed
        saved.value = SUNNY
        runCurrent()
        assertEquals(SUNNY, viewModel.uiState.value.weather)
    }

    @Test
    fun `an edit typed before midnight is saved to the day it was typed on`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        runCurrent()

        viewModel.onIdeasChanged("late-night idea")
        today.value = day2 // the day rolls over before the typing debounce fires
        runCurrent()

        assertEquals(day2, viewModel.selectedDate.value)
        assertEquals("", viewModel.ideasText)

        advanceTimeBy(2_000)
        runCurrent()

        coVerify(exactly = 1) { dashboardRepository.updateSecondBrain(day1, "IDEAS", "late-night idea") }
        coVerify(exactly = 0) { dashboardRepository.updateSecondBrain(day2, any(), any()) }
    }

    @Test
    fun `stored text is adopted but never replaces unsaved typing`() = runTest(testDispatcher) {
        dashboardFlow(day1).value = DailyDashboardEntity(date = day1, ideasContent = "stored idea")
        val viewModel = createViewModel()
        runCurrent()
        assertEquals("stored idea", viewModel.ideasText)

        viewModel.onIdeasChanged("typing in progress")
        // A sync rewrites the stored text while the edit is still unsaved.
        dashboardFlow(day1).value = DailyDashboardEntity(date = day1, ideasContent = "from a sync")
        runCurrent()
        assertEquals("typing in progress", viewModel.ideasText)

        advanceTimeBy(2_000)
        runCurrent()
        coVerify(exactly = 1) { dashboardRepository.updateSecondBrain(day1, "IDEAS", "typing in progress") }

        // Once saved, changes made elsewhere show up again.
        dashboardFlow(day1).value = DailyDashboardEntity(date = day1, ideasContent = "edited elsewhere")
        runCurrent()
        assertEquals("edited elsewhere", viewModel.ideasText)
    }

    @Test
    fun `a failed weather refresh keeps showing the cached reading`() = runTest(testDispatcher) {
        coEvery { weatherRepository.getCachedWeather(day1) } returns CACHED
        coEvery { weatherRepository.getWeather(any(), any()) } returns null
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        advanceUntilIdle()

        assertEquals(CACHED, viewModel.uiState.value.weather)
        coVerify(exactly = 3) { weatherRepository.getWeather(day1, any()) }
    }

    @Test
    fun `weather is unavailable only until a retry succeeds`() = runTest(testDispatcher) {
        coEvery { weatherRepository.getWeather(any(), any()) } returnsMany listOf(null, SUNNY)
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        runCurrent()
        assertEquals(WEATHER_UNAVAILABLE, viewModel.uiState.value.weather)

        advanceTimeBy(16_000)
        runCurrent()
        assertEquals(SUNNY, viewModel.uiState.value.weather)
    }

    @Test
    fun `returning to the app re-checks the weather`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        foregroundEvents.emit(Unit)
        runCurrent()

        coVerify(exactly = 2) { weatherRepository.getWeather(day1, forceRefresh = false) }
    }

    @Test
    fun `pull-to-refresh forces one sync and one network weather refresh`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        viewModel.triggerSync()
        runCurrent()

        verify(exactly = 1) { syncOrchestrator.requestSync(force = true) }
        coVerify(exactly = 1) { weatherRepository.getWeather(day1, forceRefresh = true) }

        // Leaving the screen and coming back must not force the network again.
        collector.cancel()
        advanceTimeBy(11_000)
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        coVerify(exactly = 1) { weatherRepository.getWeather(day1, forceRefresh = true) }
    }

    @Test
    fun `an event command is added once even when its day is saved again after midnight`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        runCurrent()

        viewModel.onIdeasChanged("/event 10:00 Standup")
        advanceTimeBy(2_000)
        runCurrent()
        coVerify(exactly = 1) { dashboardRepository.addTimelineEntry(day1, "Standup", LocalTime.of(10, 0)) }

        // More typing, then the day rolls over before the typing debounce: the late save of day 1
        // still holds the command.
        viewModel.onIdeasChanged("/event 10:00 Standup\nmore")
        today.value = day2
        advanceTimeBy(2_000)
        runCurrent()

        coVerify(exactly = 1) { dashboardRepository.updateSecondBrain(day1, "IDEAS", "/event 10:00 Standup\nmore") }
        coVerify(exactly = 1) { dashboardRepository.addTimelineEntry(day1, "Standup", LocalTime.of(10, 0)) }
    }

    @Test
    fun `an event already on the timeline is never added again`() = runTest(testDispatcher) {
        // After a restart the note still holds the command, and the timeline already has its event.
        every { dashboardRepository.getLiveTimeline(day1) } returns
            flowOf(listOf(TimelineEntryEntity(date = day1, time = LocalTime.of(10, 0), content = "Standup")))
        val viewModel = createViewModel()
        runCurrent()

        viewModel.onIdeasChanged("/event 10:00 Standup\nnew thought")
        advanceTimeBy(2_000)
        runCurrent()

        coVerify(exactly = 1) { dashboardRepository.updateSecondBrain(day1, "IDEAS", "/event 10:00 Standup\nnew thought") }
        coVerify(exactly = 0) { dashboardRepository.addTimelineEntry(any(), any(), any()) }
    }

    // --- fixtures ---------------------------------------------------------------------

    private fun dashboardFlow(date: LocalDate): MutableStateFlow<DailyDashboardEntity?> =
        dashboards.getOrPut(date) { MutableStateFlow(null) }

    private fun createViewModel(): DailyNoteViewModel {
        val moodRepository = mockk<MoodRepository>(relaxed = true)
        every { moodRepository.getDailyMood(any()) } returns flowOf(null)
        every { moodRepository.getMoodsBetween(any(), any()) } returns flowOf(emptyList())

        val scratchRepository = mockk<ScratchRepository>(relaxed = true)
        every { scratchRepository.getActiveScraps() } returns flowOf(emptyList())

        val dailyNoteRepository = mockk<DailyNoteRepository>(relaxed = true)
        every { dailyNoteRepository.getGratitudesStream(any()) } returns flowOf(emptyList())

        val healthMetricsRepository = mockk<HealthMetricsRepository>(relaxed = true)
        every { healthMetricsRepository.changes } returns MutableStateFlow(0L)
        every { healthMetricsRepository.getMetricsFlow(any()) } returns flowOf(null)
        every { healthMetricsRepository.getMetricsHistoryFlow(any(), any()) } returns flowOf(emptyList())

        val appClock = mockk<AppClock>()
        every { appClock.today } returns today
        every { appClock.foregroundEvents } returns foregroundEvents

        return DailyNoteViewModel(
            moodRepository = moodRepository,
            fileRepository = mockk(relaxed = true),
            weatherRepository = weatherRepository,
            dashboardRepository = dashboardRepository,
            scratchRepository = scratchRepository,
            dailyNoteRepository = dailyNoteRepository,
            taskRepository = mockk(relaxed = true),
            syncOrchestrator = syncOrchestrator,
            healthMetricsRepository = healthMetricsRepository,
            appClock = appClock,
            appScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        )
    }

    private companion object {
        val SUNNY = WeatherData(emoji = "☀️", temperature = "24°C", location = "Lyon", description = "Forecast", code = 0, fetchedAt = 1L)
        val CACHED = WeatherData(emoji = "⛅", temperature = "19°C", location = "Lyon", description = "Forecast", code = 2, fetchedAt = 0L)
    }
}
