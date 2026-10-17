package cloud.wafflecommons.pixelbrainreader.data.remote

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When each vault file was last changed IN GIT (epoch millis, by author time).
 *
 * A file's on-disk mtime is useless as "last edited": every clone, pull or checkout rewrites it,
 * so after a re-clone every note looks brand new. The history is walked once and the result is
 * cached outside the vault (with the HEAD it was computed at); later calls only walk the commits
 * added since. Read-only on the repository, so it never takes the git lock.
 */
@Singleton
class GitFileAges @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    @Serializable
    private data class Snapshot(val head: String, val lastChanged: Map<String, Long>)

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    @Volatile
    private var memory: Snapshot? = null

    private val vaultDir: File get() = File(context.filesDir, "vault")
    private val cacheFile: File get() = File(context.filesDir, CACHE_FILE)

    /** Vault-relative path → last commit time touching it. Empty if the vault isn't a git repo. */
    suspend fun lastChanged(): Map<String, Long> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                compute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "File ages unavailable: ${e.message}")
                memory?.lastChanged ?: emptyMap()
            }
        }
    }

    private fun compute(): Map<String, Long> {
        if (!File(vaultDir, ".git").isDirectory) return emptyMap()
        Git.open(vaultDir).use { git ->
            val repo = git.repository
            val head = repo.resolve("HEAD") ?: return emptyMap()
            val cached = memory ?: readCache()
            if (cached?.head == head.name) {
                memory = cached
                return cached.lastChanged
            }

            val ages = HashMap(cached?.lastChanged.orEmpty())
            val seen = HashSet<String>() // newest-first: the first commit seen for a path wins
            RevWalk(repo).use { walk ->
                walk.sort(RevSort.COMMIT_TIME_DESC)
                walk.markStart(walk.parseCommit(head))
                cached?.head?.let { previous ->
                    // Only the commits added since the cached snapshot (if that commit still exists).
                    runCatching { walk.markUninteresting(walk.parseCommit(ObjectId.fromString(previous))) }
                }
                DiffFormatter(DisabledOutputStream.INSTANCE).use { diff ->
                    diff.setRepository(repo)
                    for (commit in walk) {
                        val parentTree = if (commit.parentCount > 0) walk.parseCommit(commit.getParent(0)).tree else null
                        // Author time: a rebase (every pull here) rewrites the COMMIT time of local commits.
                        val time = commit.authorIdent.whenAsInstant.toEpochMilli()
                        for (entry in diff.scan(parentTree, commit.tree)) {
                            val path = if (entry.changeType == DiffEntry.ChangeType.DELETE) entry.oldPath else entry.newPath
                            if (seen.add(path)) ages[path] = time
                        }
                    }
                }
            }
            val snapshot = Snapshot(head.name, ages)
            memory = snapshot
            writeCache(snapshot)
            return ages
        }
    }

    private fun readCache(): Snapshot? = runCatching {
        if (cacheFile.exists()) json.decodeFromString<Snapshot>(cacheFile.readText()) else null
    }.getOrNull()

    private fun writeCache(snapshot: Snapshot) {
        runCatching {
            val tmp = File(cacheFile.parentFile, "$CACHE_FILE.tmp")
            tmp.writeText(json.encodeToString(Snapshot.serializer(), snapshot))
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.delete()
                tmp.renameTo(cacheFile)
            }
        }.onFailure { Log.w(TAG, "Couldn't cache file ages: ${it.message}") }
    }

    private companion object {
        const val TAG = "GitFileAges"
        const val CACHE_FILE = "git_file_ages.json"
    }
}
