package cloud.wafflecommons.pixelbrainreader.data.sync

import android.content.Context
import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.remote.ChangedPath
import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import cloud.wafflecommons.pixelbrainreader.data.work.DeskHandoffFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What the last pull brought from the remote (E5): notes added, changed and removed. */
@Serializable
data class SyncDigest(
    val atMillis: Long,
    val added: List<String>,
    val modified: List<String>,
    val deleted: List<String>,
    val dismissed: Boolean = false,
    /** What concerns you, per project (C4): your TODO lines, Claude's reports, hand-offs picked up. */
    val forYou: List<ProjectNews> = emptyList(),
    /** HEAD before and after that pull (K3, 12.20): a note's "Since your last sync". Null in older digests. */
    val beforeHead: String? = null,
    val afterHead: String? = null
) {
    val total: Int get() = added.size + modified.size + deleted.size

    /** The notes the pull brought or changed: the file list marks them (K3, the owner's D4). */
    val brought: Set<String> get() = (added + modified).toSet()

    /** What the pull did to [path], for its history; null when it didn't touch it or the heads aren't known. */
    fun changeFor(path: String): SyncChange? {
        if (path !in added && path !in modified) return null
        val before = beforeHead ?: return null
        val after = afterHead ?: return null
        return SyncChange(beforeHead = before, afterHead = after, atMillis = atMillis, isNew = path in added)
    }

    /** The top folders the changes landed in, busiest first. */
    fun folders(): List<Pair<String, Int>> = (added + modified + deleted)
        .groupingBy { it.substringBefore('/', "Vault") }
        .eachCount()
        .entries.sortedByDescending { it.value }
        .map { it.key to it.value }

    companion object {
        /** Notes only: the app's own JSON (health, habits, moods…) and the git plumbing aren't news. */
        fun of(changes: List<ChangedPath>, atMillis: Long): SyncDigest {
            val notes = changes.filter { it.path.endsWith(".md") && !it.path.startsWith(".") }
            return SyncDigest(
                atMillis = atMillis,
                added = notes.filter { it.change == "add" || it.change == "rename" }.map { it.path }.sorted(),
                modified = notes.filter { it.change == "modify" }.map { it.path }.sorted(),
                deleted = notes.filter { it.change == "delete" }.map { it.path }.sorted()
            )
        }
    }
}

/** What one pull did to a note: its text at [beforeHead] (none when [isNew]) became its text at [afterHead]. */
data class SyncChange(val beforeHead: String, val afterHead: String, val atMillis: Long, val isNew: Boolean)

/** Keeps the last pull's digest (in `filesDir`, it is about this device's syncs) until dismissed. */
@Singleton
class SyncDigestRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val jGitProvider: JGitProvider
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file: File get() = File(context.filesDir, FILE)

    private val _digest = MutableStateFlow<SyncDigest?>(null)
    val digest: StateFlow<SyncDigest?> = _digest.asStateFlow()

    @Volatile
    private var loaded = false

    suspend fun load() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        _digest.value = runCatching { if (file.exists()) json.decodeFromString(SyncDigest.serializer(), file.readText()) else null }.getOrNull()
        loaded = true
    }

    /**
     * Records what a pull changed between [beforeHead] and the HEAD after it. Never fails a sync:
     * a digest is a convenience. An empty pull keeps the previous digest.
     */
    suspend fun recordPull(beforeHead: String?) {
        if (beforeHead == null) return
        try {
            val after = jGitProvider.headId() ?: return
            if (after == beforeHead) return
            val changes = jGitProvider.changesBetween(beforeHead, after)
            val digest = SyncDigest.of(changes, System.currentTimeMillis())
            if (digest.total == 0) return
            save(digest.copy(forYou = forYou(changes, beforeHead, after), beforeHead = beforeHead, afterHead = after))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync digest skipped: ${e.message}")
        }
    }

    /** Reads the projects' TODO and hand-off files at both ends of the pull; a failure only loses the "for you" part. */
    private suspend fun forYou(changes: List<ChangedPath>, beforeHead: String, afterHead: String): List<ProjectNews> = try {
        // Sub-projects are the folders with their own Handoff.md, as the pull left them on disk.
        val vault = File(context.filesDir, "vault")
        val subRoots = withContext(Dispatchers.IO) {
            changes.mapNotNull { WhatsNew.subProjectCandidate(it.path) }.distinct()
                .filter { File(vault, "$it/${DeskHandoffFormat.FILE_NAME}").isFile }.toSet()
        }
        val wanted = changes.filter { it.change != "delete" && WhatsNew.needsContent(it.path, subRoots) }.map { it.path }
        WhatsNew.of(
            changes,
            before = wanted.associateWith { jGitProvider.getFileContentAtCommit(it, beforeHead) },
            after = wanted.associateWith { jGitProvider.getFileContentAtCommit(it, afterHead) },
            subRoots = subRoots
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "What's new for you skipped: ${e.message}")
        emptyList()
    }

    suspend fun dismiss() {
        _digest.value?.let { save(it.copy(dismissed = true)) }
    }

    private suspend fun save(digest: SyncDigest) = withContext(Dispatchers.IO) {
        _digest.value = digest
        loaded = true
        runCatching {
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(json.encodeToString(SyncDigest.serializer(), digest))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private companion object {
        const val TAG = "SyncDigest"
        const val FILE = "sync_digest.json"
    }
}
