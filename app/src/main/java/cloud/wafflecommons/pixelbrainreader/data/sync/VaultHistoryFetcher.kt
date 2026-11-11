package cloud.wafflecommons.pixelbrainreader.data.sync

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import cloud.wafflecommons.pixelbrainreader.data.remote.isTransientNetworkError
import cloud.wafflecommons.pixelbrainreader.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings the vault's history after a shallow first clone, in steps, in the background.
 *
 * The first clone takes only the newest commit, so a login gets through any connection: a full
 * clone was one long transfer, and a dropped connection started the login over (2026-11-11). The
 * history (what the activity view, a note's history line, Time Machine and memory echoes read)
 * arrives here, [JGitProvider.HISTORY_STEP] commits per transfer: a dropped connection loses one
 * step, and the next sync picks up where it stopped. It starts only when a sync the owner asked for ends (the login's is one): sync stays
 * manual (settled decision #6), and nothing runs once the history is complete.
 *
 * Each step is one fetch under JGit's own lock, never the [GitSyncCoordinator]: that lock is for
 * multi-step sequences, and a step only adds objects (and moves `origin/<branch>` like any fetch).
 */
@Singleton
class VaultHistoryFetcher @Inject constructor(
    private val jGitProvider: JGitProvider,
    @param:ApplicationScope private val appScope: CoroutineScope
) {
    private val running = AtomicBoolean(false)

    /** Fetches the rest of the history unless it is complete or already on its way. */
    fun continueInBackground() {
        if (!running.compareAndSet(false, true)) return
        appScope.launch(Dispatchers.IO) {
            try {
                fetchRemaining()
            } finally {
                running.set(false)
            }
        }
    }

    /** Step after step until complete; a dropped step is retried [MAX_FAILURES] times, then left for the next sync. */
    internal suspend fun fetchRemaining(pauseMs: (Int) -> Long = { failures -> RETRY_BACKOFF_MS * failures * failures }) {
        var failures = 0
        while (true) {
            val result = jGitProvider.deepenHistory()
            val progress = result.getOrNull()
            if (progress != null) {
                failures = 0
                if (progress.complete) {
                    Log.i(TAG, "Vault history complete: ${progress.commits} commits")
                    return
                }
                Log.i(TAG, "Vault history: ${progress.commits} commits so far")
                continue
            }
            val error = result.exceptionOrNull()
            if (error is CancellationException) throw error
            failures++
            if (error == null || failures >= MAX_FAILURES || !isTransientNetworkError(error)) {
                Log.w(TAG, "Vault history paused after $failures failure(s): ${error?.message}; the next sync continues it")
                return
            }
            delay(pauseMs(failures))
        }
    }

    private companion object {
        const val TAG = "VaultHistory"
        const val MAX_FAILURES = 4
        const val RETRY_BACKOFF_MS = 5_000L
    }
}
