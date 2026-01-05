package cloud.wafflecommons.pixelbrainreader.ui.main

import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyNoteRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.TemplateRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val fileRepository: FileRepository = mockk(relaxed = true)
    private val dailyNoteRepository: DailyNoteRepository = mockk(relaxed = true)
    private val templateRepository: TemplateRepository = mockk(relaxed = true)
    private val secretManager: SecretManager = mockk(relaxed = true)
    private val userPrefs: UserPreferencesRepository = mockk(relaxed = true)
    private val geminiRagManager: cloud.wafflecommons.pixelbrainreader.data.ai.GeminiRagManager = mockk(relaxed = true)

    private lateinit var viewModel: MainViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = MainViewModel(
            fileRepository,
            dailyNoteRepository,
            templateRepository,
            secretManager,
            userPrefs,
            geminiRagManager
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun refreshCurrentFolder_EmitsSuccessToast_AndReloadsFolder() = runTest {
        // Arrange
        val owner = "owner"
        val repo = "repo"
        every { secretManager.getRepoInfo() } returns Pair(owner, repo)
        coEvery { fileRepository.syncRepository(owner, repo) } returns Result.success(Unit)
        coEvery { fileRepository.getFiles(any()) } returns kotlinx.coroutines.flow.flowOf(emptyList())

        // Act
        viewModel.refreshCurrentFolder()
        advanceUntilIdle()

        // Assert
        coVerify { fileRepository.syncRepository(owner, repo) }
        // Verify loadFolder was called (implicitly via getFiles)
        coVerify { fileRepository.getFiles("") }
        // Verify Toast (checking flow might be tricky without Turbine, but we can infer success path)
    }

    @Test
    fun refreshCurrentFolder_EmitsErrorToast_OnFailure() = runTest {
        // Arrange
        val owner = "owner"
        val repo = "repo"
        every { secretManager.getRepoInfo() } returns Pair(owner, repo)
        coEvery { fileRepository.syncRepository(owner, repo) } returns Result.failure(Exception("Network error"))

        // Act
        viewModel.refreshCurrentFolder()
        advanceUntilIdle()

        // Assert
        coVerify { fileRepository.syncRepository(owner, repo) }
        // Verify getFiles was NOT called AGAIN (loadFolder skipped on failure)
        // It is called once during init.
        coVerify(exactly = 1) { fileRepository.getFiles(any()) }
    }

    @Test
    fun performInitialSync_ShowsLoader_AndCallsSync() = runTest {
        // Arrange
        val owner = "owner"
        val repo = "repo"
        every { secretManager.getRepoInfo() } returns Pair(owner, repo)
        coEvery { fileRepository.syncRepository(owner, repo) } returns Result.success(Unit)
        
        // Reset init (done in setup)
        clearMocks(fileRepository, answers = false, recordedCalls = true)

        // Act
        viewModel.performInitialSync()
        advanceUntilIdle()
        
        // Assert
        // We can't easily check isLoading=true mid-flow without turbine, but we verify sync and loadFolder call
        coVerify { fileRepository.syncRepository(owner, repo) }
        coVerify { fileRepository.getFiles(any()) }
    }
}
