package cloud.wafflecommons.pixelbrainreader.ui.settings

import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.repository.AppThemeConfig
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = SettingsDispatcherRule()

    private lateinit var viewModel: SettingsViewModel

    // Mocks
    private val context: android.content.Context = mockk(relaxed = true)
    private val userPrefs: UserPreferencesRepository = mockk(relaxed = true)
    private val secretManager: SecretManager = mockk(relaxed = true)
    private val vectorSearchEngine: cloud.wafflecommons.pixelbrainreader.data.ai.VectorSearchEngine = mockk(relaxed = true)
    private val geminiRagManager: cloud.wafflecommons.pixelbrainreader.data.ai.GeminiRagManager = mockk(relaxed = true)

    @Before
    fun setup() {
        // Default Stubs
        every { secretManager.getRepoInfo() } returns Pair("testUser", "testRepo")
        every { userPrefs.themeConfig } returns flowOf(AppThemeConfig.FOLLOW_SYSTEM)
        every { userPrefs.selectedAiModel } returns flowOf(UserPreferencesRepository.AiModel.GEMINI_FLASH)
        every { userPrefs.embeddingModel } returns flowOf("universal_sentence_encoder.tflite")
        every { userPrefs.llmModelName } returns flowOf("gemini-2.5-flash-lite")
        
        viewModel = SettingsViewModel(context, userPrefs, secretManager, vectorSearchEngine, geminiRagManager)
    }

    @Test
    fun `init loads repo info and preferences`() = runTest {
        advanceUntilIdle()
        
        val state = viewModel.uiState.value
        assertEquals("testUser", state.repoOwner)
        assertEquals("testRepo", state.repoName)
        assertEquals(AppThemeConfig.FOLLOW_SYSTEM, state.themeConfig)
        assertEquals(UserPreferencesRepository.AiModel.GEMINI_FLASH, state.currentAiModel)
    }

    @Test
    fun `updateTheme persists selection`() = runTest {
        // Mock persistence
        coEvery { userPrefs.setThemeConfig(any()) } returns Unit
        
        // Mock Flow Update (simulate reactive update)
        // Note: Real ViewModel relies on Flow observation. 
        // We can't easily change the Flow emission of the mock on the fly in MockK unless we use MutableStateFlow in the mock setup.
        // For this test, verifying the CALL to save is sufficient.
        
        viewModel.updateTheme(AppThemeConfig.DARK)
        advanceUntilIdle()
        
        coVerify { userPrefs.setThemeConfig(AppThemeConfig.DARK) }
    }

    @Test
    fun `updateAiModel persists selection`() = runTest {
        coEvery { userPrefs.setAiModel(any()) } returns Unit
        
        viewModel.updateAiModel(UserPreferencesRepository.AiModel.GEMINI_PRO)
        advanceUntilIdle()
        
        coVerify { userPrefs.setAiModel(UserPreferencesRepository.AiModel.GEMINI_PRO) }
    }

    @Test
    fun `logout clears secret manager`() = runTest {
        // Mock clear behavior (usually void)
        every { secretManager.clear() } returns Unit
        // Mock getRepoInfo to return nulls after clear (simulating effect)
        // Use side-effect to change subsequent call return
        every { secretManager.clear() } answers {
            every { secretManager.getRepoInfo() } returns Pair(null, null)
        }
        
        viewModel.logout()
        advanceUntilIdle()
        
        verify { secretManager.clear() }
        
        // Verify state is updated (repo info cleared)
        val state = viewModel.uiState.value
        assertNull(state.repoOwner)
        assertNull(state.repoName)
    }
}
