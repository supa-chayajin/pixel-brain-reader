package cloud.wafflecommons.pixelbrainreader.data.sync

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import cloud.wafflecommons.pixelbrainreader.data.remote.SyncResult
import cloud.wafflecommons.pixelbrainreader.data.repository.ChoreRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.GoogleCalendarRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.GoogleTaskRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository
import cloud.wafflecommons.pixelbrainreader.data.usecase.SyncHealthDataUseCase
import cloud.wafflecommons.pixelbrainreader.domain.gamification.AutomateHabitsUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression guards for the sync-correctness fixes:
 *  - P0.2: a failed push (or a rebase-conflict) must surface [SyncState.Error], NEVER Success.
 *  - P0.5: a [CancellationException] must propagate, not be swallowed into an Error toast.
 */
class SyncOrchestratorTest {

    private lateinit var jGit: JGitProvider
    private lateinit var health: SyncHealthDataUseCase
    private lateinit var calendar: GoogleCalendarRepository
    private lateinit var tasks: GoogleTaskRepository
    private lateinit var vault: VaultDiscoveryRepository
    private lateinit var mood: MoodRepository
    private lateinit var habit: HabitRepository
    private lateinit var chore: ChoreRepository
    private lateinit var automate: AutomateHabitsUseCase
    private lateinit var daily: DailyDashboardRepository
    private lateinit var orchestrator: SyncOrchestrator

    @Before
    fun setUp() {
        jGit = mockk(relaxed = true)
        health = mockk(relaxed = true)
        calendar = mockk(relaxed = true)
        tasks = mockk(relaxed = true)
        vault = mockk(relaxed = true)
        mood = mockk(relaxed = true)
        habit = mockk(relaxed = true)
        chore = mockk(relaxed = true)
        automate = mockk(relaxed = true)
        daily = mockk(relaxed = true)

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
            context = mockk<Context>(relaxed = true),
            jGitProvider = jGit,
            syncHealthDataUseCase = health,
            googleCalendarRepository = calendar,
            googleTaskRepository = tasks,
            vaultDiscoveryRepository = vault,
            moodRepository = mood,
            habitRepository = habit,
            choreRepository = chore,
            automateHabitsUseCase = automate,
            dailyDashboardRepository = daily,
            gitSyncCoordinator = GitSyncCoordinator()
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
    fun `cancellation propagates instead of being swallowed into Error`() {
        coEvery { health.invoke(any()) } throws CancellationException("navigated away")

        assertThrows(CancellationException::class.java) {
            runBlocking { orchestrator.executeFullSyncCycle(force = true) }
        }
    }
}
