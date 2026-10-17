package cloud.wafflecommons.pixelbrainreader.ui.mood

import cloud.wafflecommons.pixelbrainreader.data.repository.DailyMoodData
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodSummary
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodTagRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.NoteRepository
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class MoodViewModelTest {

    private val repository: MoodRepository = mockk(relaxed = true)
    private val moodTagRepository: MoodTagRepository = mockk(relaxed = true)
    private val noteRepository: NoteRepository = mockk(relaxed = true)
    private val appClock: AppClock = mockk(relaxed = true)
    private val todayFlow = MutableStateFlow(LocalDate.of(2026, 1, 4))
    private lateinit var viewModel: MoodViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { appClock.today } returns todayFlow
        every { moodTagRepository.tags } returns MutableStateFlow(listOf("Work", "Sport"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadMood_UpdatesState() = runTest {
        val date = LocalDate.of(2026, 1, 4)
        val mockData = DailyMoodData(date.toString(), emptyList(), MoodSummary(4.0, "🙂"))
        every { repository.getDailyMood(any()) } returns flowOf(mockData)

        viewModel = MoodViewModel(repository, moodTagRepository, noteRepository, appClock)
        viewModel.loadMood(date)
        advanceUntilIdle()

        assertEquals(mockData, viewModel.uiState.value.moodData)
        verify { repository.getDailyMood(date) }
    }

    @Test
    fun addMoodEntry_CallsRepositoryAndUpdatesNoteFrontmatter() = runTest {
        every { repository.getDailyMood(any()) } returns flowOf(null)
        coEvery { repository.addEntry(any(), any()) } just Runs
        coEvery { noteRepository.updateNoteMetadata(any(), any()) } just Runs

        viewModel = MoodViewModel(repository, moodTagRepository, noteRepository, appClock)
        val timestamp = java.time.LocalDateTime.of(2026, 1, 4, 14, 30)

        viewModel.addMoodEntry(5, listOf("Coding"), "Feel good", timestamp)
        advanceUntilIdle()

        coVerify { repository.addEntry(LocalDate.of(2026, 1, 4), any()) }
        coVerify {
            noteRepository.updateNoteMetadata(
                path = "10_Journal/diary/2026-01-04.md",
                updates = mapOf("mood_score" to 5, "mood_emoji" to "🤩")
            )
        }
    }

    @Test
    fun selectDate_UpdatesState() = runTest {
        val date = LocalDate.of(2026, 1, 5)
        every { repository.getDailyMood(any()) } returns flowOf(null)

        viewModel = MoodViewModel(repository, moodTagRepository, noteRepository, appClock)
        viewModel.selectDate(date)
        advanceUntilIdle()

        assertEquals(date, viewModel.uiState.value.selectedDate)
        verify { repository.getDailyMood(date) }
    }
}
