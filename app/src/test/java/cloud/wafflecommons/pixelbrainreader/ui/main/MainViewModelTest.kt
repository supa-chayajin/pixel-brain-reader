package cloud.wafflecommons.pixelbrainreader.ui.main

import cloud.wafflecommons.pixelbrainreader.data.ai.GeminiRagManager
import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyNoteRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.TemplateRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultExportRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncOrchestrator
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncState
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.data.utils.SoundEffectManager
import cloud.wafflecommons.pixelbrainreader.ui.utils.UiEffectManager
import cloud.wafflecommons.pixelbrainreader.widget.manager.WidgetSnapshotManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val fileRepository: FileRepository = mockk(relaxed = true)
    private val dailyNoteRepository: DailyNoteRepository = mockk(relaxed = true)
    private val templateRepository: TemplateRepository = mockk(relaxed = true)
    private val secretManager: SecretManager = mockk(relaxed = true)
    private val userPrefs: UserPreferencesRepository = mockk(relaxed = true)
    private val geminiRagManager: GeminiRagManager = mockk(relaxed = true)
    private val widgetSnapshotManager: WidgetSnapshotManager = mockk(relaxed = true)
    private val uiEffectManager: UiEffectManager = mockk(relaxed = true)
    private val gamificationRepository: GamificationRepository = mockk(relaxed = true)
    private val syncOrchestrator: SyncOrchestrator = mockk(relaxed = true)
    private val vaultExportRepository: VaultExportRepository = mockk(relaxed = true)
    private val pushScheduler: VaultPushScheduler = mockk(relaxed = true)
    private val soundEffectManager: SoundEffectManager = mockk(relaxed = true)
    private val appClock: AppClock = mockk(relaxed = true)
    private val vaultDiscoveryRepository: cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository = mockk(relaxed = true)

    private lateinit var viewModel: MainViewModel
    private val testDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { appClock.today } returns MutableStateFlow(LocalDate.of(2026, 1, 1))
        every { appClock.foregroundEvents } returns MutableSharedFlow()
        every { syncOrchestrator.syncState } returns MutableStateFlow(SyncState.Idle)
        every { fileRepository.getFiles(any()) } returns flowOf(emptyList())
        every { fileRepository.getFileContentFlow(any()) } returns flowOf("")
        every { userPrefs.listPaneWidth } returns flowOf(360f)
        every { userPrefs.soundEffectsEnabled } returns flowOf(true)
        every { vaultDiscoveryRepository.observeAttachmentIndex() } returns
            flowOf(cloud.wafflecommons.pixelbrainreader.data.utils.VaultFileIndex.EMPTY)

        viewModel = MainViewModel(
            repository = fileRepository,
            dailyNoteRepository = dailyNoteRepository,
            templateRepository = templateRepository,
            secretManager = secretManager,
            userPrefs = userPrefs,
            geminiRagManager = geminiRagManager,
            widgetSnapshotManager = widgetSnapshotManager,
            uiEffectManager = uiEffectManager,
            gamificationRepository = gamificationRepository,
            syncOrchestrator = syncOrchestrator,
            vaultExportRepository = vaultExportRepository,
            pushScheduler = pushScheduler,
            soundEffectManager = soundEffectManager,
            appClock = appClock,
            appScope = CoroutineScope(testDispatcher),
            vaultDiscoveryRepository = vaultDiscoveryRepository
        )
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun refreshCurrentFolder_SyncsRepository_AndReconcilesVault() = runTest {
        val owner = "owner"
        val repo = "repo"
        every { secretManager.getRepoInfo() } returns Pair(owner, repo)
        coEvery { fileRepository.syncRepository(owner, repo) } returns Result.success(Unit)

        viewModel.refreshCurrentFolder()
        advanceUntilIdle()

        coVerify { fileRepository.syncRepository(owner, repo) }
        coVerify { syncOrchestrator.reconcileVaultIntoRoom() }
        coVerify { widgetSnapshotManager.updateSnapshot() }
    }

    @Test
    fun refreshCurrentFolder_DoesNotReconcile_OnFailure() = runTest {
        val owner = "owner"
        val repo = "repo"
        every { secretManager.getRepoInfo() } returns Pair(owner, repo)
        coEvery { fileRepository.syncRepository(owner, repo) } returns Result.failure(Exception("Network error"))

        viewModel.refreshCurrentFolder()
        advanceUntilIdle()

        coVerify { fileRepository.syncRepository(owner, repo) }
        coVerify(exactly = 0) { syncOrchestrator.reconcileVaultIntoRoom() }
    }

    @Test
    fun restoreRevision_WritesTheOldVersion_WithoutGrantingTaskXp_AndCanBeUndone() = runTest {
        val path = "20_Projects/plan.md"
        viewModel.loadFile(
            cloud.wafflecommons.pixelbrainreader.data.remote.model.GithubFileDto(
                name = "plan.md", path = path, type = "file",
                downloadUrl = null, sha = null, lastModified = 0L
            )
        )
        viewModel.onContentChanged("- [ ] ship it\n")
        advanceUntilIdle()

        // The old version had the task checked: restoring it is not the user completing a task.
        viewModel.restoreRevision("- [x] ship it\n")
        advanceUntilIdle()

        coVerify(exactly = 0) { gamificationRepository.processTaskCompletion(any()) }
        coVerify { fileRepository.saveFileLocally(path, "- [x] ship it\n") }
        assertEquals("- [x] ship it\n", viewModel.unsavedContent)
        assertEquals("Version restored", viewModel.restoreUndo.value)

        viewModel.undoRestore()
        advanceUntilIdle()

        coVerify { fileRepository.saveFileLocally(path, "- [ ] ship it\n") }
        assertEquals("- [ ] ship it\n", viewModel.unsavedContent)
        assertEquals(null, viewModel.restoreUndo.value)
    }

    @Test
    fun timeMachine_IsDisabledForEncryptedNotes() = runTest {
        assertEquals(emptyList<Any>(), viewModel.getFileCommitHistory("99_Private/diary.md.enc"))
        assertEquals(null, viewModel.getFileContentAtCommit("99_Private/diary.md.enc", "abc"))
        coVerify(exactly = 0) { fileRepository.getFileCommitHistory(any(), any()) }
    }

    @Test
    fun openVaultFile_ResolvesWikiLink_AndTriggersHomeNavigation() = runTest {
        viewModel.openVaultFile("[[My Note]]")

        val state = withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                viewModel.uiState.first { it.navigationTrigger == "home" }
            }
        }
        assertEquals("home", state.navigationTrigger)
        coVerify { fileRepository.resolveLink("My Note") }
    }
}
