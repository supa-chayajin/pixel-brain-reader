package cloud.wafflecommons.pixelbrainreader.data.watch

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/** One feed of the tech watch. A [work] feed hides outside Work mode (owner, 2026-11-11). */
@Serializable
data class Feed(val url: String, val name: String = "", val work: Boolean = false) {
    /** What the block shows: the name, else the address's host. */
    val label: String
        get() = name.trim().ifEmpty { runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: url }
}

/**
 * The tech watch's feeds, in the owner's order. Kept in the vault ([FeedConfigRepository.PATH]) and
 * not in the code: this repository is public, the feed list is personal (gate 7).
 */
@Serializable
data class FeedConfig(val feeds: List<Feed> = emptyList()) {
    /** Adds [feed] last, unless a feed already has its address. */
    fun add(feed: Feed): FeedConfig =
        if (feeds.any { it.url.equals(feed.url, ignoreCase = true) }) this else copy(feeds = feeds + feed)

    fun remove(url: String): FeedConfig = copy(feeds = feeds.filterNot { it.url.equals(url, ignoreCase = true) })

    fun setWork(url: String, work: Boolean): FeedConfig =
        copy(feeds = feeds.map { if (it.url.equals(url, ignoreCase = true)) it.copy(work = work) else it })

    /** The feeds a mode shows: the Work ones only while work is in the picture. */
    fun visible(workHidden: Boolean): List<Feed> = if (workHidden) feeds.filterNot { it.work } else feeds
}

/**
 * Reads and writes `feeds.json` like the projects arrangement: every change reads the disk first,
 * an unreadable file is moved aside before anything is written, and a read-only device writes
 * nothing.
 */
@Singleton
class FeedConfigRepository @Inject constructor(
    private val fileRepository: FileRepository,
    private val pushScheduler: VaultPushScheduler
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }
    private val mutex = Mutex()

    /** The saved feeds; none yet, or an unreadable file, is the empty list. */
    suspend fun read(): FeedConfig = withContext(Dispatchers.IO) { load().config ?: FeedConfig() }

    /** Applies [change] to the feeds on disk; the feeds now saved, or null when nothing could be saved. */
    suspend fun update(change: (FeedConfig) -> FeedConfig): FeedConfig? {
        if (VaultWriteGate.refuses()) return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val saved = load()
                val current = saved.config ?: FeedConfig()
                val next = change(current)
                if (next == current) return@withLock current
                if (saved.unreadable && fileRepository.moveAsideCorrupt(PATH) == null) {
                    Log.e(TAG, "Unreadable $PATH could not be kept aside; not overwriting it")
                    return@withLock null
                }
                try {
                    fileRepository.saveFileLocally(PATH, json.encodeToString(FeedConfig.serializer(), next))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save the feeds", e)
                    return@withLock null
                }
                pushScheduler.requestPush()
                next
            }
        }
    }

    private class Saved(val config: FeedConfig?, val unreadable: Boolean)

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
            Saved(json.decodeFromString(FeedConfig.serializer(), raw), unreadable = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$PATH doesn't parse: ${e.message}")
            Saved(null, unreadable = true)
        }
    }

    companion object {
        private const val TAG = "FeedConfig"
        const val PATH = "10_Journal/data/config/feeds.json"
    }
}
