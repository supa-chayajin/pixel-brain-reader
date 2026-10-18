package cloud.wafflecommons.pixelbrainreader.data.sync

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository
import cloud.wafflecommons.pixelbrainreader.data.guard.CommitHeldException
import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import cloud.wafflecommons.pixelbrainreader.data.remote.SyncResult
import cloud.wafflecommons.pixelbrainreader.data.repository.ChoreRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.GoogleCalendarRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.GoogleTaskRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodTagRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository
import cloud.wafflecommons.pixelbrainreader.data.usecase.SyncHealthDataUseCase
import cloud.wafflecommons.pixelbrainreader.di.ApplicationScope
import cloud.wafflecommons.pixelbrainreader.domain.gamification.AutomateHabitsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The granular sub-steps of a sync cycle, each with a user-facing label so the pull-to-refresh
 * indicator can show WHAT is being refreshed instead of a generic string.
 */
enum class SyncStep(val label: String) {
    PULLING("Fetching notes…"),
    INDEXING("Indexing vault…"),
    HEALTH("Syncing health…"),
    HABITS("Updating habits…"),
    GOOGLE("Syncing Google…"),
    PUSHING("Pushing changes…"),
    RECONCILING("Reconciling…")
}

/**
 * Represents the current state of the global sync cycle.
 */
sealed class SyncState {
    object Idle : SyncState()
    /** In progress. [step] names the current phase for the UI. */
    data class Syncing(val step: SyncStep = SyncStep.PULLING) : SyncState()
    object Success : SyncState()
    data class Error(val message: String) : SyncState()
}

/**
 * Global Sync Orchestrator.
 * Owns the strict Git→Health→Git sync sequence and exposes observable state.
 *
 * Design:
 * - Sequential execution: Pull (rebase) → Health Sync → Add/Commit/Push
 * - Mutex lock prevents concurrent sync cycles
 * - 60-second cooldown for automatic callers; user-initiated (forced) syncs bypass it
 * - All I/O runs on Dispatchers.IO
 */
@Singleton
class SyncOrchestrator @Inject constructor(
    private val jGitProvider: JGitProvider,
    private val syncHealthDataUseCase: SyncHealthDataUseCase,
    private val googleCalendarRepository: GoogleCalendarRepository,
    private val googleTaskRepository: GoogleTaskRepository,
    private val vaultDiscoveryRepository: VaultDiscoveryRepository,
    private val moodRepository: MoodRepository,
    private val moodTagRepository: MoodTagRepository,
    private val habitRepository: HabitRepository,
    private val choreRepository: ChoreRepository,
    private val gamificationRepository: GamificationRepository,
    private val automateHabitsUseCase: AutomateHabitsUseCase,
    private val dailyDashboardRepository: DailyDashboardRepository,
    private val gitSyncCoordinator: GitSyncCoordinator,
    @param:ApplicationScope private val appScope: CoroutineScope,
    private val modesRepository: dagger.Lazy<cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository>
) {
    companion object {
        private const val TAG = "SyncOrchestrator"
        private const val COOLDOWN_MS = 60_000L // 60 seconds
    }

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    // Shared with FileRepository.syncRepository so explicit saves and the foreground
    // cycle can't interleave their commit→pull→push sequences.
    private val syncMutex get() = gitSyncCoordinator.mutex

    @Volatile
    private var lastSyncTimestamp: Long = 0L

    /**
     * Fire-and-forget entry point for the UI (pull-to-refresh). The cycle runs in the APPLICATION
     * scope, not the caller's viewModelScope: leaving the screen mid-sync used to cancel the cycle
     * between its commit and its push.
     */
    fun requestSync(force: Boolean = false) {
        appScope.launch {
            try {
                executeFullSyncCycle(force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Sync cycle crashed", e)
            }
        }
    }

    /**
     * Executes the full sync cycle: Pull (rebase) → Health Sync → Add/Commit/Push.
     *
     * - Skips when a cycle is already running.
     * - Automatic callers skip when the git lock is busy or during the cooldown; a user-initiated
     *   ([force]) sync waits for the lock (e.g. a background push) instead of silently no-op'ing.
     * - Health / Google failures are non-fatal and logged.
     *
     * @return true if the full cycle completed successfully.
     */
    suspend fun executeFullSyncCycle(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (_syncState.value is SyncState.Syncing) {
            Log.d(TAG, "Sync skipped: a cycle is already running")
            return@withContext false
        }
        if (force) {
            // Show progress right away: a user-initiated sync may first wait for a background git
            // writer (batched push, foreground health refresh) to release the lock.
            _syncState.value = SyncState.Syncing(SyncStep.PULLING)
            try {
                syncMutex.lock()
            } catch (e: CancellationException) {
                if (_syncState.value is SyncState.Syncing) _syncState.value = SyncState.Idle
                throw e
            }
        } else if (!syncMutex.tryLock()) {
            Log.d(TAG, "Sync skipped: git lock busy")
            return@withContext false
        }

        try {
            runCycle(force)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Sync cycle failed unexpectedly", e)
            _syncState.value = SyncState.Error("Sync failed: ${e.message}")
            lastSyncTimestamp = System.currentTimeMillis()
            false
        } finally {
            // A cancelled (or crashed) cycle must never leave the UI spinning forever.
            if (_syncState.value is SyncState.Syncing) _syncState.value = SyncState.Idle
            syncMutex.unlock()
        }
    }

    private suspend fun runCycle(force: Boolean): Boolean {
        // Cooldown check — bypassed for user-initiated (force) refreshes so a manual
        // pull-to-refresh always does something instead of silently no-op'ing.
        val now = System.currentTimeMillis()
        if (!force && now - lastSyncTimestamp < COOLDOWN_MS) {
            Log.d(TAG, "Sync skipped: cooldown active (${(COOLDOWN_MS - (now - lastSyncTimestamp)) / 1000}s remaining)")
            return false
        }

        // Guard: repo must be initialized
        if (!jGitProvider.isReady()) {
            Log.w(TAG, "Sync skipped: repository not initialized")
            return false
        }

        _syncState.value = SyncState.Syncing(SyncStep.PULLING)
        Log.i(TAG, "=== Starting Full Sync Cycle ===")

        // Phase 0a: Burn today's Room-only board (dashboard/timeline/tasks/scraps/gratitude)
        // to its markdown file BEFORE we commit, so the vault copy is always fresh and ships
        // in the same commit as everything else. Non-fatal.
        Log.i(TAG, "Phase 0a: Eager burn of today's board...")
        runCatching { dailyDashboardRepository.burnToDisk(LocalDate.now()) }
            .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Phase 0a: Eager burn failed (non-fatal)", it) }

        // Phase 0: Commit any pending local edits BEFORE the pull. JGit's rebase requires a
        // clean working tree; commit() self-stages and is a no-op when nothing changed.
        // A commit held by the confidentiality guard stops the cycle here: the edits stay staged,
        // so the rebase couldn't run anyway, and the user has something to review.
        Log.i(TAG, "Phase 0: Pre-pull commit of local edits...")
        val preCommit = jGitProvider.commit("Auto-sync: local edits before pull")
        when (val failure = preCommit.exceptionOrNull()) {
            null -> Unit
            is CancellationException -> throw failure
            is CommitHeldException -> {
                Log.w(TAG, "Phase 0: commit held by the confidentiality guard; sync stopped before the pull")
                _syncState.value = SyncState.Error(failure.message.orEmpty())
                lastSyncTimestamp = System.currentTimeMillis()
                return false
            }
            else -> Log.w(TAG, "Phase 0: Pre-pull commit failed (non-fatal)", failure)
        }

        // Phase 1: Git Pull (Rebase)
        Log.i(TAG, "Phase 1: Pull (rebase)...")
        when (val pullResult = jGitProvider.pull()) {
            is SyncResult.Success -> Log.i(TAG, "Phase 1: Pull OK")
            is SyncResult.ResolvedWithConflicts -> {
                // pull() already aborted the rebase and backed up the conflicting local files:
                // local now DIVERGES from remote, so a push could never fast-forward. Stop with
                // an actionable state instead of pushing into diverged history.
                Log.w(TAG, "Phase 1: Pull hit ${pullResult.backedUpFilesCount} conflict(s); local backed up, manual merge needed")
                _syncState.value = SyncState.Error(
                    "Sync conflict: your local changes were saved. Manual merge required."
                )
                lastSyncTimestamp = System.currentTimeMillis()
                return false
            }
            is SyncResult.Error -> {
                Log.e(TAG, "Phase 1: Pull FAILED", pullResult.exception)
                _syncState.value = SyncState.Error("Pull failed: ${pullResult.exception.message}")
                lastSyncTimestamp = System.currentTimeMillis()
                return false
            }
        }

        // Phase 1.5: Reindex Room from vault FS — keeps the index honest after the pull.
        _syncState.value = SyncState.Syncing(SyncStep.INDEXING)
        Log.i(TAG, "Phase 1.5: Reindexing Room (post-pull)...")
        runCatching { vaultDiscoveryRepository.reindexAll(0L) }
            .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Post-pull reindex failed (non-fatal)", it) }

        // Phase 2: Health Data Sync (non-fatal)
        _syncState.value = SyncState.Syncing(SyncStep.HEALTH)
        Log.i(TAG, "Phase 2: Health data sync...")
        try {
            val healthResult = syncHealthDataUseCase.invoke()
            if (healthResult.isSuccess) {
                Log.i(TAG, "Phase 2: Health sync OK")
            } else {
                Log.w(TAG, "Phase 2: Health sync failed (non-fatal): ${healthResult.exceptionOrNull()?.message}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Phase 2: Health sync exception (non-fatal)", e)
        }

        // Phase 2.6: Habit automation — propagate the freshly-written health metrics into habit
        // logs BEFORE the push, so the same commit ships both. Non-fatal.
        _syncState.value = SyncState.Syncing(SyncStep.HABITS)
        Log.i(TAG, "Phase 2.6: Habit automation (health → habits)...")
        runCatching { automateHabitsUseCase(LocalDate.now()) }
            .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Phase 2.6: Habit automation failed (non-fatal)", it) }

        // Phase 2.5: Google Ecosystem Sync (non-fatal)
        _syncState.value = SyncState.Syncing(SyncStep.GOOGLE)
        Log.i(TAG, "Phase 2.5: Google Ecosystem sync...")
        try {
            val calRes = googleCalendarRepository.syncTodayEvents()
            val taskRes = googleTaskRepository.syncPendingTasks()
            calRes.fold(
                onSuccess = { Log.i(TAG, "Phase 2.5: Calendar import returned $it event(s)") },
                onFailure = { Log.w(TAG, "Phase 2.5: Calendar import failed", it) }
            )
            taskRes.fold(
                onSuccess = { Log.i(TAG, "Phase 2.5: Tasks import returned $it task(s)") },
                onFailure = { Log.w(TAG, "Phase 2.5: Tasks import failed", it) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Phase 2.5: Google sync threw (non-fatal)", e)
        }

        // Phase 3: Commit + Push (commit() stages everything itself)
        _syncState.value = SyncState.Syncing(SyncStep.PUSHING)
        Log.i(TAG, "Phase 3: Commit/Push...")
        // What the middle phases wrote can be held too; the commits already recorded passed the
        // guard, so they are still pushed, and the cycle ends on the hold instead of Success.
        var held: CommitHeldException? = null
        try {
            held = jGitProvider.commit("Auto-sync: lifecycle foreground cycle").exceptionOrNull() as? CommitHeldException
            val pushResult = jGitProvider.push()
            if (pushResult.isSuccess) {
                Log.i(TAG, "Phase 3: Push OK")
            } else {
                // A failed or REJECTED push is a Result.failure, not an exception — surface it,
                // never fall through to Success.
                val msg = pushResult.exceptionOrNull()?.message ?: "unknown push failure"
                Log.e(TAG, "Phase 3: Push FAILED: $msg")
                _syncState.value = SyncState.Error("Push failed: $msg")
                lastSyncTimestamp = System.currentTimeMillis()
                return false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Phase 3: Commit/Push failed", e)
            _syncState.value = SyncState.Error("Push failed: ${e.message}")
            lastSyncTimestamp = System.currentTimeMillis()
            return false
        }

        // Phase 3.5: Reindex Room from vault FS so post-write state is reflected in the index.
        Log.i(TAG, "Phase 3.5: Reindexing Room (post-push)...")
        runCatching { vaultDiscoveryRepository.reindexAll(0L) }
            .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Post-push reindex failed (non-fatal)", it) }

        // Phase 4: Reconcile the JSON-backed domains (newly pulled from git) into Room inline,
        // so the UI sees freshly synced state before this method returns.
        _syncState.value = SyncState.Syncing(SyncStep.RECONCILING)
        Log.i(TAG, "Phase 4: Inline vault JSON → Room reconcile...")
        reconcileVaultIntoRoom()

        lastSyncTimestamp = System.currentTimeMillis()
        if (held != null) {
            Log.w(TAG, "=== Sync Cycle Complete, new changes held by the confidentiality guard ===")
            _syncState.value = SyncState.Error(held.message.orEmpty())
            return false
        }
        Log.i(TAG, "=== Full Sync Cycle Complete ===")
        _syncState.value = SyncState.Success
        return true
    }

    /**
     * Rebuilds the state derived from the vault JSON — Room's Mood / Habit / Chore tables, the
     * gamification profile and the mood-tag list. Shared by the sync cycle, the file-list refresh,
     * cold start and first login (whose clone used to leave these tables empty until the next
     * restart). Each step is independent, non-fatal, and writes nothing when the vault is unchanged.
     */
    suspend fun reconcileVaultIntoRoom() {
        reconcileStep("Mood") { moodRepository.syncWithFileSystem() }
        reconcileStep("Mood tags") { moodTagRepository.reload() }
        reconcileStep("Habit") { habitRepository.syncWithFileSystem() }
        reconcileStep("Chore") { choreRepository.syncWithFileSystem() }
        reconcileStep("Gamification") { gamificationRepository.loadState() }
        reconcileStep("Modes") { modesRepository.get().reload() }
    }

    private suspend fun reconcileStep(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$name reconcile failed (non-fatal)", e)
        }
    }
}
