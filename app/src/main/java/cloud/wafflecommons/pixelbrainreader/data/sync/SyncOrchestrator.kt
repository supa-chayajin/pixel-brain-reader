package cloud.wafflecommons.pixelbrainreader.data.sync

import android.content.Context
import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import cloud.wafflecommons.pixelbrainreader.data.remote.SyncResult
import cloud.wafflecommons.pixelbrainreader.data.usecase.SyncHealthDataUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Represents the current state of the global sync cycle.
 */
sealed class SyncState {
    object Idle : SyncState()
    object Syncing : SyncState()
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
 * - 60-second cooldown prevents server spam on rapid foreground transitions
 * - All I/O runs on Dispatchers.IO
 */
@Singleton
class SyncOrchestrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val jGitProvider: JGitProvider,
    private val syncHealthDataUseCase: SyncHealthDataUseCase,
    private val googleCalendarRepository: cloud.wafflecommons.pixelbrainreader.data.repository.GoogleCalendarRepository,
    private val googleTaskRepository: cloud.wafflecommons.pixelbrainreader.data.repository.GoogleTaskRepository,
    private val vaultDiscoveryRepository: cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository,
    private val moodRepository: cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository,
    private val habitRepository: cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository,
    private val choreRepository: cloud.wafflecommons.pixelbrainreader.data.repository.ChoreRepository,
    private val automateHabitsUseCase: cloud.wafflecommons.pixelbrainreader.domain.gamification.AutomateHabitsUseCase
) {
    companion object {
        private const val TAG = "SyncOrchestrator"
        private const val COOLDOWN_MS = 60_000L // 60 seconds
    }

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val syncMutex = Mutex()
    private var lastSyncTimestamp: Long = 0L

    /**
     * Executes the full sync cycle: Pull (rebase) → Health Sync → Add/Commit/Push.
     *
     * - Skips if a sync is already in progress (Mutex).
     * - Skips if the last sync completed less than [COOLDOWN_MS] ago.
     * - Health sync failures are non-fatal and logged.
     *
     * @return true if the sync was executed, false if it was skipped.
     */
    suspend fun executeFullSyncCycle(): Boolean = withContext(Dispatchers.IO) {
        // Skip if Mutex is already held (sync in progress)
        if (!syncMutex.tryLock()) {
            Log.d(TAG, "Sync skipped: already in progress")
            return@withContext false
        }

        try {
            // Cooldown check
            val now = System.currentTimeMillis()
            if (now - lastSyncTimestamp < COOLDOWN_MS) {
                Log.d(TAG, "Sync skipped: cooldown active (${(COOLDOWN_MS - (now - lastSyncTimestamp)) / 1000}s remaining)")
                return@withContext false
            }

            // Guard: repo must be initialized
            if (!jGitProvider.isReady()) {
                Log.w(TAG, "Sync skipped: repository not initialized")
                return@withContext false
            }

            _syncState.value = SyncState.Syncing
            Log.i(TAG, "=== Starting Full Sync Cycle ===")

            // Phase 1: Git Pull (Rebase)
            Log.i(TAG, "Phase 1: Pull (rebase)...")
            val pullResult = jGitProvider.pull()
            when (pullResult) {
                is SyncResult.Success -> Log.i(TAG, "Phase 1: Pull OK")
                is SyncResult.ResolvedWithConflicts -> Log.w(TAG, "Phase 1: Pull resolved ${pullResult.backedUpFilesCount} conflicts")
                is SyncResult.Error -> {
                    Log.e(TAG, "Phase 1: Pull FAILED", pullResult.exception)
                    _syncState.value = SyncState.Error("Pull failed: ${pullResult.exception.message}")
                    lastSyncTimestamp = System.currentTimeMillis()
                    return@withContext false
                }
            }

            // Phase 1.5: Reindex Room from vault FS — keeps the index honest after a pull
            // that may have added/removed/modified files outside the app's write path.
            Log.i(TAG, "Phase 1.5: Reindexing Room (post-pull)...")
            runCatching { vaultDiscoveryRepository.reindexAll(0L) }
                .onFailure { Log.w(TAG, "Post-pull reindex failed (non-fatal)", it) }

            // Phase 2: Health Data Sync (non-fatal)
            Log.i(TAG, "Phase 2: Health data sync...")
            try {
                val healthResult = syncHealthDataUseCase.invoke()
                if (healthResult.isSuccess) {
                    Log.i(TAG, "Phase 2: Health sync OK")
                } else {
                    Log.w(TAG, "Phase 2: Health sync failed (non-fatal): ${healthResult.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Phase 2: Health sync exception (non-fatal)", e)
            }

            // Phase 2.6: Habit automation — read freshly-written health metrics
            // from the vault and propagate into habit_logs (+ JSON log files)
            // BEFORE the push, so the same commit ships both metrics and the
            // derived habit progress. Non-fatal.
            Log.i(TAG, "Phase 2.6: Habit automation (health → habits)...")
            runCatching { automateHabitsUseCase(java.time.LocalDate.now()) }
                .onFailure { Log.w(TAG, "Phase 2.6: Habit automation failed (non-fatal)", it) }

            // Phase 2.5: Google Ecosystem Sync (non-fatal)
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
            } catch (e: Exception) {
                Log.w(TAG, "Phase 2.5: Google sync threw (non-fatal)", e)
            }

            // Phase 3: Git Add + Commit + Push
            Log.i(TAG, "Phase 3: Add/Commit/Push...")
            try {
                jGitProvider.addAll()
                jGitProvider.commit("Auto-sync: lifecycle foreground cycle")
                val pushResult = jGitProvider.push()
                if (pushResult.isSuccess) {
                    Log.i(TAG, "Phase 3: Push OK")
                } else {
                    Log.w(TAG, "Phase 3: Push failed: ${pushResult.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Phase 3: Add/Commit/Push failed", e)
                _syncState.value = SyncState.Error("Push failed: ${e.message}")
                lastSyncTimestamp = System.currentTimeMillis()
                return@withContext false
            }

            // Phase 3.5: Reindex Room from vault FS so post-write state is reflected in the index.
            Log.i(TAG, "Phase 3.5: Reindexing Room (post-push)...")
            runCatching { vaultDiscoveryRepository.reindexAll(0L) }
                .onFailure { Log.w(TAG, "Post-push reindex failed (non-fatal)", it) }

            // Phase 4: Reconcile Mood / Habit / Chore JSON files (newly pulled
            // from git) back into Room. Cheap local I/O — done inline rather
            // than via WorkManager so we can guarantee the UI sees freshly
            // synced state before this method returns. Embedding indexing is
            // intentionally NOT triggered here: it's now exclusively under
            // user control via Settings → "Index Knowledge Vault".
            Log.i(TAG, "Phase 4: Inline Mood/Habit/Chore JSON → Room reconcile...")
            runCatching { moodRepository.syncWithFileSystem() }
                .onFailure { Log.w(TAG, "Phase 4: Mood reconcile failed (non-fatal)", it) }
            runCatching { habitRepository.syncWithFileSystem() }
                .onFailure { Log.w(TAG, "Phase 4: Habit reconcile failed (non-fatal)", it) }
            runCatching { choreRepository.syncWithFileSystem() }
                .onFailure { Log.w(TAG, "Phase 4: Chore reconcile failed (non-fatal)", it) }

            Log.i(TAG, "=== Full Sync Cycle Complete ===")
            _syncState.value = SyncState.Success
            lastSyncTimestamp = System.currentTimeMillis()
            return@withContext true

        } finally {
            syncMutex.unlock()
        }
    }
}
