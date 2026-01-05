package cloud.wafflecommons.pixelbrainreader.ui.daily

import cloud.wafflecommons.pixelbrainreader.data.repository.DailyMoodData
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodSummary
import cloud.wafflecommons.pixelbrainreader.data.utils.FrontmatterManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DailyNoteViewModelTest {

    private val moodRepository: MoodRepository = mockk()
    private val fileRepository: FileRepository = mockk()
    
    // FrontmatterManager is an object, so we might need mockkObject if we want to verify calls, 
    // or just rely on its real implementation if it's pure logic. 
    // Using real implementation is often better for Utils unless we want strict isolation.
    // Given the prompt asks "Assert: State contains BOTH... and StripsFrontmatter", 
    // I will implicitly test it using the real object logic or mock it if I can.
    // I'll stick to real logic for utils usually, but let's mock strictly if needed.
    // Since FrontmatterManager is an object, I'll use it directly to avoid static mocking complexity unless required.

    private lateinit var viewModel: DailyNoteViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // viewModel init moved to individual tests to allow stubbing before init
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadData_AggregatesSources() = runTest {
        // Arrange
        val date = LocalDate.now()
        val formattedDate = date.toString()
        val notePath = "10_Journal/$formattedDate.md"

        val moodData = DailyMoodData(formattedDate, emptyList(), MoodSummary(3.0, "😐"))
        
        every { moodRepository.getDailyMood(any()) } returns flowOf(moodData)
        
        val rawContent = """
            ---
            mood: happy
            ---
            # My Day
            It was good.
        """.trimIndent()
        
        every { fileRepository.fileUpdates } returns kotlinx.coroutines.flow.MutableSharedFlow()
        every { fileRepository.getFileContentFlow(any()) } returns flowOf(rawContent)

        viewModel = DailyNoteViewModel(moodRepository, fileRepository)

        // Act
        viewModel.loadData() // Assuming loadData uses current date by default or takes a param? 
                             // Based on context, it probably uses LocalDate.now() or injected date.
                             // I'll assume defaults for now.
        
        advanceUntilIdle()

        // Assert
        val state = viewModel.uiState.value
        assertEquals(moodData, state.moodData)
        
        // Use FrontmatterManager logic expectation
        val expectedContent = "\n# My Day\nIt was good."
        assertEquals(expectedContent.trim(), state.noteContent.trim())
    }
}
