package cloud.wafflecommons.pixelbrainreader.ui.sync

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.guard.CommitHeldException
import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import cloud.wafflecommons.pixelbrainreader.data.sync.ConflictChangedException
import cloud.wafflecommons.pixelbrainreader.data.sync.ConflictChoice
import cloud.wafflecommons.pixelbrainreader.data.sync.ConflictFile
import cloud.wafflecommons.pixelbrainreader.data.sync.PendingConflict
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncOrchestrator
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncState
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.ui.cancelAndAwait
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The conflict screen, Daily's "Sync stopped" banner and Settings' row (12.15, S1), over a mocked provider. */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncConflictViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val provider: JGitProvider = mockk()
    private val orchestrator: SyncOrchestrator = mockk()
    private val syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    private val foregroundEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val stops = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var screen: SyncConflictViewModel? = null
    private var pendingVm: PendingConflictViewModel? = null

    private val story = ConflictFile("notes/story.md", "mine\n", "theirs\n", "<<<<<<< téléphone\nmine\n=======\ntheirs\n>>>>>>> GitHub\n")
    private val habits = ConflictFile("10_Journal/data/habits.json", "{}", "{}", null)
    private val pending = PendingConflict("abc", listOf(story.path, habits.path), emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { orchestrator.syncState } returns syncState
        every { orchestrator.requestSync(any()) } just runs
        every { provider.conflictStops } returns stops
        coEvery { provider.pendingConflict() } returns pending
    }

    @After
    fun tearDown() {
        screen?.cancelAndAwait(testDispatcher.scheduler)
        pendingVm?.cancelAndAwait(testDispatcher.scheduler)
        Dispatchers.resetMain()
        VaultWriteGate.readOnly = false
    }

    private fun screen() = SyncConflictViewModel(provider, orchestrator).also { screen = it }

    private fun pendingVm(): PendingConflictViewModel {
        val clock = mockk<AppClock>()
        every { clock.foregroundEvents } returns foregroundEvents
        return PendingConflictViewModel(provider, orchestrator, clock).also { pendingVm = it }
    }

    // --- The screen ---

    @Test
    fun `the conflict's files load when the screen opens`() = runTest(testDispatcher) {
        coEvery { provider.conflictFiles() } returns Result.success(listOf(story, habits))

        val vm = screen()
        advanceUntilIdle()

        assertEquals(listOf(story, habits), vm.ui.value.files)
        assertTrue(vm.ui.value.waiting)
        assertFalse(vm.ui.value.loading)
    }

    @Test
    fun `with no conflict remembered, nothing is waiting and nothing is read`() = runTest(testDispatcher) {
        coEvery { provider.pendingConflict() } returns null

        val vm = screen()
        advanceUntilIdle()

        assertFalse(vm.ui.value.waiting)
        coVerify(exactly = 0) { provider.conflictFiles() }
    }

    @Test
    fun `resolving sends each file's choice, then a sync brings the app up to date`() = runTest(testDispatcher) {
        coEvery { provider.conflictFiles() } returns Result.success(listOf(story, habits))
        coEvery { provider.resolveConflicts(any()) } returns Result.success(true)
        val vm = screen()
        advanceUntilIdle()

        vm.choose(story.path, ConflictChoice.Edited("à la main\n"))
        vm.choose(habits.path, ConflictChoice.Theirs)
        vm.resolve()
        advanceUntilIdle()

        coVerify { provider.resolveConflicts(mapOf(story.path to ConflictChoice.Edited("à la main\n"), habits.path to ConflictChoice.Theirs)) }
        assertEquals(true, vm.ui.value.pushed)
        assertFalse(vm.ui.value.resolving)
        verify(exactly = 1) { orchestrator.requestSync(true) }
    }

    @Test
    fun `a conflict whose files no longer conflict resumes syncing with no choice`() = runTest(testDispatcher) {
        coEvery { provider.conflictFiles() } returns Result.success(emptyList())
        coEvery { provider.resolveConflicts(any()) } returns Result.success(true)
        val vm = screen()
        advanceUntilIdle()
        assertTrue(vm.ui.value.waiting)
        assertEquals(emptyList<ConflictFile>(), vm.ui.value.files)

        vm.resolve()
        advanceUntilIdle()

        coVerify { provider.resolveConflicts(emptyMap()) }
        assertEquals(true, vm.ui.value.pushed)
    }

    @Test
    fun `the editor opens on git's marked merge, then on the text saved, and a cancel keeps the choice`() = runTest(testDispatcher) {
        coEvery { provider.conflictFiles() } returns Result.success(listOf(story))
        val vm = screen()
        advanceUntilIdle()

        vm.startEdit(story)
        assertEquals(story.path, vm.editingPath)
        assertEquals(story.merged, vm.draft)
        vm.draft = "à la main\n"
        vm.saveEdit()
        assertNull(vm.editingPath)
        assertEquals(ConflictChoice.Edited("à la main\n"), vm.choices[story.path])

        vm.startEdit(story)
        assertEquals("à la main\n", vm.draft)
        vm.draft = "autre chose\n"
        vm.cancelEdit()
        assertEquals(ConflictChoice.Edited("à la main\n"), vm.choices[story.path])
    }

    @Test
    fun `GitHub moving again reloads the files, keeping the choices that still apply`() = runTest(testDispatcher) {
        val other = ConflictFile("notes/autre.md", "a\n", "b\n", null)
        coEvery { provider.conflictFiles() } returnsMany listOf(Result.success(listOf(story, habits)), Result.success(listOf(story, other)))
        coEvery { provider.resolveConflicts(any()) } returns Result.failure(ConflictChangedException(listOf(other.path)))
        val vm = screen()
        advanceUntilIdle()
        vm.choose(story.path, ConflictChoice.Mine)
        vm.choose(habits.path, ConflictChoice.Mine)

        vm.resolve()
        advanceUntilIdle()

        assertEquals(listOf(story, other), vm.ui.value.files)
        assertEquals(mapOf<String, ConflictChoice>(story.path to ConflictChoice.Mine), vm.choices.toMap())
        assertTrue(vm.ui.value.error!!.startsWith("GitHub changed again"))
        assertNull(vm.ui.value.pushed)
        verify(exactly = 0) { orchestrator.requestSync(any()) }
    }

    @Test
    fun `a pull that stops again while the screen is open shows the new files`() = runTest(testDispatcher) {
        val other = ConflictFile("notes/autre.md", "a\n", "b\n", null)
        coEvery { provider.conflictFiles() } returnsMany listOf(Result.success(listOf(story)), Result.success(listOf(other)))
        coEvery { provider.resolveConflicts(any()) } returns Result.success(true)
        val vm = screen()
        advanceUntilIdle()
        vm.choose(story.path, ConflictChoice.Mine)
        vm.resolve()
        advanceUntilIdle()
        assertEquals(true, vm.ui.value.pushed)

        stops.emit(Unit) // the sync after the resolution stopped on a new conflict
        advanceUntilIdle()

        assertEquals(listOf(other), vm.ui.value.files)
        assertNull(vm.ui.value.pushed)
    }

    @Test
    fun `a resolution the confidentiality guard holds says so, and nothing syncs`() = runTest(testDispatcher) {
        coEvery { provider.conflictFiles() } returns Result.success(listOf(story))
        coEvery { provider.resolveConflicts(any()) } returns Result.failure(CommitHeldException(emptyList()))
        val vm = screen()
        advanceUntilIdle()
        vm.choose(story.path, ConflictChoice.Mine)

        vm.resolve()
        advanceUntilIdle()

        assertTrue(vm.ui.value.error!!.contains("Confidentiality"))
        assertFalse(vm.ui.value.resolving)
        assertNull(vm.ui.value.pushed)
        verify(exactly = 0) { orchestrator.requestSync(any()) }
    }

    // --- The banner and the Settings row ---

    @Test
    fun `the waiting conflict shows, follows a stopped pull, and clears after a sync once resolved`() = runTest(testDispatcher) {
        coEvery { provider.pendingConflict() } returns null
        val vm = pendingVm()
        advanceUntilIdle()
        assertNull(vm.pending.value)

        coEvery { provider.pendingConflict() } returns pending
        stops.emit(Unit) // a pull from the file list stopped: no sync state changed
        advanceUntilIdle()
        assertEquals(pending, vm.pending.value)

        coEvery { provider.pendingConflict() } returns null
        syncState.value = SyncState.Success
        advanceUntilIdle()
        assertNull(vm.pending.value)
    }

    @Test
    fun `a read-only device never shows it`() = runTest(testDispatcher) {
        VaultWriteGate.readOnly = true

        val vm = pendingVm()
        advanceUntilIdle()

        assertNull(vm.pending.value)
        coVerify(exactly = 0) { provider.pendingConflict() }
    }
}
