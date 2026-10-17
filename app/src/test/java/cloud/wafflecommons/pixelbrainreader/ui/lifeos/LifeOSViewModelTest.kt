package cloud.wafflecommons.pixelbrainreader.ui.lifeos

import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository
import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationState
import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.TaskRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncOrchestrator
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncState
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.data.utils.SoundEffectManager
import cloud.wafflecommons.pixelbrainreader.domain.gamification.GrantXpUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class LifeOSViewModelTest {

    private val habitRepository: HabitRepository = mockk(relaxed = true)
    private val taskRepository: TaskRepository = mockk(relaxed = true)
    private val gamificationRepository: GamificationRepository = mockk(relaxed = true)
    private val grantXpUseCase: GrantXpUseCase = mockk(relaxed = true)
    private val syncOrchestrator: SyncOrchestrator = mockk(relaxed = true)
    private val soundEffectManager: SoundEffectManager = mockk(relaxed = true)
    private val appClock: AppClock = mockk(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testDate = LocalDate.of(2023, 10, 23) // Monday
    private var activeViewModel: LifeOSViewModel? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { appClock.today } returns MutableStateFlow(testDate)
        every { gamificationRepository.gamificationState } returns MutableStateFlow(GamificationState())
        every { syncOrchestrator.syncState } returns MutableStateFlow(SyncState.Idle)
        every { taskRepository.getTasksFlow(any()) } returns flowOf(emptyList())
        every { habitRepository.getLogsBetweenFlow(any(), any()) } returns flowOf(emptyMap())
    }

    @After
    fun tearDown() {
        activeViewModel?.viewModelScope?.cancel()
        activeViewModel = null
        Dispatchers.resetMain()
    }

    @Test
    fun `uiState filters scheduled habits by day of week`() = runTest {
        val habitMon = HabitConfig(id = "1", title = "Monday Habit", frequency = listOf("MON"))
        val habitTue = HabitConfig(id = "2", title = "Tuesday Habit", frequency = listOf("TUE"))
        val habitDaily = HabitConfig(id = "3", title = "Daily Habit", frequency = emptyList())

        every { habitRepository.getHabitConfigsFlow() } returns flowOf(listOf(habitMon, habitTue, habitDaily))

        val viewModel = LifeOSViewModel(
            habitRepository = habitRepository,
            taskRepository = taskRepository,
            gamificationRepository = gamificationRepository,
            grantXpUseCase = grantXpUseCase,
            syncOrchestrator = syncOrchestrator,
            soundEffectManager = soundEffectManager,
            appClock = appClock
        ).also { activeViewModel = it }

        val state = withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                viewModel.uiState.first { !it.isLoading }
            }
        }
        val scheduledIds = state.groupedHabits.values.flatten().map { it.config.id }
        assertEquals(2, scheduledIds.size)
        assertTrue(scheduledIds.contains("1"))
        assertTrue(scheduledIds.contains("3"))
        assertTrue(!scheduledIds.contains("2"))
        viewModel.viewModelScope.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `uiState groups habits by RPG attribute tag`() = runTest {
        val habitVig = HabitConfig(id = "1", title = "Gym", description = "Go to gym (+VIG)", frequency = listOf("MON"))
        val habitMnd = HabitConfig(id = "2", title = "Read", description = "Read book (+MND)", frequency = listOf("MON"))
        val habitGen = HabitConfig(id = "3", title = "Water", description = "Drink water", frequency = listOf("MON"))

        every { habitRepository.getHabitConfigsFlow() } returns flowOf(listOf(habitVig, habitMnd, habitGen))

        val viewModel = LifeOSViewModel(
            habitRepository = habitRepository,
            taskRepository = taskRepository,
            gamificationRepository = gamificationRepository,
            grantXpUseCase = grantXpUseCase,
            syncOrchestrator = syncOrchestrator,
            soundEffectManager = soundEffectManager,
            appClock = appClock
        ).also { activeViewModel = it }

        val state = withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                viewModel.uiState.first { !it.isLoading }
            }
        }
        val groups = state.groupedHabits
        assertEquals(3, groups.size)
        assertTrue(groups.containsKey("VIG Training"))
        assertTrue(groups.containsKey("MND Training"))
        assertTrue(groups.containsKey("General"))
        viewModel.viewModelScope.cancel()
        advanceUntilIdle()
    }
}
