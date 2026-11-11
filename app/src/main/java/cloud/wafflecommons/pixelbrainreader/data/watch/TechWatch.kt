package cloud.wafflecommons.pixelbrainreader.data.watch

import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A headline of the tech watch. */
data class Headline(val title: String, val url: String, val publishedAt: Long)

/** One feed's latest headlines, newest first; [fetchedAt] null when nothing was ever read from it. */
data class FeedHeadlines(val feed: Feed, val headlines: List<Headline>, val fetchedAt: Long?)

/** A cached article as the watch reads it (the Room row, without Room). */
data class CachedArticle(val url: String, val title: String, val source: String, val publishedAt: Long, val fetchedAt: Long)

/** What a feed item becomes once read: only http(s) links, a title, a date. */
data class FeedItem(val title: String?, val link: String?, val published: String?)

/** The tech watch's rules (D5, owner's review 2026-11-11), pure. */
object TechWatch {

    /** Headlines a feed shows on Daily. */
    const val PER_FEED = 3

    /** Headlines kept per feed in the cache. */
    const val KEPT_PER_FEED = 20

    /** The cache is fresh for two hours: the watch refreshes when the app comes back, never on a timer. */
    const val FRESH_FOR_MS = 2 * 60 * 60 * 1000L

    /** After a refresh that failed (offline), the next try waits this long. */
    const val RETRY_AFTER_MS = 10 * 60 * 1000L

    /** The feeds in their order, each with its [perFeed] newest headlines; a feed with none is left out. */
    fun group(feeds: List<Feed>, articles: List<CachedArticle>, perFeed: Int = PER_FEED): List<FeedHeadlines> {
        val bySource = articles.groupBy { it.source }
        return feeds.mapNotNull { feed ->
            val mine = bySource[feed.url].orEmpty()
            if (mine.isEmpty()) return@mapNotNull null
            FeedHeadlines(
                feed = feed,
                headlines = mine.sortedByDescending { it.publishedAt }.take(perFeed).map { Headline(it.title, it.url, it.publishedAt) },
                fetchedAt = mine.maxOf { it.fetchedAt }
            )
        }
    }

    /** Whether to read the feeds again: never read, older than [FRESH_FOR_MS], or [force]d; not right after a failed try. */
    fun needsRefresh(lastFetch: Long?, lastFailure: Long?, now: Long, force: Boolean = false): Boolean {
        if (force) return true
        if (lastFailure != null && now - lastFailure < RETRY_AFTER_MS) return false
        return lastFetch == null || now - lastFetch >= FRESH_FOR_MS
    }

    /** A feed's items as cache rows: http(s) links only, each link once, at most [KEPT_PER_FEED]. */
    fun articlesOf(feed: Feed, items: List<FeedItem>, now: Long): List<CachedArticle> =
        items.mapNotNull { item ->
            val link = item.link?.trim().orEmpty()
            if (!link.startsWith("https://", ignoreCase = true) && !link.startsWith("http://", ignoreCase = true)) return@mapNotNull null
            val title = item.title?.trim().orEmpty().ifEmpty { link }
            CachedArticle(link, title, feed.url, parseDate(item.published) ?: now, now)
        }
            .distinctBy { it.url }
            .sortedByDescending { it.publishedAt }
            .take(KEPT_PER_FEED)

    /** RSS (RFC 1123) or Atom (ISO 8601) dates; null when it is neither. */
    fun parseDate(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
    }
}
