package cloud.wafflecommons.pixelbrainreader.ui.settings

import cloud.wafflecommons.pixelbrainreader.data.modes.ModeAppearance
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.utils.VaultGraphAnalyzer
import cloud.wafflecommons.pixelbrainreader.data.utils.VaultGraphReport
import cloud.wafflecommons.pixelbrainreader.ui.cancelAndAwait
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Settings home's ViewModel (13.4): its two device switches, the owner's names, the link check. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val preferences: UserPreferencesRepository = mockk(relaxed = true)
    private val modes: ModesRepository = mockk(relaxed = true)
    private val vaultGraphAnalyzer: VaultGraphAnalyzer = mockk()

    private val testDispatcher = StandardTestDispatcher()
    private val soundEffects = MutableStateFlow(false)
    private val visibility = MutableStateFlow(Visibility())
    private var viewModel: SettingsViewModel? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { preferences.soundEffectsEnabled } returns soundEffects
        every { modes.visibility } returns visibility
    }

    @After
    fun tearDown() {
        viewModel?.cancelAndAwait(testDispatcher.scheduler)
        viewModel = null
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SettingsViewModel =
        SettingsViewModel(preferences, modes, vaultGraphAnalyzer).also { viewModel = it }

    @Test
    fun `sound effects persist`() = runTest(testDispatcher) {
        val vm = createViewModel()

        vm.setSoundEffects(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferences.setSoundEffectsEnabled(true) }
    }

    @Test
    fun `screen-share safe goes to the modes`() = runTest(testDispatcher) {
        val vm = createViewModel()

        vm.setScreenShareSafe(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { modes.setScreenShareSafe(true) }
    }

    @Test
    fun `the vault check fills its report`() = runTest(testDispatcher) {
        val report = VaultGraphReport(
            totalNotesAnalyzed = 3,
            totalWikiLinksFound = 5,
            deadLinks = emptyList(),
            orphanNotes = listOf("00_Inbox/Lonely.md"),
            healthScore = 67
        )
        coEvery { vaultGraphAnalyzer.analyze() } returns report
        val vm = createViewModel()

        vm.analyzeVaultGraph()
        advanceUntilIdle()

        assertEquals(report, vm.vaultGraphReport.value)
        assertFalse(vm.isAnalyzingGraph.value)
    }

    @Test
    fun `a failed vault check leaves no report and stops analyzing`() = runTest(testDispatcher) {
        coEvery { vaultGraphAnalyzer.analyze() } throws IllegalStateException("broken index")
        val vm = createViewModel()

        vm.analyzeVaultGraph()
        advanceUntilIdle()

        assertEquals(null, vm.vaultGraphReport.value)
        assertFalse(vm.isAnalyzingGraph.value)
    }

    @Test
    fun `the state shows the switches and the owner's names`() = runTest(testDispatcher) {
        soundEffects.value = true
        visibility.value = Visibility(screenShareSafe = true, appearance = ModeAppearance(toolkit = "Workbench"))
        val vm = createViewModel()
        backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.soundEffects)
        assertTrue(state.screenShareSafe)
        assertEquals("Workbench", state.appearance.toolkitName)
    }
}
