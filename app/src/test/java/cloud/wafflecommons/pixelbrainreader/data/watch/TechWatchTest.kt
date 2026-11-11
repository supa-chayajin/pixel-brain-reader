package cloud.wafflecommons.pixelbrainreader.data.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tech watch's rules (D5): grouping, freshness, what a feed item becomes, dates. */
class TechWatchTest {

    private val dev = Feed("https://dev.example/feed", "Dev", work = true)
    private val local = Feed("https://news.example/rss", "Local")
    private val hour = 3_600_000L

    private fun article(feed: Feed, n: Int, published: Long) = CachedArticle("https://x.example/$n", "Title $n", feed.url, published, fetchedAt = 1_000L)

    @Test
    fun `each feed keeps its order and shows its three newest headlines`() {
        val articles = (1..5).map { article(dev, it, published = it * hour) } + article(local, 9, published = 0L)

        val groups = TechWatch.group(listOf(local, dev), articles)

        assertEquals(listOf("Local", "Dev"), groups.map { it.feed.label })
        assertEquals(listOf("Title 5", "Title 4", "Title 3"), groups[1].headlines.map { it.title })
        assertEquals(1, groups[0].headlines.size)
    }

    @Test
    fun `a feed with nothing read yet is left out`() {
        assertEquals(listOf("Dev"), TechWatch.group(listOf(local, dev), listOf(article(dev, 1, 0L))).map { it.feed.label })
    }

    @Test
    fun `the cache is read again after two hours, at once when forced, not right after a failed try`() {
        val now = 100 * hour
        assertTrue(TechWatch.needsRefresh(lastFetch = null, lastFailure = null, now = now))
        assertFalse(TechWatch.needsRefresh(lastFetch = now - hour, lastFailure = null, now = now))
        assertTrue(TechWatch.needsRefresh(lastFetch = now - 2 * hour, lastFailure = null, now = now))
        assertTrue(TechWatch.needsRefresh(lastFetch = now - hour, lastFailure = null, now = now, force = true))
        assertFalse(TechWatch.needsRefresh(lastFetch = null, lastFailure = now - 60_000L, now = now))
        assertTrue(TechWatch.needsRefresh(lastFetch = null, lastFailure = now - TechWatch.RETRY_AFTER_MS, now = now))
    }

    @Test
    fun `only http links become headlines, each once, newest first, twenty at most`() {
        val items = listOf(
            FeedItem("Safe", "https://a.example/1", "Wed, 11 Nov 2026 09:00:00 GMT"),
            FeedItem("Again", "https://a.example/1", null),
            FeedItem("Script", "javascript:alert(1)", null),
            FeedItem("Local file", "file:///sdcard/x", null),
            FeedItem(null, "http://b.example/2", "2026-11-11T10:00:00+01:00")
        ) + (1..30).map { FeedItem("Old $it", "https://c.example/$it", "2020-01-01T00:00:00Z") }

        val articles = TechWatch.articlesOf(dev, items, now = 5L)

        assertEquals(20, articles.size)
        assertEquals(listOf("Safe", "http://b.example/2"), articles.take(2).map { it.title })
        assertTrue(articles.all { it.url.startsWith("http") && it.source == dev.url })
    }

    @Test
    fun `RSS and Atom dates are read, anything else is unknown`() {
        assertEquals(1_794_387_600_000L, TechWatch.parseDate("Wed, 11 Nov 2026 09:00:00 GMT"))
        assertEquals(1_794_387_600_000L, TechWatch.parseDate("Wed, 11 Nov 2026 10:00:00 +0100"))
        assertEquals(1_794_387_600_000L, TechWatch.parseDate("2026-11-11T09:00:00Z"))
        assertEquals(1_794_387_600_000L, TechWatch.parseDate("2026-11-11T10:00:00+01:00"))
        assertNull(TechWatch.parseDate("yesterday"))
        assertNull(TechWatch.parseDate(null))
    }

    @Test
    fun `a dateless item takes the time it was read`() {
        assertEquals(42L, TechWatch.articlesOf(dev, listOf(FeedItem("A", "https://a.example", null)), now = 42L).single().publishedAt)
    }
}
