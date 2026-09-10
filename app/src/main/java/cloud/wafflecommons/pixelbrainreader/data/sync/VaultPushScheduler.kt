package cloud.wafflecommons.pixelbrainreader.data.sync

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coalesces "this change should reach the remote" requests into ONE background commit + push,
 * [DEBOUNCE_MS] after the last request.
 *
 * WHY: every small mutation used to run its own network git round-trip inline — each habit toggle,
 * mood entry and automatic habit — which hammered JGit and made UI feedback wait on the network
 * (the mood widget only refreshed after the push returned). Changes are written to disk
 * immediately; this only batches their propagation.
 *
 * Push-only, exactly like the per-mutation pushes it replaces: sync stays manual (pull-to-refresh),
 * so this never pulls remote changes into the working tree. A push rejected because the remote moved
 * on is left for the next manual sync, which rebases first. Runs under [GitSyncCoordinator] so it
 * never interleaves with a sync cycle. If the process dies before the debounce fires nothing is
 * lost: the files are on disk and the next sync commits them.
 */
@OptIn(FlowPreview::class)
@Singleton
class VaultPushScheduler @Inject constructor(
    private val fileRepository: FileRepository,
    private val jGitProvider: JGitProvider,
    private val secretManager: SecretManager,
    private val gitSyncCoordinator: GitSyncCoordinator,
    @ApplicationScope appScope: CoroutineScope
) {
    /**
     * Conflated: any number of requests collapse into one pending push. Unlike a SharedFlow, a
     * request made before the collector below has subscribed is kept rather than dropped.
     */
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        appScope.launch {
            requests.receiveAsFlow().debounce(DEBOUNCE_MS).collect { pushNow() }
        }
    }

    /** Non-blocking: marks the vault dirty; the actual push runs later, batched with other requests. */
    fun requestPush() {
        requests.trySend(Unit)
    }

    private suspend fun pushNow() = withContext(Dispatchers.IO) {
        try {
            // Never clone/init from a background trigger — only push an already-set-up vault.
            if (!jGitProvider.isReady()) return@withContext
            val (owner, repo) = secretManager.getRepoInfo()
            if (owner.isNullOrBlank() || repo.isNullOrBlank()) return@withContext
            val result = gitSyncCoordinator.mutex.withLock {
                fileRepository.pushDirtyFiles(owner, repo, COMMIT_MESSAGE)
            }
            if (result.isFailure) {
                Log.w(TAG, "Background push failed; the next sync will retry", result.exceptionOrNull())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Background push crashed", e)
        }
    }

    private companion object {
        const val TAG = "VaultPushScheduler"
        const val DEBOUNCE_MS = 15_000L
        const val COMMIT_MESSAGE = "Auto-save: local changes"
    }
}
