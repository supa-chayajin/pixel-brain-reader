package cloud.wafflecommons.pixelbrainreader.data.sync

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import cloud.wafflecommons.pixelbrainreader.data.remote.SyncResult
import cloud.wafflecommons.pixelbrainreader.data.usecase.SyncHealthDataUseCase
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
    private val jGitProvider: JGitProvider,
    private val syncHealthDataUseCase: SyncHealthDataUseCase
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

            Log.i(TAG, "=== Full Sync Cycle Complete ===")
            _syncState.value = SyncState.Success
            lastSyncTimestamp = System.currentTimeMillis()
            return@withContext true

        } finally {
            syncMutex.unlock()
        }
    }
}
