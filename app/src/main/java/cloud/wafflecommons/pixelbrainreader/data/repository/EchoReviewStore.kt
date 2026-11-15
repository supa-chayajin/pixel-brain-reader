package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which Memory Echoes were marked as reviewed, and when. Kept in the VAULT (it syncs and survives
 * a reinstall), not in DataStore. Every change starts from the file on disk, and a file that no
 * longer parses is moved aside before it is rewritten — never overwritten from scratch.
 */
@Singleton
class EchoReviewStore @Inject constructor(
    private val noteRepository: NoteRepository,
    private val fileRepository: FileRepository,
    private val pushScheduler: VaultPushScheduler
) {
    @Serializable
    internal data class Reviews(
        val reviewed: Map<String, Long> = emptyMap(),
        /** Spaced echoes (K6, 12.20): each note's schedule. */
        val spaced: Map<String, SpacedReview.Card> = emptyMap(),
        /** The folders whose notes come back on that schedule (the owner's D11). */
        val folders: List<String> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex = Mutex()

    /** Note path → when it was last marked as reviewed (epoch millis). */
    suspend fun reviewedAt(): Map<String, Long> = withContext(Dispatchers.IO) {
        mutex.withLock { read()?.reviewed.orEmpty() }
    }

    suspend fun markReviewed(path: String, now: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read() ?: Reviews()
            // A review only matters for the next few weeks: older entries are dropped.
            val kept = current.reviewed.filterValues { now - it < RETENTION_MS } + (path to now)
            write(current.copy(reviewed = kept))
        }
    }

    /** Spaced echoes (K6): note path → its schedule. */
    suspend fun spacedCards(): Map<String, SpacedReview.Card> = withContext(Dispatchers.IO) {
        mutex.withLock { read()?.spaced.orEmpty() }
    }

    /** Your answer on a spaced echo moves its next return; the file on disk first, as always. */
    suspend fun answer(path: String, answer: SpacedReview.Answer, today: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read() ?: Reviews()
            val next = SpacedReview.next(current.spaced[path], answer, today)
            write(current.copy(spaced = current.spaced + (path to next)))
        }
    }

    /** The folders picked for spaced echoes. */
    suspend fun folders(): Set<String> = withContext(Dispatchers.IO) {
        mutex.withLock { read()?.folders.orEmpty().toSet() }
    }

    suspend fun setFolders(folders: Set<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read() ?: Reviews()
            write(current.copy(folders = folders.sorted()))
        }
    }

    /** Every field is kept: a change to one never drops the others. */
    private suspend fun write(reviews: Reviews) {
        noteRepository.saveNote(PATH, json.encodeToString(Reviews.serializer(), reviews))
        pushScheduler.requestPush()
    }

    /** Null when the file is absent or unreadable (moved aside in that case). */
    private suspend fun read(): Reviews? {
        val text = noteRepository.getNoteContent(PATH) ?: return null
        return try {
            json.decodeFromString(Reviews.serializer(), text)
        } catch (e: IllegalArgumentException) { // includes SerializationException
            val backup = fileRepository.moveAsideCorrupt(PATH)
            Log.w(TAG, "Unreadable echo reviews moved aside to ${backup?.name}")
            null
        }
    }

    companion object {
        const val PATH = "10_Journal/system/memory_echoes.json"
        private const val TAG = "EchoReviewStore"
        private const val RETENTION_MS = 60L * 24 * 60 * 60 * 1000
    }
}
