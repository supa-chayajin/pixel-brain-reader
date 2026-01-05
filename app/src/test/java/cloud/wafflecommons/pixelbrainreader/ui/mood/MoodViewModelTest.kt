package cloud.wafflecommons.pixelbrainreader.ui.mood

import cloud.wafflecommons.pixelbrainreader.data.repository.DailyMoodData
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodSummary
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class MoodViewModelTest {

    private val repository: MoodRepository = mockk()
    private lateinit var viewModel: MoodViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // ViewModel init moved to tests
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadMood_UpdatesState() = runTest {
        // Arrange
        val date = LocalDate.of(2026, 1, 4)
        val mockData = DailyMoodData(date.toString(), emptyList(), MoodSummary(4.0, "🙂"))
        every { repository.getDailyMood(any()) } returns flowOf(mockData)
        
        viewModel = MoodViewModel(repository)

        // Act
        viewModel.loadMood(date)
        advanceUntilIdle() // Ensure collection happens

        // Assert
        // assertEquals(date, viewModel.uiState.value.selectedDate) // loadMood doesn't update selectedDate
        assertEquals(mockData, viewModel.uiState.value.moodData)
        verify { repository.getDailyMood(date) }
    }

    @Test
    fun addMoodEntry_CallsRepository() = runTest {
        // Arrange
        every { repository.getDailyMood(any()) } returns flowOf(null) // for Init
        coEvery { repository.addEntry(any(), any()) } just Runs
        
        viewModel = MoodViewModel(repository)
        val date = viewModel.uiState.value.selectedDate

        // Act
        viewModel.addMoodEntry(5, listOf("Coding"), "Feel good")
        advanceUntilIdle()

        // Assert
        coVerify { repository.addEntry(date, any()) }
    }

    @Test
    fun selectDate_UpdatesState() = runTest {
        // Arrange
        val date = LocalDate.of(2026, 1, 5)
        every { repository.getDailyMood(any()) } returns flowOf(null)
        
        viewModel = MoodViewModel(repository)

        // Act
        viewModel.selectDate(date)
        advanceUntilIdle()

        // Assert
        assertEquals(date, viewModel.uiState.value.selectedDate)
        verify { repository.getDailyMood(date) }
    }
    @Test
    fun refreshData_ReloadsMood() = runTest {
        // Arrange
        val date = LocalDate.now()
        every { repository.getDailyMood(any()) } returns flowOf(null)

        viewModel = MoodViewModel(repository)
        viewModel.selectDate(date)
        clearMocks(repository, answers = false, recordedCalls = true) // Clear valid loadMood calls

        // Act
        viewModel.refreshData()
        advanceUntilIdle()

        // Assert
        verify { repository.getDailyMood(date) }
    }
}
