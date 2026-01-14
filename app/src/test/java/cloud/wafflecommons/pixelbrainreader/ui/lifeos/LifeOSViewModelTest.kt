package cloud.wafflecommons.pixelbrainreader.ui.lifeos

import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.model.HabitLogEntry
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.TaskRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class LifeOSViewModelTest {

    private lateinit var viewModel: LifeOSViewModel
    private val habitRepository: HabitRepository = mockk()
    private val taskRepository: TaskRepository = mockk()
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = LifeOSViewModel(habitRepository, taskRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadData filters habits by day of week`() = runTest {
        // Arrange
        // Assume today is MONDAY for test simplicity (mocking LocalDate.now() is hard, 
        // so we rely on the fact that we pass the date to loadData, 
        // BUT the viewModel uses date.dayOfWeek.name)
        val testDate = LocalDate.of(2023, 10, 23) // Monday
        
        val habitMon = HabitConfig(id = "1", title = "Monday Habit", frequency = listOf("MON"))
        val habitTue = HabitConfig(id = "2", title = "Tuesday Habit", frequency = listOf("TUE"))
        val habitDaily = HabitConfig(id = "3", title = "Daily Habit", frequency = emptyList())
        
        coEvery { habitRepository.getHabitConfigs() } returns listOf(habitMon, habitTue, habitDaily)
        coEvery { habitRepository.getLogsForYear(any()) } returns emptyMap()
        coEvery { taskRepository.getScopedTasks(any()) } returns emptyList()

        // Act
        viewModel.loadData(testDate)
        testDispatcher.scheduler.advanceUntilIdle()

        // Assert
        val state = viewModel.uiState.value
        assertEquals(2, state.habits.size)
        assertTrue(state.habits.any { it.id == "1" }) // Mon
        assertTrue(state.habits.any { it.id == "3" }) // Daily
        assertTrue(state.habits.none { it.id == "2" }) // Tue should be filtered out
    }

    @Test
    fun `loadData groups habits by RPG tag`() = runTest {
        // Arrange
        val testDate = LocalDate.of(2023, 10, 23) // Monday
        
        val habitVig = HabitConfig(id = "1", title = "Gym", description = "Go to gym (+VIG)")
        val habitMnd = HabitConfig(id = "2", title = "Read", description = "Read book (+MND)")
        val habitGen = HabitConfig(id = "3", title = "Water", description = "Drink water")
        
        coEvery { habitRepository.getHabitConfigs() } returns listOf(habitVig, habitMnd, habitGen)
        coEvery { habitRepository.getLogsForYear(any()) } returns emptyMap()
        coEvery { taskRepository.getScopedTasks(any()) } returns emptyList()

        // Act
        viewModel.loadData(testDate)
        testDispatcher.scheduler.advanceUntilIdle()

        // Assert
        val state = viewModel.uiState.value
        val groups = state.groupedHabits
        
        assertEquals(3, groups.size)
        assertTrue(groups.containsKey("Vigor (Physical)"))
        assertTrue(groups.containsKey("Mind (Mental)"))
        assertTrue(groups.containsKey("General"))
        
        assertEquals(1, groups["Vigor (Physical)"]?.size)
        assertEquals(habitVig.id, groups["Vigor (Physical)"]?.first()?.config?.id)
        
        assertEquals(1, groups["Mind (Mental)"]?.size)
        assertEquals(habitMnd.id, groups["Mind (Mental)"]?.first()?.config?.id)
        
        assertEquals(1, groups["General"]?.size)
        assertEquals(habitGen.id, groups["General"]?.first()?.config?.id)
    }
}
