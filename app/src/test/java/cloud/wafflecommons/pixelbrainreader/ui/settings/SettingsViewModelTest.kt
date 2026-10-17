package cloud.wafflecommons.pixelbrainreader.ui.settings

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import cloud.wafflecommons.pixelbrainreader.data.ai.LocalAiManager
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoState
import cloud.wafflecommons.pixelbrainreader.data.ai.VectorSearchEngine
import cloud.wafflecommons.pixelbrainreader.data.auth.GoogleAuthRepository
import cloud.wafflecommons.pixelbrainreader.data.health.HealthConnectManager
import cloud.wafflecommons.pixelbrainreader.data.local.preferences.GamificationPreferences
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.model.AiModel
import cloud.wafflecommons.pixelbrainreader.data.repository.AppThemeConfig
import cloud.wafflecommons.pixelbrainreader.data.repository.ChoreRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.usecase.SyncHealthDataUseCase
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val context: Context = mockk(relaxed = true)
    private val userPrefs: UserPreferencesRepository = mockk(relaxed = true)
    private val secretManager: SecretManager = mockk(relaxed = true)
    private val vectorSearchEngine: VectorSearchEngine = mockk(relaxed = true)
    private val healthConnectManager: HealthConnectManager = mockk(relaxed = true)
    private val syncHealthDataUseCase: SyncHealthDataUseCase = mockk(relaxed = true)
    private val habitRepository: HabitRepository = mockk(relaxed = true)
    private val choreRepository: ChoreRepository = mockk(relaxed = true)
    private val gamificationPrefs: GamificationPreferences = mockk(relaxed = true)
    private val googleAuthManager: GoogleAuthRepository = mockk(relaxed = true)
    private val localAiManager: LocalAiManager = mockk(relaxed = true)
    private val vaultGraphAnalyzer: cloud.wafflecommons.pixelbrainreader.data.utils.VaultGraphAnalyzer = mockk(relaxed = true)
    private val workManager: WorkManager = mockk(relaxed = true)

    private val testDispatcher = StandardTestDispatcher()
    private val nanoStateFlow = MutableStateFlow<NanoState>(NanoState.NotDownloaded)
    private var viewModel: SettingsViewModel? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager
        every { workManager.getWorkInfosForUniqueWorkFlow(any()) } returns flowOf(emptyList())
        every { localAiManager.nanoState } returns nanoStateFlow
        every { userPrefs.listPaneWidth } returns flowOf(360f)
        every { userPrefs.themeConfig } returns flowOf(AppThemeConfig.FOLLOW_SYSTEM)
        every { userPrefs.selectedAiModel } returns flowOf(AiModel.CORTEX_LOCAL)
        every { userPrefs.embeddingModel } returns flowOf("sentences_encoder.tflite")
        every { userPrefs.llmModelName } returns flowOf("gemini-2.5-flash")
        every { userPrefs.isGoogleSyncEnabled } returns flowOf(false)
        every { userPrefs.soundEffectsEnabled } returns flowOf(false)
        every { googleAuthManager.isAccountLinked } returns MutableStateFlow(false)
        every { gamificationPrefs.moodEmojiMappingFlow } returns flowOf(emptyMap())
        every { secretManager.getRepoInfo() } returns Pair("testOwner", "testRepo")
    }

    @After
    fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        viewModel = null
        unmockkStatic(WorkManager::class)
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SettingsViewModel = SettingsViewModel(
        context = context,
        userPrefs = userPrefs,
        secretManager = secretManager,
        vectorSearchEngine = vectorSearchEngine,
        healthConnectManager = healthConnectManager,
        syncHealthDataUseCase = syncHealthDataUseCase,
        habitRepository = habitRepository,
        choreRepository = choreRepository,
        gamificationPrefs = gamificationPrefs,
        googleAuthManager = googleAuthManager,
        localAiManager = localAiManager,
        vaultGraphAnalyzer = vaultGraphAnalyzer
    ).also { viewModel = it }

    @Test
    fun updateAiModel_RejectsLocalWhenNanoNotReady() = runTest {
        nanoStateFlow.value = NanoState.NotDownloaded
        val vm = createViewModel()
        advanceUntilIdle()

        vm.updateAiModel(AiModel.CORTEX_LOCAL)
        advanceUntilIdle()

        coVerify(exactly = 0) { userPrefs.setAiModel(AiModel.CORTEX_LOCAL) }
    }

    @Test
    fun updateAiModel_AllowsLocalWhenNanoReady() = runTest {
        nanoStateFlow.value = NanoState.Ready
        val vm = createViewModel()
        advanceUntilIdle()

        vm.updateAiModel(AiModel.CORTEX_LOCAL)
        advanceUntilIdle()

        coVerify(exactly = 1) { userPrefs.setAiModel(AiModel.CORTEX_LOCAL) }
    }

    @Test
    fun updateTheme_PersistsThemeConfig() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.updateTheme(AppThemeConfig.DARK)
        advanceUntilIdle()

        coVerify(exactly = 1) { userPrefs.setThemeConfig(AppThemeConfig.DARK) }
    }
}
