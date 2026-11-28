package cloud.wafflecommons.pixelbrainreader.data.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 13.4 (F3): this device's read marks: one by one, "Mark all read" per feed, the tab's opening, pruning, storage. */
class FeedMarksTest {

    private val local = Feed("https://news.example/rss", "Local")
    private val dev = Feed("https://dev.example/feed", "Dev")

    private fun entry(feed: Feed, n: Int, firstSeen: Long) =
        FeedEntry("${feed.url}/$n", "Item $n", feed.url, feed.label, publishedAt = firstSeen, firstSeenAt = firstSeen, thumbnailUrl = null, read = false)

    @Test
    fun `an opened headline is read, and one first seen at or before its feed's mark`() {
        val marks = FeedMarkRules.markRead(FeedMarks(readUpTo = mapOf(local.url to 100L)), "https://news.example/a/9")

        assertTrue(FeedMarkRules.isRead(marks, "https://news.example/a/9", local.url, firstSeenAt = 500L))
        assertTrue(FeedMarkRules.isRead(marks, "https://news.example/a/1", local.url, firstSeenAt = 100L))
        assertTrue(FeedMarkRules.isRead(marks, "https://news.example/a/1", local.url, firstSeenAt = 99L))
        assertFalse(FeedMarkRules.isRead(marks, "https://news.example/a/2", local.url, firstSeenAt = 101L))
        // Another feed's mark says nothing about this one.
        assertFalse(FeedMarkRules.isRead(marks, "https://dev.example/a/1", dev.url, firstSeenAt = 1L))
        // No mark at all: everything is unread.
        assertFalse(FeedMarkRules.isRead(FeedMarks(), "https://news.example/a/1", local.url, firstSeenAt = 0L))
        // Opening it again changes nothing.
        assertSame(marks, FeedMarkRules.markRead(marks, "https://news.example/a/9"))
    }

    @Test
    fun `mark all read moves each feed's mark to its newest headline in the view, never back`() {
        val view = listOf(entry(local, 1, 300L), entry(local, 2, 200L), entry(dev, 1, 250L))
        val start = FeedMarks(readUpTo = mapOf(dev.url to 400L), read = setOf("${local.url}/2", "https://elsewhere.example/1"))

        val marks = FeedMarkRules.markAllRead(start, view)

        // Dev's mark was already past its headline: it stays where it was.
        assertEquals(mapOf(dev.url to 400L, local.url to 300L), marks.readUpTo)
        // The view's single reads are covered by the marks now; the others stay.
        assertEquals(setOf("https://elsewhere.example/1"), marks.read)
        assertTrue(view.all { FeedMarkRules.isRead(marks, it.url, it.feedUrl, it.firstSeenAt) })
        // A headline first seen later stays unread, even one published earlier.
        assertFalse(FeedMarkRules.isRead(marks, "${local.url}/3", local.url, firstSeenAt = 301L))
        // An empty view changes nothing.
        assertSame(start, FeedMarkRules.markAllRead(start, emptyList()))
    }

    @Test
    fun `mark all read on one feed leaves the others unread`() {
        val marks = FeedMarkRules.markAllRead(FeedMarks(), listOf(entry(dev, 1, 250L), entry(dev, 2, 260L)))

        assertEquals(mapOf(dev.url to 260L), marks.readUpTo)
        assertTrue(FeedMarkRules.isRead(marks, "${dev.url}/1", dev.url, firstSeenAt = 250L))
        assertFalse(FeedMarkRules.isRead(marks, "${local.url}/1", local.url, firstSeenAt = 100L))
    }

    @Test
    fun `opening the tab moves its time forward only`() {
        val opened = FeedMarkRules.opened(FeedMarks(), now = 500L)
        assertEquals(500L, opened.tabOpenedAt)
        assertSame(opened, FeedMarkRules.opened(opened, now = 400L))
        assertEquals(600L, FeedMarkRules.opened(opened, now = 600L).tabOpenedAt)
    }

    @Test
    fun `prune drops addresses no longer cached and marks of feeds no longer listed`() {
        val marks = FeedMarks(
            readUpTo = mapOf(local.url to 1L, dev.url to 2L),
            read = setOf("${local.url}/1", "${local.url}/gone"),
            tabOpenedAt = 9L
        )

        val pruned = FeedMarkRules.prune(marks, cachedUrls = setOf("${local.url}/1", "${dev.url}/1"), feedUrls = setOf(local.url))

        assertEquals(FeedMarks(readUpTo = mapOf(local.url to 1L), read = setOf("${local.url}/1"), tabOpenedAt = 9L), pruned)
        // Nothing left to drop: the same marks.
        assertSame(pruned, FeedMarkRules.prune(pruned, setOf("${local.url}/1"), setOf(local.url)))
    }

    @Test
    fun `marks round-trip, and an unreadable value is no mark`() {
        val marks = FeedMarks(
            readUpTo = mapOf(local.url to 1_795_000_000_000L),
            read = setOf("https://news.example/a/1"),
            tabOpenedAt = 1_795_000_500_000L
        )

        val text = FeedMarkRules.encode(marks)

        assertEquals(marks, FeedMarkRules.decode(text))
        // The stored field names are persisted.
        assertTrue(text.contains("\"read_upto\""))
        assertTrue(text.contains("\"tab_opened_at\""))
        assertEquals(
            marks,
            FeedMarkRules.decode(
                """{ "read_upto": { "https://news.example/rss": 1795000000000 }, "read": ["https://news.example/a/1"], "tab_opened_at": 1795000500000, "later": 1 }"""
            )
        )
        assertEquals(FeedMarks(), FeedMarkRules.decode("{not json"))
        assertEquals(FeedMarks(), FeedMarkRules.decode("""{ "read_upto": { "https://news.example/rss": "soon" } }"""))
        assertEquals(FeedMarks(), FeedMarkRules.decode(""))
        assertEquals(FeedMarks(), FeedMarkRules.decode(null))
    }
}
