package cloud.wafflecommons.pixelbrainreader.data.watch

import cloud.wafflecommons.pixelbrainreader.data.web.PageProblem
import cloud.wafflecommons.pixelbrainreader.data.web.PageUnavailableException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * A feed's last read and last failure (12.18, D5a), kept on the device for the feeds page: a feed
 * that fails used to vanish from Daily without a word.
 */
@Serializable
data class FeedStatus(val readAt: Long? = null, val failedAt: Long? = null, val problem: String? = null) {
    /** The last try failed: a failure newer than the last read. */
    val failing: Boolean get() = failedAt != null && (readAt == null || failedAt > readAt)
}

/** The statuses' rules and words, pure. */
object FeedStatuses {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), FeedStatus.serializer())

    /** A read went through: the failure is forgotten. */
    fun read(statuses: Map<String, FeedStatus>, url: String, now: Long) = statuses + (url to FeedStatus(readAt = now))

    /** A try failed: the last read stays, so the page can say when the feed last worked. */
    fun failed(statuses: Map<String, FeedStatus>, url: String, now: Long, problem: String) =
        statuses + (url to (statuses[url] ?: FeedStatus()).copy(failedAt = now, problem = problem))

    /** The feeds whose last try failed less than [TechWatch.RETRY_AFTER_MS] ago. */
    fun failedRecently(statuses: Map<String, FeedStatus>, now: Long): Set<String> =
        statuses.filterValues { it.failing && now - (it.failedAt ?: 0L) < TechWatch.RETRY_AFTER_MS }.keys

    /**
     * Why a feed couldn't be read, in a few words that follow "Failed 5 min ago: " on the feeds page;
     * [error] null: it answered, but with no headline.
     */
    fun problemOf(error: Throwable?): String = when (error) {
        null -> "no headlines at this address (a web page, not a feed?)"
        is PageUnavailableException -> {
            val status = error.status?.let { " (HTTP $it)" }.orEmpty()
            when (error.problem) {
                PageProblem.NOT_PUBLIC -> "blocked, not a public address"
                PageProblem.NOT_HTML -> "not a feed (${error.detail ?: "another kind of file"})"
                PageProblem.GONE -> "gone$status"
                PageProblem.PAYWALL, PageProblem.REFUSED -> "refused by the site$status"
                PageProblem.BLOCKED -> "blocked by the site's bot check$status"
                PageProblem.SERVER -> "the site failed$status"
                PageProblem.NETWORK -> "no answer (the network, or the site is down)"
                PageProblem.NO_TEXT -> "an empty answer"
            }
        }
        else -> "not readable as RSS or Atom (a web page?)"
    }

    fun encode(statuses: Map<String, FeedStatus>): String = json.encodeToString(serializer, statuses)

    /** Unreadable or absent: no status yet, never a crash. */
    fun decode(raw: String?): Map<String, FeedStatus> =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
}
