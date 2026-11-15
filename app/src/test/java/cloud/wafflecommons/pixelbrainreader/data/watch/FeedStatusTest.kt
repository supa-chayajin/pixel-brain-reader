package cloud.wafflecommons.pixelbrainreader.data.watch

import cloud.wafflecommons.pixelbrainreader.data.web.PageProblem
import cloud.wafflecommons.pixelbrainreader.data.web.PageUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A feed's last read and last failure, for the feeds page (12.18, D5a). */
class FeedStatusTest {

    private val url = "https://news.example/rss"
    private val minute = 60_000L

    @Test
    fun `a failure keeps the last read, and the next read clears it`() {
        var statuses = FeedStatuses.read(emptyMap(), url, now = 1_000L)
        statuses = FeedStatuses.failed(statuses, url, now = 2_000L, problem = "gone (HTTP 404)")
        assertTrue(statuses.getValue(url).failing)
        assertEquals(1_000L, statuses.getValue(url).readAt)
        assertEquals("gone (HTTP 404)", statuses.getValue(url).problem)

        statuses = FeedStatuses.read(statuses, url, now = 3_000L)
        assertFalse(statuses.getValue(url).failing)
        assertEquals(FeedStatus(readAt = 3_000L), statuses.getValue(url))
    }

    @Test
    fun `a feed that just failed waits ten minutes before its next try`() {
        val statuses = FeedStatuses.failed(emptyMap(), url, now = 0L, problem = "No answer")
        assertEquals(setOf(url), FeedStatuses.failedRecently(statuses, now = 9 * minute))
        assertEquals(emptySet<String>(), FeedStatuses.failedRecently(statuses, now = 10 * minute))
    }

    @Test
    fun `each failure says why in a few words`() {
        // Each one follows "Failed 5 min ago: " on the feeds page.
        assertEquals("no headlines at this address (a web page, not a feed?)", FeedStatuses.problemOf(null))
        assertEquals("gone (HTTP 404)", FeedStatuses.problemOf(PageUnavailableException(PageProblem.GONE, 404)))
        assertEquals("refused by the site (HTTP 403)", FeedStatuses.problemOf(PageUnavailableException(PageProblem.REFUSED, 403)))
        assertEquals("blocked, not a public address", FeedStatuses.problemOf(PageUnavailableException(PageProblem.NOT_PUBLIC)))
        assertEquals("no answer (the network, or the site is down)", FeedStatuses.problemOf(PageUnavailableException(PageProblem.NETWORK)))
        assertEquals("not a feed (application/pdf)", FeedStatuses.problemOf(PageUnavailableException(PageProblem.NOT_HTML, detail = "application/pdf")))
        assertEquals("not readable as RSS or Atom (a web page?)", FeedStatuses.problemOf(IllegalArgumentException("not xml")))
    }

    @Test
    fun `statuses survive their storage, and an unreadable one is no status`() {
        val statuses = FeedStatuses.failed(FeedStatuses.read(emptyMap(), url, 5L), "https://other.example/atom", 6L, "An empty answer")
        assertEquals(statuses, FeedStatuses.decode(FeedStatuses.encode(statuses)))
        assertEquals(emptyMap<String, FeedStatus>(), FeedStatuses.decode("{not json"))
        assertEquals(emptyMap<String, FeedStatus>(), FeedStatuses.decode(null))
    }
}
