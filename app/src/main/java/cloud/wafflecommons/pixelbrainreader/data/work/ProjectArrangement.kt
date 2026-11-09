package cloud.wafflecommons.pixelbrainreader.data.work

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetUpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How the Projects page lists the projects (12.7.0): the [pinned] roots first, in this order, then
 * the others by latest activity; the [hidden] roots out of the list (and of its figures), in a
 * fold at the bottom. Kept in the vault ([ProjectArrangementRepository.PATH]), so every device
 * shows the same list. A root is pinned or hidden, never both: in a hand-edited file, hidden wins.
 * Roots that don't show (a work project outside Work mode, a folder gone) are kept, never pruned.
 */
@Serializable
data class ProjectArrangement(
    val pinned: List<String> = emptyList(),
    val hidden: List<String> = emptyList()
) {
    fun isPinned(root: String): Boolean = root in pinned && root !in hidden
    fun isHidden(root: String): Boolean = root in hidden

    /** Pins [root] last among the pinned (and shows it if it was hidden). */
    fun pin(root: String): ProjectArrangement =
        copy(pinned = if (root in pinned) pinned else pinned + root, hidden = hidden.filterNot { it == root })

    fun unpin(root: String): ProjectArrangement = copy(pinned = pinned.filterNot { it == root })

    /** Hides [root] (and unpins it). */
    fun hide(root: String): ProjectArrangement =
        copy(hidden = if (root in hidden) hidden else hidden + root, pinned = pinned.filterNot { it == root })

    fun show(root: String): ProjectArrangement = copy(hidden = hidden.filterNot { it == root })

    /** Swaps two pinned roots (a move up or down among the pinned the page shows). */
    fun swapPinned(a: String, b: String): ProjectArrangement {
        val i = pinned.indexOf(a)
        val j = pinned.indexOf(b)
        if (i < 0 || j < 0 || i == j) return this
        return copy(pinned = pinned.toMutableList().also { it[i] = b; it[j] = a })
    }

    /** [projects] (the most recently active first) split for the page. */
    fun arrange(projects: List<ProjectSummary>): ArrangedProjects {
        val byRoot = projects.associateBy { it.root }
        val pinnedShown = pinned.distinct().filter { it !in hidden }.mapNotNull { byRoot[it] }
        val pinnedRoots = pinnedShown.map { it.root }.toSet()
        return ArrangedProjects(
            pinned = pinnedShown,
            others = projects.filter { it.root !in pinnedRoots && it.root !in hidden },
            hidden = projects.filter { it.root in hidden }
        )
    }
}

/** The page's three parts. [shown] is what the list (and its figures, and the widget) shows. */
data class ArrangedProjects(
    val pinned: List<ProjectSummary>,
    val others: List<ProjectSummary>,
    val hidden: List<ProjectSummary>
) {
    val shown: List<ProjectSummary> get() = pinned + others
}

/**
 * Reads and writes the [ProjectArrangement] file. Every change starts from the file on disk
 * (settled decision 8), a file that no longer parses is moved aside, never overwritten, and a
 * read-only device changes nothing.
 */
@Singleton
class ProjectArrangementRepository @Inject constructor(
    private val fileRepository: FileRepository,
    private val pushScheduler: VaultPushScheduler,
    private val widgetUpdateManager: WidgetUpdateManager
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }
    private val mutex = Mutex()

    /** The saved arrangement; none yet, or an unreadable file, is the empty one (nothing pinned, nothing hidden). */
    suspend fun read(): ProjectArrangement = withContext(Dispatchers.IO) { load().arrangement ?: ProjectArrangement() }

    /**
     * Applies [change] to the arrangement on disk and requests a push. Returns the arrangement now
     * saved (the same one when [change] changes nothing), or null when nothing could be saved: a
     * read-only device, or a write that failed.
     */
    suspend fun update(change: (ProjectArrangement) -> ProjectArrangement): ProjectArrangement? {
        if (VaultWriteGate.refuses()) return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val saved = load()
                val current = saved.arrangement ?: ProjectArrangement()
                val next = change(current)
                if (next == current) return@withLock current
                if (saved.unreadable && fileRepository.moveAsideCorrupt(PATH) == null) {
                    Log.e(TAG, "Unreadable $PATH could not be kept aside; not overwriting it")
                    return@withLock null
                }
                try {
                    fileRepository.saveFileLocally(PATH, json.encodeToString(next))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save the projects arrangement", e)
                    return@withLock null
                }
                pushScheduler.requestPush()
                // The Projects widget lists the same projects.
                widgetUpdateManager.triggerUpdate()
                next
            }
        }
    }

    private class Saved(val arrangement: ProjectArrangement?, val unreadable: Boolean)

    private suspend fun load(): Saved {
        val raw = try {
            fileRepository.readFile(PATH)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (raw.isNullOrBlank()) return Saved(null, unreadable = false)
        return try {
            Saved(json.decodeFromString<ProjectArrangement>(raw), unreadable = false)
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable $PATH; showing the projects unarranged", e)
            Saved(null, unreadable = true)
        }
    }

    companion object {
        private const val TAG = "ProjectArrangement"
        const val PATH = "10_Journal/data/config/projects.json"
    }
}
