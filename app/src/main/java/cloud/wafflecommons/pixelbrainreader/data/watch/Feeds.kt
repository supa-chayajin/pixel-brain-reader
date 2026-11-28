package cloud.wafflecommons.pixelbrainreader.data.watch

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One feed. It shows in the modes of [modes] (13.4, F4); a feed written before 13.4 has none and
 * follows [work], the 12.12 rule: a Work feed shows in Work mode only, any other in every mode
 * (owner, 2026-11-11).
 */
@Serializable
data class Feed(
    val url: String,
    val name: String = "",
    /** Written true only for a Work-only feed, so an older app keeps its rule (13.4, F4). */
    val work: Boolean = false,
    /** 13.4 (F4): the modes it shows in, [FeedModes] keys; null = the 12.12 rule, from [work]. */
    val modes: List<String>? = null
) {
    /** What the screens show: the name, else the address's host. */
    val label: String
        get() = name.trim().ifEmpty { runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: url }

    /** The modes it shows in, never none (F4). */
    val shownIn: Set<ContextMode> get() = FeedModes.shownIn(this)

    fun showsIn(mode: ContextMode): Boolean = mode in shownIn

    /** Shows in Work alone: screen-share safe hides it, as the Work switch did. */
    val workOnly: Boolean get() = shownIn == setOf(ContextMode.WORK)
}

/** The mode keys `feeds.json` stores (13.4, F4). Persisted: never rename one. Pure. */
object FeedModes {

    const val WORK = "work"
    const val PERSONAL = "personal"
    const val OFF = "off"

    /** Every key, in the order a feed's `modes` is written. */
    val ALL_KEYS: List<String> = listOf(WORK, PERSONAL, OFF)

    fun keyOf(mode: ContextMode): String = when (mode) {
        ContextMode.WORK -> WORK
        ContextMode.PERSONAL -> PERSONAL
        ContextMode.OFF -> OFF
    }

    /** The mode [key] names, case and spaces aside; null for a key this version doesn't know. */
    fun modeOf(key: String): ContextMode? = when (key.trim().lowercase()) {
        WORK -> ContextMode.WORK
        PERSONAL -> ContextMode.PERSONAL
        OFF -> ContextMode.OFF
        else -> null
    }

    /**
     * Where [feed] shows: the modes its `modes` names, in the modes' order. Without `modes`, or when
     * it names no mode this version knows, its `work` flag decides as in 12.12: true, Work only;
     * false, every mode.
     */
    fun shownIn(feed: Feed): Set<ContextMode> {
        val named = feed.modes.orEmpty().mapNotNull(::modeOf).toSet()
        if (named.isNotEmpty()) return ContextMode.entries.filterTo(LinkedHashSet()) { it in named }
        return if (feed.work) setOf(ContextMode.WORK) else ContextMode.entries.toSet()
    }

    /**
     * [feed] in [modes] (never none: an empty set changes nothing): `modes` written in [ALL_KEYS]'
     * order, the keys this version doesn't know kept after them, and `work` true only for Work
     * alone, so an older app keeps the Work rule. A feed that already shows in exactly [modes]
     * keeps its shape (a 12.x feed stays one).
     */
    fun withModes(feed: Feed, modes: Set<ContextMode>): Feed {
        if (modes.isEmpty() || shownIn(feed) == modes) return feed
        val unknown = feed.modes.orEmpty().filter { modeOf(it) == null }.distinct()
        return feed.copy(
            work = modes == setOf(ContextMode.WORK),
            modes = ContextMode.entries.filter { it in modes }.map(::keyOf) + unknown
        )
    }
}

/**
 * The feeds, in the owner's order. Kept in the vault ([FeedConfigRepository.PATH]) and not in the
 * code: this repository is public, the feed list is personal (gate 7).
 */
@Serializable
data class FeedConfig(val feeds: List<Feed> = emptyList()) {
    /** Adds [feed] last, unless a feed already has its address. */
    fun add(feed: Feed): FeedConfig =
        if (feeds.any { it.url.equals(feed.url, ignoreCase = true) }) this else copy(feeds = feeds + feed)

    fun remove(url: String): FeedConfig = copy(feeds = feeds.filterNot { it.url.equals(url, ignoreCase = true) })

    /** Kept for the 12.x feeds page until F-d: true = Work only, false = every mode (removed by the integration step). */
    fun setWork(url: String, work: Boolean): FeedConfig =
        withModes(url, if (work) setOf(ContextMode.WORK) else ContextMode.entries.toSet())

    /** 13.4 (F4): the feed at [url] (case aside) in [modes]; an empty set changes nothing (a feed shows in at least one mode). */
    fun withModes(url: String, modes: Set<ContextMode>): FeedConfig =
        if (modes.isEmpty()) {
            this
        } else {
            copy(feeds = feeds.map { if (it.url.equals(url, ignoreCase = true)) FeedModes.withModes(it, modes) else it })
        }

    /**
     * 13.4 (F-d): the feeds in [urls]' order (addresses compared ignoring case); a feed [urls]
     * doesn't name follows, in its order. Every feed stays, whatever [urls] holds.
     */
    fun reordered(urls: List<String>): FeedConfig {
        val taken = BooleanArray(feeds.size)
        val ordered = ArrayList<Feed>(feeds.size)
        urls.forEach { url ->
            val index = feeds.indices.firstOrNull { !taken[it] && feeds[it].url.equals(url, ignoreCase = true) }
            if (index != null) {
                taken[index] = true
                ordered += feeds[index]
            }
        }
        feeds.indices.filterNot { taken[it] }.forEach { ordered += feeds[it] }
        return copy(feeds = ordered)
    }

    /** The feeds [visibility] shows: those of its mode; while screen-share safe, never a Work-only one. */
    fun visible(visibility: Visibility): List<Feed> =
        feeds.filter { it.showsIn(visibility.mode) && !(visibility.screenShareSafe && it.workOnly) }
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

    private val _saved = MutableStateFlow(0)

    /**
     * Moves after each write that went through (13.4), so the Feeds tab and Daily read the list
     * again after the feeds page saves; a refused or failed write, or a change that changes nothing,
     * leaves it.
     */
    val saved: StateFlow<Int> = _saved.asStateFlow()

    /** The saved feeds; none yet, or an unreadable file, is the empty list. */
    suspend fun read(): FeedConfig = withContext(Dispatchers.IO) { load().config ?: FeedConfig() }

    /** Applies [change] to the feeds on disk; the feeds now saved, or null when nothing could be saved. */
    suspend fun update(change: (FeedConfig) -> FeedConfig): FeedConfig? {
        if (VaultWriteGate.refuses()) return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val onDisk = load()
                val current = onDisk.config ?: FeedConfig()
                val next = change(current)
                if (next == current) return@withLock current
                if (onDisk.unreadable && fileRepository.moveAsideCorrupt(PATH) == null) {
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
                _saved.update { it + 1 }
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
            // The type only: a parse error's message quotes the file, feed addresses included.
            Log.w(TAG, "$PATH doesn't parse: ${e.javaClass.simpleName}")
            Saved(null, unreadable = true)
        }
    }

    companion object {
        private const val TAG = "FeedConfig"
        const val PATH = "10_Journal/data/config/feeds.json"
    }
}
