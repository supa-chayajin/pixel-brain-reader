package cloud.wafflecommons.pixelbrainreader.data.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The feeds' rules (D5; 13.4, F2–F7): freshness, what a feed item becomes, the 30-day
 * history, merging a read into the cache, the stream, Daily's pick, the tab's dot, dates.
 */
class TechWatchTest {

    private val dev = Feed("https://dev.example/feed", "Dev", work = true)
    private val local = Feed("https://news.example/rss", "Local")
    private val hour = 3_600_000L
    private val day = 24 * hour

    /** 2026-11-28 09:00 UTC. */
    private val now = 1_795_856_400_000L

    /** An Atom date [ago] before [now]. */
    private fun dated(ago: Long) = Instant.ofEpochMilli(now - ago).toString()

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
    fun `a feed a sync just added is read at once, the rest waits for the cache to be due`() {
        val now = 100 * hour
        val added = Feed("https://new.example/atom", "New")
        val cached = setOf(dev.url, local.url)

        // Fresh cache: only the feed with nothing cached is read.
        assertEquals(listOf(added), TechWatch.feedsToRead(listOf(dev, local, added), cached, lastFetch = now - hour, lastFailure = null, now = now))
        // Due cache, or forced: all of them.
        assertEquals(3, TechWatch.feedsToRead(listOf(dev, local, added), cached, lastFetch = now - 2 * hour, lastFailure = null, now = now).size)
        assertEquals(3, TechWatch.feedsToRead(listOf(dev, local, added), cached, lastFetch = now - hour, lastFailure = null, now = now, force = true).size)
        // Nothing new and a fresh cache: nothing to read.
        assertEquals(emptyList<Feed>(), TechWatch.feedsToRead(listOf(dev, local), cached, lastFetch = now - hour, lastFailure = null, now = now))
    }

    @Test
    fun `a new feed that just failed waits like the rest`() {
        val now = 100 * hour
        val broken = Feed("https://broken.example/rss", "Broken")
        assertEquals(
            emptyList<Feed>(),
            TechWatch.feedsToRead(listOf(dev, broken), setOf(dev.url), lastFetch = now - hour, lastFailure = null, now = now, failedRecently = setOf(broken.url))
        )
    }

    @Test
    fun `only http links become headlines, each once, newest first`() {
        val items = listOf(
            FeedItem("Safe", "https://a.example/1", "Wed, 25 Nov 2026 09:00:00 GMT"),
            FeedItem("Again", "https://a.example/1", null),
            FeedItem("Script", "javascript:alert(1)", null),
            FeedItem("Local file", "file:///sdcard/x", null),
            FeedItem(null, " http://b.example/2 ", "2026-11-26T10:00:00+01:00")
        ) + (1..25).map { FeedItem("Older $it", "https://c.example/$it", dated((it + 3) * day)) }

        val articles = TechWatch.articlesOf(dev, items, now)

        // No cap of 20 any more: every item of the last 30 days stays.
        assertEquals(27, articles.size)
        assertEquals(listOf("http://b.example/2", "Safe"), articles.take(2).map { it.title })
        assertEquals("http://b.example/2", articles.first().url)
        assertTrue(articles.all { it.url.startsWith("http") && it.source == dev.url && it.fetchedAt == now && it.dated })
        assertEquals(articles.sortedByDescending { it.publishedAt }, articles)
    }

    @Test
    fun `a link longer than 2048 characters is no headline`() {
        val longest = "https://a.example/" + "x".repeat(2048 - "https://a.example/".length)
        val tooLong = "$longest/"
        val items = listOf(FeedItem("Longest", longest, null), FeedItem("Too long", tooLong, null))

        assertEquals(listOf(longest), TechWatch.articlesOf(dev, items, now).map { it.url })
        // A feed whose only links are too long answered with no headline.
        assertFalse(TechWatch.hasHeadlines(listOf(FeedItem("Too long", tooLong, null))))
    }

    @Test
    fun `a title is cut at 500 characters`() {
        val article = TechWatch.articlesOf(dev, listOf(FeedItem("t".repeat(20_000), "https://a.example/1", null)), now).single()
        assertEquals(TechWatch.MAX_TITLE_CHARS, article.title.length)
        assertEquals(500, TechWatch.MAX_TITLE_CHARS)
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
        val article = TechWatch.articlesOf(dev, listOf(FeedItem("A", "https://a.example", null)), now = 42L).single()
        assertEquals(42L, article.publishedAt)
        assertFalse(article.dated)
    }

    @Test
    fun `items older than thirty days are left out, a dateless one counts as new`() {
        val items = listOf(
            FeedItem("Fresh", "https://a.example/1", dated(29 * day)),
            FeedItem("Old", "https://a.example/2", dated(31 * day)),
            FeedItem("Undated", "https://a.example/3", null),
            // Exactly thirty days: still in.
            FeedItem("Edge", "https://a.example/4", dated(TechWatch.KEEP_MS))
        )

        val articles = TechWatch.articlesOf(local, items, now)

        assertEquals(listOf("Undated", "Fresh", "Edge"), articles.map { it.title })
        assertEquals(now, articles.first().publishedAt)
        assertFalse(articles.first().dated)
        assertEquals(30, TechWatch.KEEP_DAYS)
        assertEquals(30 * day, TechWatch.KEEP_MS)
        assertEquals(now - 30 * day, TechWatch.cutoff(now))
    }

    @Test
    fun `a feed with links but only old items has headlines`() {
        val old = listOf(FeedItem("Old", "https://a.example/2", "2020-01-01T00:00:00Z"))
        assertTrue(TechWatch.hasHeadlines(old))
        assertTrue(TechWatch.articlesOf(local, old, now).isEmpty())
        // No http(s) link at all: the address answered with no headline.
        assertFalse(TechWatch.hasHeadlines(listOf(FeedItem("Page", "javascript:void(0)", null), FeedItem("No link", null, null))))
        assertFalse(TechWatch.hasHeadlines(emptyList()))
    }

    @Test
    fun `an item's picture is taken from its candidates, https only, made absolute against its link`() {
        val items = listOf(
            FeedItem("Absolute", "https://news.example/a/1", null, images = listOf("https://cdn.example.org/1.jpg")),
            FeedItem("Relative", "https://news.example/a/2", null, images = listOf("/img/2.png")),
            FeedItem("Http", "https://news.example/a/3", null, images = listOf("http://cdn.example.org/3.jpg")),
            FeedItem("Second", "https://news.example/a/4", null, images = listOf("http://cdn.example.org/4.jpg", "//cdn.example.org/4.jpg")),
            FeedItem("Private", "https://news.example/a/5", null, images = listOf("https://192.168.1.20/5.jpg")),
            FeedItem("None", "https://news.example/a/6", null)
        )

        val pictures = TechWatch.articlesOf(local, items, now).associate { it.title to it.thumbnailUrl }

        assertEquals("https://cdn.example.org/1.jpg", pictures["Absolute"])
        assertEquals("https://news.example/img/2.png", pictures["Relative"])
        assertNull(pictures["Http"])
        assertEquals("https://cdn.example.org/4.jpg", pictures["Second"])
        assertNull(pictures["Private"])
        assertNull(pictures["None"])
        assertEquals(6, pictures.size)
    }

    @Test
    fun `merge keeps the first-seen time and the first feed, takes the new title, keeps a picture the feed dropped`() {
        val known = listOf(
            CachedArticle("https://a.example/1", "Old title", dev.url, publishedAt = now - 2 * day, fetchedAt = now - 2 * day, thumbnailUrl = "https://cdn.example.org/1.jpg")
        )
        val fresh = listOf(
            CachedArticle("https://a.example/1", "New title", local.url, publishedAt = now - day, fetchedAt = now, thumbnailUrl = null),
            CachedArticle("https://a.example/2", "Brand new", local.url, publishedAt = now, fetchedAt = now, thumbnailUrl = "https://cdn.example.org/2.jpg")
        )

        val merged = TechWatch.merge(fresh, known)

        assertEquals(
            CachedArticle("https://a.example/1", "New title", dev.url, publishedAt = now - day, fetchedAt = now - 2 * day, thumbnailUrl = "https://cdn.example.org/1.jpg"),
            merged[0]
        )
        assertEquals(fresh[1], merged[1])
        // A picture the feed gives now wins over the one kept.
        val repictured = TechWatch.merge(listOf(fresh[0].copy(thumbnailUrl = "https://cdn.example.org/1b.jpg")), known).single()
        assertEquals("https://cdn.example.org/1b.jpg", repictured.thumbnailUrl)
        assertEquals(fresh, TechWatch.merge(fresh, emptyList()))
    }

    @Test
    fun `an undated item keeps the date it was first given`() {
        val item = FeedItem("Undated", "https://a.example/3", null)
        val first = TechWatch.articlesOf(local, listOf(item), now - 3 * day)
        val later = TechWatch.articlesOf(local, listOf(item), now)

        val merged = TechWatch.merge(later, first).single()

        assertEquals(now - 3 * day, merged.publishedAt)
        assertEquals(now - 3 * day, merged.fetchedAt)
        // A date the feed gives later wins.
        val withDate = TechWatch.articlesOf(local, listOf(item.copy(published = dated(5 * day))), now)
        assertEquals(now - 5 * day, TechWatch.merge(withDate, first).single().publishedAt)
    }

    @Test
    fun `the stream is newest first, each headline with its feed's label and its mark, every feed of the mode a chip`() {
        val garden = Feed("https://garden.example/rss", "Garden Notes")
        val rows = listOf(
            CachedArticle("https://news.example/1", "Local one", local.url, publishedAt = now - 3 * hour, fetchedAt = now - 3 * hour),
            CachedArticle("https://dev.example/1", "Dev one", dev.url, publishedAt = now - hour, fetchedAt = now - hour),
            CachedArticle("https://dev.example/2", "Dev two", dev.url, publishedAt = now - 2 * hour, fetchedAt = now - 2 * hour),
            // The same date as Dev two: the one seen last comes first.
            CachedArticle("https://news.example/3", "Local three", local.url, publishedAt = now - 2 * hour, fetchedAt = now),
            // A feed this mode doesn't show.
            CachedArticle("https://other.example/1", "Not shown", "https://other.example/rss", now, now)
        )

        val stream = TechWatch.stream(listOf(local, dev, garden), rows, FeedMarks(read = setOf("https://dev.example/2")))

        assertEquals(listOf("Dev one", "Local three", "Dev two", "Local one"), stream.entries.map { it.title })
        assertEquals(listOf("Dev", "Local", "Dev", "Local"), stream.entries.map { it.feedLabel })
        assertEquals(listOf(dev.url, local.url, dev.url, local.url), stream.entries.map { it.feedUrl })
        assertEquals(listOf(false, false, true, false), stream.entries.map { it.read })
        assertEquals(now, stream.entries[1].firstSeenAt)
        // Every feed of the mode is a chip, in the owner's order, one with nothing cached too.
        assertEquals(listOf(local, dev, garden), stream.chips.map { it.feed })
        assertEquals(listOf(2, 1, 0), stream.chips.map { it.unread })
        assertEquals(listOf(2, 2, 0), stream.chips.map { it.total })
        assertEquals(3, stream.unread)
    }

    @Test
    fun `daily takes the five newest unread`() {
        val rows = (1..8).map { CachedArticle("https://news.example/$it", "Item $it", local.url, publishedAt = now - it * hour, fetchedAt = now - it * hour) }

        val daily = TechWatch.daily(TechWatch.stream(listOf(local), rows, FeedMarks(read = setOf("https://news.example/2"))))

        assertEquals(listOf("Item 1", "Item 3", "Item 4", "Item 5", "Item 6"), daily.entries.map { it.title })
        assertEquals(7, daily.unread)
        assertTrue(daily.hasFeeds)
        assertTrue(daily.anyCached)
        assertEquals(5, TechWatch.DAILY_UNREAD)
    }

    @Test
    fun `daily says the mode has feeds and headlines when all is read`() {
        val rows = listOf(CachedArticle("https://news.example/1", "Item", local.url, now, now))

        val allRead = TechWatch.daily(TechWatch.stream(listOf(local), rows, FeedMarks(readUpTo = mapOf(local.url to now))))

        assertTrue(allRead.entries.isEmpty())
        assertTrue(allRead.hasFeeds)
        assertTrue(allRead.anyCached)
        assertEquals(0, allRead.unread)
        // Feeds, nothing cached yet; no feed at all.
        val nothingYet = TechWatch.daily(TechWatch.stream(listOf(local), emptyList(), FeedMarks()))
        assertTrue(nothingYet.hasFeeds)
        assertFalse(nothingYet.anyCached)
        assertEquals(DailyFeeds(), TechWatch.daily(FeedStream()))
    }

    @Test
    fun `hasNew is true for an unread headline only`() {
        val opened = now - hour
        val marks = FeedMarks(tabOpenedAt = opened, read = setOf("https://news.example/2"))
        val unreadNew = CachedArticle("https://news.example/1", "New", local.url, now, fetchedAt = now)
        val readNew = CachedArticle("https://news.example/2", "Opened", local.url, now, fetchedAt = now)
        val seenBefore = CachedArticle("https://news.example/3", "Before", local.url, now, fetchedAt = opened)

        assertTrue(TechWatch.hasNew(listOf(readNew, unreadNew), marks))
        assertFalse(TechWatch.hasNew(listOf(readNew, seenBefore), marks))
        assertFalse(TechWatch.hasNew(listOf(unreadNew), marks.copy(readUpTo = mapOf(local.url to now))))
        assertFalse(TechWatch.hasNew(emptyList(), marks))
    }

    @Test
    fun `an undated headline its feed still carries past 30 days is neither new again nor shown unread`() {
        val url = "https://news.example/u"
        val item = FeedItem("Undated", url, null)
        // First seen 31 days ago and opened then; the feed still lists it today.
        val first = TechWatch.articlesOf(local, listOf(item), now - 31 * day).single()
        val marks = FeedMarks(tabOpenedAt = now - day, read = setOf(url))

        // Today's read merges into the row the age prune spared: its first-seen time and date stay.
        val merged = TechWatch.merge(TechWatch.articlesOf(local, listOf(item), now), listOf(first)).single()
        assertEquals(now - 31 * day, merged.fetchedAt)
        assertEquals(now - 31 * day, merged.publishedAt)
        // So it lights no dot (even unread), its read mark stays (it is still cached), and the
        // 30-day view leaves it out.
        assertFalse(TechWatch.hasNew(listOf(merged), marks.copy(read = emptySet())))
        assertEquals(marks, FeedMarkRules.prune(marks, cachedUrls = setOf(url), feedUrls = setOf(local.url)))
        val stream = TechWatch.stream(listOf(local), listOf(merged), marks, oldest = TechWatch.cutoff(now))
        assertTrue(stream.entries.isEmpty())
        assertEquals(0, stream.unread)
    }

    @Test
    fun `the 30-day view shows a headline published at its edge and none before it`() {
        val edge = CachedArticle("https://news.example/edge", "Edge", local.url, publishedAt = TechWatch.cutoff(now), fetchedAt = now)
        val older = CachedArticle("https://news.example/older", "Older", local.url, publishedAt = TechWatch.cutoff(now) - 1, fetchedAt = now)

        val stream = TechWatch.stream(listOf(local), listOf(edge, older), FeedMarks(), oldest = TechWatch.cutoff(now))

        assertEquals(listOf("Edge"), stream.entries.map { it.title })
        assertEquals(1, stream.chips.single().total)
        // The dot agrees with the view: the older one, unread and new, lights nothing.
        assertFalse(TechWatch.hasNew(listOf(older), FeedMarks(), oldest = TechWatch.cutoff(now)))
        assertTrue(TechWatch.hasNew(listOf(edge), FeedMarks(), oldest = TechWatch.cutoff(now)))
    }

    @Test
    fun `a headline that enters the cache after the tab was opened lights the dot`() {
        // The refresh began at [now]; its download took 40 s; the tab was opened and left 10 s in.
        val opened = now + 10_000L
        val items = listOf(FeedItem("Undated", "https://news.example/u", null), FeedItem("Dated", "https://news.example/d", dated(hour)))

        val rows = TechWatch.articlesOf(local, items, now = now, seenAt = now + 40_000L)

        assertTrue(rows.all { it.fetchedAt == now + 40_000L })
        assertEquals(now + 40_000L, rows.first { it.title == "Undated" }.publishedAt)
        assertTrue(TechWatch.hasNew(rows, FeedMarks(tabOpenedAt = opened), oldest = TechWatch.cutoff(now)))
        // Stamped with the refresh's start instead, they would never light it.
        assertFalse(TechWatch.hasNew(TechWatch.articlesOf(local, items, now), FeedMarks(tabOpenedAt = opened)))
        // The cutoff stays the refresh's: an item 30 days old at its start is still in.
        val edge = FeedItem("Edge", "https://news.example/e", dated(TechWatch.KEEP_MS))
        assertEquals(1, TechWatch.articlesOf(local, listOf(edge), now = now, seenAt = now + 40_000L).size)
    }
}
