package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** On-disk shape of the mood-tag config file. */
@Serializable
private data class MoodTagsDto(val tags: List<String> = emptyList())

/**
 * Canonical list of activity tags offered when logging a mood.
 *
 * Local-first: the source of truth is a single JSON file in the vault
 * ([TAGS_PATH]), so the list syncs across devices via git just like notes and mood
 * entries. Room is not involved — tags are a small, user-authored config, not derived
 * state. Historical [MoodEntity] rows keep whatever tags they were saved with; editing
 * this list only changes what is *offered* going forward.
 *
 * On the very first run (no file yet) the in-memory list falls back to
 * [DEFAULT_TAGS]; nothing is written until the user makes an edit, so merely opening
 * the mood sheet never creates a commit.
 */
@Singleton
class MoodTagRepository @Inject constructor(
    private val fileRepository: FileRepository,
    private val pushScheduler: VaultPushScheduler
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    /** Serializes disk reads/writes so a rapid add/remove/reorder burst can't interleave. */
    private val ioMutex = Mutex()

    private val _tags = MutableStateFlow(DEFAULT_TAGS)
    /** Reactive canonical tag list. Observed by the check-in sheet and the settings page. */
    val tags: StateFlow<List<String>> = _tags.asStateFlow()

    @Volatile
    private var loaded = false

    /**
     * Loads the tag list from the vault, seeding the in-memory list with [DEFAULT_TAGS]
     * only when there is **no** saved file yet (absent / blank / unparseable). A validly
     * parsed list is respected as-is — including a deliberately emptied one, so clearing
     * every tag survives a restart and syncs. Idempotent; only the first call touches disk
     * and it never writes (seeding is in-memory only; the file is created on the first edit).
     */
    suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                if (!loaded) loadFromDisk()
            }
        }
    }

    /**
     * Re-reads the tag file. Called after every sync, which may have pulled a list edited on another
     * device — the list used to be read once per process, so such edits stayed invisible until restart.
     */
    suspend fun reload() {
        withContext(Dispatchers.IO) {
            ioMutex.withLock { loadFromDisk() }
        }
    }

    /** Caller holds [ioMutex]. */
    private suspend fun loadFromDisk() {
        val parsed: List<String>? = try {
            fileRepository.readFile(TAGS_PATH)
                ?.takeIf { it.isNotBlank() }
                ?.let { json.decodeFromString<MoodTagsDto>(it).tags }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (loaded) {
                // A reload (after a sync) that can't read the file keeps the list already shown, so
                // the next edit can't write the defaults over the user's tags.
                Log.w(TAG, "Failed to re-read mood tags; keeping the current list", e)
                return
            }
            Log.w(TAG, "Failed to read mood tags; falling back to defaults", e)
            null
        }
        // parsed == null ⇒ no saved file; parsed == [] ⇒ user cleared the list (kept).
        _tags.value = parsed?.distinct() ?: DEFAULT_TAGS
        loaded = true
    }

    /** Appends a new tag (trimmed, case-insensitively de-duplicated). No-op if blank/duplicate. */
    suspend fun addTag(raw: String) {
        val tag = raw.trim()
        if (tag.isBlank()) return
        ensureLoaded()
        update { current ->
            if (current.any { it.equals(tag, ignoreCase = true) }) current else current + tag
        }
    }

    /** Removes a tag from the offered list. Historical mood entries are untouched. */
    suspend fun removeTag(tag: String) {
        ensureLoaded()
        update { current ->
            if (tag in current) current.filterNot { it == tag } else current
        }
    }

    /** Moves the tag at [from] to index [to], preserving the rest of the order. */
    suspend fun moveTag(from: Int, to: Int) {
        ensureLoaded()
        update { current ->
            if (from !in current.indices || to !in current.indices || from == to) {
                current
            } else {
                current.toMutableList().apply { add(to, removeAt(from)) }
            }
        }
    }

    /**
     * Atomic read-modify-write: [transform] runs on the current list **inside** the mutex so
     * concurrent edits can't clobber each other (a rapid add/remove/reorder burst is serialized,
     * not raced). A no-op transform (returns an equal list) skips the write entirely. The in-memory
     * flow is updated first so the UI reacts instantly; the git push is batched in the background.
     */
    private suspend fun update(transform: (List<String>) -> List<String>) {
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                val current = _tags.value
                val next = transform(current)
                if (next == current) return@withLock
                _tags.value = next
                try {
                    fileRepository.saveFileLocally(TAGS_PATH, json.encodeToString(MoodTagsDto(next)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write mood tags to vault", e)
                    return@withLock
                }
                pushScheduler.requestPush()
            }
        }
    }

    companion object {
        private const val TAG = "MoodTagRepository"
        const val TAGS_PATH = "10_Journal/data/config/mood_tags.json"

        /** Seed list — mirrors the tags that were previously hardcoded in the check-in sheet. */
        val DEFAULT_TAGS: List<String> = listOf(
            "Coding", "Working", "Gaming", "Chilling",
            "Solo", "Family", "Friends",
            "Home", "Work", "CDS", "Out"
        )
    }
}
