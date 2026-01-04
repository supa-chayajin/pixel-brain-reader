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
        every { repository.getDailyMood(any()) } returns flowOf(null)
        viewModel = MoodViewModel(repository)
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
        every { repository.getDailyMood(date) } returns flowOf(mockData)

        // Act
        viewModel.loadMood(date)

        // Assert
        assertEquals(date, viewModel.uiState.value.selectedDate)
        assertEquals(mockData, viewModel.uiState.value.moodData)
        verify { repository.getDailyMood(date) }
    }

    @Test
    fun addMoodEntry_CallsRepository() = runTest {
        // Arrange
        coEvery { repository.addEntry(any(), any()) } just Runs
        val date = viewModel.uiState.value.selectedDate

        // Act
        viewModel.addMoodEntry(5, listOf("Coding"), "Feel good")

        // Assert
        coVerify { repository.addEntry(date, any()) }
    }

    @Test
    fun selectDate_UpdatesState() = runTest {
        // Arrange
        val date = LocalDate.of(2026, 1, 5)
        every { repository.getDailyMood(date) } returns flowOf(null)

        // Act
        viewModel.selectDate(date)

        // Assert
        assertEquals(date, viewModel.uiState.value.selectedDate)
        verify { repository.getDailyMood(date) }
    }
}
