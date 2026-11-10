package cloud.wafflecommons.pixelbrainreader.data.device

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase
import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import cloud.wafflecommons.pixelbrainreader.data.remote.LocalChanges
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.GitSyncCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A login that changes the device's role ([DeviceRole]) or the repository it pulls from starts from
 * a fresh clone, so no commit of the old copy (a sandbox's, a writing device's) ever sits under the
 * vault a device then reads. What the old copy holds that GitHub doesn't is shown to the user first.
 */
@Singleton
class LocalCopyReset @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val jGitProvider: JGitProvider,
    private val database: AppDatabase,
    private val deviceRole: DeviceRole,
    private val dailyDashboardRepository: DailyDashboardRepository,
    private val gitSyncCoordinator: GitSyncCoordinator
) {

    /** True when a clone exists and this login changes the role or the repository. */
    suspend fun needsFreshClone(readOnly: Boolean, remoteUrl: String): Boolean {
        if (!jGitProvider.isReady()) return false
        return deviceRole.saved() != readOnly || !sameRepository(jGitProvider.originUrl(), remoteUrl)
    }

    /**
     * What the current clone holds that GitHub doesn't: what a fresh clone discards. A writing
     * device keeps today's board in Room until it is burned, so it is burned into the clone first
     * and counts like any other change.
     */
    suspend fun pendingChanges(): LocalChanges = gitSyncCoordinator.mutex.withLock {
        if (!VaultWriteGate.readOnly) {
            try {
                dailyDashboardRepository.burnToDisk(LocalDate.now())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("LocalCopyReset", "Could not burn today's board before the reset", e)
            }
        }
        jGitProvider.localChanges()
    }

    /**
     * Deletes the clone and what this device derived from it (Room, the caches next to it). The
     * chat history, the one thing Room holds that no vault reproduces, is kept. Under the sync lock:
     * no background writer may write into the copy while it goes.
     */
    suspend fun reset() = withContext(Dispatchers.IO) {
        gitSyncCoordinator.mutex.withLock { resetLocked() }
    }

    private suspend fun resetLocked() {
        if (!jGitProvider.deleteLocalClone()) throw IOException("Couldn't remove this device's old copy of the vault.")
        val chat = database.chatDao().getAllMessages()
        database.clearAllTables()
        if (chat.isNotEmpty()) database.chatDao().insertMessages(chat)
        DERIVED_CACHES.forEach { File(context.filesDir, it).delete() }
    }

    companion object {
        /** Files in `filesDir` computed from the clone (see GitFileAges, SyncDigest, WidgetSnapshotManager). */
        private val DERIVED_CACHES = listOf("git_file_ages.json", "sync_digest.json", "widget_snapshot.json")

        /** The same repository, whatever the spelling (case, trailing slash, `.git`). */
        fun sameRepository(a: String?, b: String?): Boolean {
            if (a == null || b == null) return false
            fun norm(url: String) = url.trim().lowercase().removeSuffix("/").removeSuffix(".git").removeSuffix("/")
            return norm(a) == norm(b)
        }
    }
}
