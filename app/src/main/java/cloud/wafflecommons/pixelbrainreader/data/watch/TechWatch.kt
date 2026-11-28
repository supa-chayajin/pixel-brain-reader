package cloud.wafflecommons.pixelbrainreader.data.watch

import cloud.wafflecommons.pixelbrainreader.data.utils.ImportUrlValidator
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * A cached article as the feeds read it (the Room row, without Room). [source] is the address of
 * the feed that first brought it; [fetchedAt] is when it was first seen (13.4: kept on every later
 * read, for the unread marks and the tab's dot).
 */
data class CachedArticle(
    val url: String,
    val title: String,
    val source: String,
    val publishedAt: Long,
    val fetchedAt: Long,
    /** 13.4 (F7): the item's picture when FeedPictures allows it. */
    val thumbnailUrl: String? = null,
    /** False when the feed gave no date this time: the row keeps the date it has. */
    val dated: Boolean = true
)

/** What a feed item becomes once read; [images]: its picture candidates in order (media, enclosure, itunes). */
data class FeedItem(val title: String?, val link: String?, val published: String?, val images: List<String> = emptyList())

/** A headline as the Feeds tab and Daily show it (13.4). [firstSeenAt] = the cache's fetchDate. */
data class FeedEntry(
    val url: String,
    val title: String,
    val feedUrl: String,
    val feedLabel: String,
    val publishedAt: Long,
    val firstSeenAt: Long,
    val thumbnailUrl: String?,
    val read: Boolean
)

/** A feed of the mode with its unread and total counts (a feed with nothing cached: 0 and 0). */
data class FeedChip(val feed: Feed, val unread: Int, val total: Int)

/** The tab's stream (F2): the mode's feeds in the owner's order, and every headline newest first. */
data class FeedStream(val chips: List<FeedChip> = emptyList(), val entries: List<FeedEntry> = emptyList()) {
    val unread: Int get() = chips.sumOf { it.unread }
}

/** Daily's block (F5): the newest unread; [hasFeeds]: the mode has feeds; [anyCached]: one of them has headlines. */
data class DailyFeeds(
    val entries: List<FeedEntry> = emptyList(),
    val hasFeeds: Boolean = false,
    val anyCached: Boolean = false,
    val unread: Int = 0
)

/** A headline of the 12.x Daily block (kept until the Feeds block replaces it). */
data class Headline(val title: String, val url: String, val publishedAt: Long)

/** One feed's latest headlines, newest first; [fetchedAt] null when nothing was ever read from it. */
data class FeedHeadlines(val feed: Feed, val headlines: List<Headline>, val fetchedAt: Long?)

/** The feeds' rules (D5, owner's review 2026-11-11; 13.4, F2–F7), pure. */
object TechWatch {
    /** Headlines a feed shows in the 12.x Daily block (kept until the integration step). */
    const val PER_FEED = 3

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


    /**
     * History (F6): what a feed carried in the last 30 days stays in the cache; past them a headline
     * leaves once its feed no longer carries it, and the view shows the 30 days only.
     */
    const val KEEP_DAYS = 30
    const val KEEP_MS = KEEP_DAYS * 24L * 60 * 60 * 1000

    /** A safety ceiling per feed inside the 30 days: a runaway feed can't fill the cache. */
    const val MAX_PER_FEED = 500

    /** The unread headlines Daily's block shows (F5). */
    const val DAILY_UNREAD = 5

    /**
     * A headline's title is cut past this: a feed may be megabytes, and one row over Room's 2 MB
     * cursor window would blank the whole stream.
     */
    const val MAX_TITLE_CHARS = 500

    /** The cache is fresh for two hours: the feeds refresh when the app comes back, never on a timer. */
    const val FRESH_FOR_MS = 2 * 60 * 60 * 1000L

    /** After a refresh that failed (offline), the next try waits this long. */
    const val RETRY_AFTER_MS = 10 * 60 * 1000L

    /** Newest first: published, then first seen, then the address (so equal dates keep one order). */
    private val NEWEST_FIRST: Comparator<FeedEntry> =
        compareByDescending<FeedEntry> { it.publishedAt }.thenByDescending { it.firstSeenAt }.thenBy { it.url }

    /**
     * The oldest date shown at [now] (F6). A row published before it leaves the cache at its feed's
     * next read that no longer carries it; one the feed still carries stays, out of the view.
     */
    fun cutoff(now: Long): Long = now - KEEP_MS

    /** Whether to read the feeds again: never read, older than [FRESH_FOR_MS], or [force]d; not right after a failed try. */
    fun needsRefresh(lastFetch: Long?, lastFailure: Long?, now: Long, force: Boolean = false): Boolean {
        if (force) return true
        if (lastFailure != null && now - lastFailure < RETRY_AFTER_MS) return false
        return lastFetch == null || now - lastFetch >= FRESH_FOR_MS
    }

    /**
     * The feeds to read now (12.18, D5a): all of them when the cache is due ([needsRefresh]);
     * otherwise only the ones with nothing cached yet, such as a feed a sync or the feeds page just
     * added, which used to wait up to two hours. A feed in [failedRecently] waits [RETRY_AFTER_MS].
     */
    fun feedsToRead(
        all: List<Feed>,
        cached: Set<String>,
        lastFetch: Long?,
        lastFailure: Long?,
        now: Long,
        force: Boolean = false,
        failedRecently: Set<String> = emptySet()
    ): List<Feed> {
        if (needsRefresh(lastFetch, lastFailure, now, force)) return all
        return all.filter { it.url !in cached && it.url !in failedRecently }
    }

    /** True when at least one item has an http(s) link (else the feed answered with no headline). */
    fun hasHeadlines(items: List<FeedItem>): Boolean = items.any { linkOf(it) != null }

    /**
     * A feed's items as cache rows: http(s) links only, each link once, `dated` = the date parsed,
     * `publishedAt` = that date or [seenAt], none older than [cutoff] at [now], the picture through
     * [FeedPictures.thumbnailOf] (its candidates against the item's link), newest first, at most
     * [MAX_PER_FEED]. [seenAt] (13.4): when these rows enter the cache, their first-seen time, taken
     * once the feed was downloaded (a refresh's downloads can take tens of seconds, and a headline
     * stamped before the tab was opened meanwhile would never light its dot).
     */
    fun articlesOf(feed: Feed, items: List<FeedItem>, now: Long, seenAt: Long = now): List<CachedArticle> {
        val oldest = cutoff(now)
        return items.mapNotNull { item ->
            val link = linkOf(item) ?: return@mapNotNull null
            val date = parseDate(item.published)
            val publishedAt = date ?: seenAt
            if (publishedAt < oldest) return@mapNotNull null
            CachedArticle(
                url = link,
                title = item.title?.trim().orEmpty().take(MAX_TITLE_CHARS).ifEmpty { link },
                source = feed.url,
                publishedAt = publishedAt,
                fetchedAt = seenAt,
                thumbnailUrl = FeedPictures.thumbnailOf(item.images, link),
                dated = date != null
            )
        }
            .distinctBy { it.url }
            .sortedByDescending { it.publishedAt }
            .take(MAX_PER_FEED)
    }

    /**
     * [fresh] (one read of one feed) over the cache's [known] rows of the same addresses: a known
     * row keeps its first-seen time and its feed, an undated fresh row keeps the known date, the
     * title is the fresh one, the picture the fresh one or else the known one.
     */
    fun merge(fresh: List<CachedArticle>, known: List<CachedArticle>): List<CachedArticle> {
        if (known.isEmpty()) return fresh
        val byUrl = known.associateBy { it.url }
        return fresh.map { item ->
            val old = byUrl[item.url] ?: return@map item
            item.copy(
                source = old.source,
                fetchedAt = old.fetchedAt,
                publishedAt = if (item.dated) item.publishedAt else old.publishedAt,
                thumbnailUrl = item.thumbnailUrl ?: old.thumbnailUrl
            )
        }
    }

    /**
     * The stream: the rows of [shown]'s feeds, newest first (published, then first seen, then
     * address), each read or not by [marks]; every feed of [shown] a chip, in its order, with its
     * unread and total counts. Rows of a feed [shown] doesn't hold are left out, and so are rows
     * published before [oldest] (the 30-day view, F6: a headline its feed still carries stays in the
     * cache past it, with its read mark, so it never comes back as new).
     */
    fun stream(shown: List<Feed>, rows: List<CachedArticle>, marks: FeedMarks, oldest: Long = Long.MIN_VALUE): FeedStream {
        val feeds = shown.distinctBy { it.url }
        val labels = feeds.associate { it.url to it.label }
        val entries = rows.mapNotNull { row ->
            if (row.publishedAt < oldest) return@mapNotNull null
            val label = labels[row.source] ?: return@mapNotNull null
            FeedEntry(
                url = row.url,
                title = row.title,
                feedUrl = row.source,
                feedLabel = label,
                publishedAt = row.publishedAt,
                firstSeenAt = row.fetchedAt,
                thumbnailUrl = row.thumbnailUrl,
                read = FeedMarkRules.isRead(marks, row.url, row.source, row.fetchedAt)
            )
        }.sortedWith(NEWEST_FIRST)
        val total = entries.groupingBy { it.feedUrl }.eachCount()
        val unread = entries.filterNot { it.read }.groupingBy { it.feedUrl }.eachCount()
        return FeedStream(
            chips = feeds.map { FeedChip(it, unread = unread[it.url] ?: 0, total = total[it.url] ?: 0) },
            entries = entries
        )
    }

    /** Daily's block (F5): the [limit] newest unread headlines of [stream], and what the block needs to know of the rest. */
    fun daily(stream: FeedStream, limit: Int = DAILY_UNREAD): DailyFeeds = DailyFeeds(
        entries = stream.entries.asSequence().filterNot { it.read }.take(limit.coerceAtLeast(0)).toList(),
        hasFeeds = stream.chips.isNotEmpty(),
        anyCached = stream.entries.isNotEmpty(),
        unread = stream.unread
    )

    /**
     * The tab's dot: one of [rows] first seen after the tab's last opening and still unread, among
     * those the tab shows (published from [oldest] on, as [stream]).
     */
    fun hasNew(rows: List<CachedArticle>, marks: FeedMarks, oldest: Long = Long.MIN_VALUE): Boolean =
        rows.any {
            it.publishedAt >= oldest && it.fetchedAt > marks.tabOpenedAt &&
                !FeedMarkRules.isRead(marks, it.url, it.source, it.fetchedAt)
        }

    /** RSS (RFC 1123) or Atom (ISO 8601) dates; null when it is neither. */
    fun parseDate(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
    }

    /**
     * An item's link when it is http(s), trimmed, and no longer than the import accepts
     * ([ImportUrlValidator.MAX_LEN]: a longer one couldn't be imported or read, and would overflow
     * the import's work data); null for anything else (javascript:, file:, none).
     */
    private fun linkOf(item: FeedItem): String? = item.link?.trim()?.takeIf {
        it.length <= ImportUrlValidator.MAX_LEN &&
            (it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true))
    }
}
