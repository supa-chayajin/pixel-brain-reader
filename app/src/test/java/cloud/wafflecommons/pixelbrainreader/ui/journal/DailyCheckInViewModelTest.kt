package cloud.wafflecommons.pixelbrainreader.ui.journal

import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyNoteRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.ui.main.MainDispatcherRule
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class DailyCheckInViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var viewModel: DailyCheckInViewModel
    private val fileRepository: FileRepository = mockk(relaxed = true)
    private val dailyNoteRepository: DailyNoteRepository = mockk(relaxed = true)
    private val secretManager: SecretManager = mockk(relaxed = true)

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.d(any(), any()) } returns 0
        
        viewModel = DailyCheckInViewModel(fileRepository, dailyNoteRepository, secretManager)
    }

    @Test
    fun `loadTodayStatus_FileExists_UpdatesState`() = runTest {
        val date = LocalDate.now().toString()
        val path = "10_Journal/$date.md"
        val content = """
            ---
            pixel_brain_log: true
            daily_emoji: "🤩"
            last_update: "08:00"
            ---
        """.trimIndent()
        
        coEvery { fileRepository.getFileContentFlow(path) } returns flowOf(content)
        
        viewModel.loadTodayStatus()
        advanceUntilIdle()
        
        assertEquals("🤩", viewModel.uiState.value.summary.dailyEmoji)
        assertEquals("08:00", viewModel.uiState.value.summary.lastUpdate)
    }

    @Test
    fun `submitCheckIn_SavesFile_AndShowsSuccess`() = runTest {
        val date = LocalDate.now().toString()
        val targetPath = "10_Journal/$date.md"
        
        coEvery { fileRepository.getFileContentFlow(targetPath) } returns flowOf("")
        coEvery { secretManager.getRepoInfo() } returns Pair("user", "repo")
        coEvery { fileRepository.pushDirtyFiles(any(), any()) } returns Result.success(Unit)
        
        viewModel.submitCheckIn(5, "🤩", listOf("Testing"), "Nice day")
        advanceUntilIdle()
        
        coVerify { fileRepository.createLocalFolder("10_Journal") }
        coVerify { dailyNoteRepository.getOrCreateTodayNote() }
        coVerify { fileRepository.saveFileLocally(targetPath, any()) }
        coVerify { fileRepository.pushDirtyFiles("user", "repo") }
        
        assertTrue(viewModel.uiState.value.success)
        assertEquals("Check-in saved!", viewModel.uiState.value.message)
    }
}
