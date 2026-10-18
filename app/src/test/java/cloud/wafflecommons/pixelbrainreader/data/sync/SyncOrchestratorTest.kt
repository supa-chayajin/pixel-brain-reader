package cloud.wafflecommons.pixelbrainreader.data.sync

import cloud.wafflecommons.pixelbrainreader.data.guard.CommitHeldException
import cloud.wafflecommons.pixelbrainreader.data.guard.FindingKind
import cloud.wafflecommons.pixelbrainreader.data.guard.GuardFinding
import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import cloud.wafflecommons.pixelbrainreader.data.remote.SyncResult
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.GoogleCalendarRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.GoogleTaskRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository
import cloud.wafflecommons.pixelbrainreader.data.usecase.SyncHealthDataUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression guards for the sync-correctness fixes:
 *  - a failed push (or a rebase-conflict) must surface [SyncState.Error], NEVER Success;
 *  - a [CancellationException] must propagate, not be swallowed into an Error toast;
 *  - a cancelled cycle must never leave the state stuck on [SyncState.Syncing];
 *  - a user-forced sync waits for a busy git lock instead of silently doing nothing.
 */
class SyncOrchestratorTest {

    private lateinit var jGit: JGitProvider
    private lateinit var health: SyncHealthDataUseCase
    private lateinit var calendar: GoogleCalendarRepository
    private lateinit var tasks: GoogleTaskRepository
    private lateinit var vault: VaultDiscoveryRepository
    private lateinit var daily: DailyDashboardRepository
    private lateinit var coordinator: GitSyncCoordinator
    private lateinit var orchestrator: SyncOrchestrator

    @Before
    fun setUp() {
        jGit = mockk(relaxed = true)
        health = mockk(relaxed = true)
        calendar = mockk(relaxed = true)
        tasks = mockk(relaxed = true)
        vault = mockk(relaxed = true)
        daily = mockk(relaxed = true)
        coordinator = GitSyncCoordinator()

        // Happy-path defaults; individual tests override the interesting call.
        every { jGit.isReady() } returns true
        coEvery { jGit.commit(any()) } returns Result.success(Unit)
        coEvery { jGit.addAll() } returns Result.success(Unit)
        coEvery { jGit.pull() } returns SyncResult.Success
        coEvery { jGit.push() } returns Result.success(Unit)
        coEvery { health.invoke(any()) } returns Result.success(Unit)
        coEvery { calendar.syncTodayEvents(any()) } returns Result.success(0)
        coEvery { tasks.syncPendingTasks(any()) } returns Result.success(0)
        coEvery { vault.reindexAll(any()) } returns emptyList()

        orchestrator = SyncOrchestrator(
            jGitProvider = jGit,
            syncHealthDataUseCase = health,
            googleCalendarRepository = calendar,
            googleTaskRepository = tasks,
            vaultDiscoveryRepository = vault,
            moodRepository = mockk(relaxed = true),
            moodTagRepository = mockk(relaxed = true),
            habitRepository = mockk(relaxed = true),
            choreRepository = mockk(relaxed = true),
            gamificationRepository = mockk(relaxed = true),
            automateHabitsUseCase = mockk(relaxed = true),
            dailyDashboardRepository = daily,
            gitSyncCoordinator = coordinator,
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            modesRepository = { mockk(relaxed = true) }
        )
    }

    @Test
    fun `happy path reports Success`() = runBlocking {
        val ran = orchestrator.executeFullSyncCycle(force = true)
        assertTrue(ran)
        assertTrue(orchestrator.syncState.value is SyncState.Success)
    }

    @Test
    fun `push failure surfaces Error and never reports Success`() = runBlocking {
        coEvery { jGit.push() } returns Result.failure(RuntimeException("non-fast-forward"))

        val ran = orchestrator.executeFullSyncCycle(force = true)

        assertFalse(ran)
        assertTrue(
            "push failure must not report Success",
            orchestrator.syncState.value is SyncState.Error
        )
    }

    @Test
    fun `rebase conflict surfaces Error and skips the doomed push`() = runBlocking {
        coEvery { jGit.pull() } returns SyncResult.ResolvedWithConflicts(2)

        val ran = orchestrator.executeFullSyncCycle(force = true)

        assertFalse(ran)
        assertTrue(orchestrator.syncState.value is SyncState.Error)
        coVerify(exactly = 0) { jGit.push() } // don't push into a diverged history
    }

    @Test
    fun `a commit held by the confidentiality guard stops the cycle before the pull`() = runBlocking {
        coEvery { jGit.commit(any()) } returns Result.failure(CommitHeldException(listOf(heldFinding)))

        val ran = orchestrator.executeFullSyncCycle(force = true)

        assertFalse(ran)
        val state = orchestrator.syncState.value
        assertTrue(state is SyncState.Error && state.message.startsWith("Sync held"))
        coVerify(exactly = 0) { jGit.pull() }
        coVerify(exactly = 0) { jGit.push() }
    }

    @Test
    fun `a hold on the final commit still pushes what passed, then reports the hold`() = runBlocking {
        coEvery { jGit.commit("Auto-sync: lifecycle foreground cycle") } returns
            Result.failure(CommitHeldException(listOf(heldFinding)))

        val ran = orchestrator.executeFullSyncCycle(force = true)

        assertFalse(ran)
        assertTrue(orchestrator.syncState.value is SyncState.Error)
        coVerify(exactly = 1) { jGit.push() }
    }

    private val heldFinding = GuardFinding("00_Inbox/x.md", FindingKind.SECRET, "secret:aws:0", "AWS access key", 1, "AKIA…")

    @Test
    fun `cancellation propagates instead of being swallowed into Error`() {
        coEvery { health.invoke(any()) } throws CancellationException("navigated away")

        assertThrows(CancellationException::class.java) {
            runBlocking { orchestrator.executeFullSyncCycle(force = true) }
        }
    }

    @Test
    fun `a cancelled cycle never leaves the state stuck on Syncing and releases the git lock`() {
        coEvery { health.invoke(any()) } throws CancellationException("navigated away")

        assertThrows(CancellationException::class.java) {
            runBlocking { orchestrator.executeFullSyncCycle(force = true) }
        }

        assertFalse(
            "a cancelled cycle must not leave the pull-to-refresh spinner running",
            orchestrator.syncState.value is SyncState.Syncing
        )
        assertFalse("the git lock must be released", coordinator.mutex.isLocked)
    }

    @Test
    fun `an unexpected exception surfaces Error instead of a stuck spinner`() = runBlocking {
        coEvery { jGit.pull() } throws IllegalStateException("boom")

        val ran = orchestrator.executeFullSyncCycle(force = true)

        assertFalse(ran)
        assertTrue(orchestrator.syncState.value is SyncState.Error)
        assertFalse(coordinator.mutex.isLocked)
    }

    @Test
    fun `automatic sync skips while the git lock is busy`() = runBlocking {
        coordinator.mutex.lock()
        try {
            assertFalse(orchestrator.executeFullSyncCycle(force = false))
            coVerify(exactly = 0) { jGit.pull() }
        } finally {
            coordinator.mutex.unlock()
        }
    }

    @Test
    fun `forced sync waits for a busy git lock instead of silently skipping`() = runBlocking {
        coordinator.mutex.lock()
        val pending = async(Dispatchers.Default) { orchestrator.executeFullSyncCycle(force = true) }
        delay(150)
        assertTrue("forced sync must wait, not return early", pending.isActive)

        coordinator.mutex.unlock()

        assertTrue(pending.await())
        assertTrue(orchestrator.syncState.value is SyncState.Success)
    }
}
