package cloud.wafflecommons.pixelbrainreader.ui.feeds

import cloud.wafflecommons.pixelbrainreader.data.watch.FeedEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** The Feeds tab's days (13.4, F2): the stream, newest first, under the day each headline was published. */
class FeedDaysTest {

    /** A zone an hour ahead of UTC: a headline's day is the zone's, not UTC's. */
    private val zone = ZoneOffset.ofHours(1)
    private val today = LocalDate.of(2026, 11, 25)

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun entry(id: Int, publishedAt: Long) = FeedEntry(
        url = "https://news.example/a/$id",
        title = "Headline $id",
        feedUrl = "https://news.example/rss",
        feedLabel = "Local",
        publishedAt = publishedAt,
        firstSeenAt = publishedAt,
        thumbnailUrl = null,
        read = false
    )

    @Test
    fun `headlines group by the day they were published, newest first, today and yesterday named`() {
        val entries = listOf(
            entry(5, at("2026-11-25T09:00")),
            // 23:30 in UTC on the 24th is half past midnight on the 25th in the zone.
            entry(4, Instant.parse("2026-11-24T23:30:00Z").toEpochMilli()),
            entry(3, at("2026-11-24T22:15")),
            entry(2, at("2026-11-23T08:00")),
            entry(1, at("2026-11-23T07:00"))
        )

        val days = FeedDays.group(entries, today, zone)

        assertEquals(listOf("Today", "Yesterday", "Mon 23 Nov"), days.map { it.label })
        assertEquals(listOf(today, LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 23)), days.map { it.date })
        assertEquals(
            listOf(listOf(5, 4), listOf(3), listOf(2, 1)),
            days.map { day -> day.entries.map { it.url.substringAfterLast('/').toInt() } }
        )
    }

    @Test
    fun `a headline dated after today counts as today's`() {
        // A feed whose clock runs ahead: its age reads "Just now", so it sits under Today.
        val days = FeedDays.group(listOf(entry(2, at("2026-11-27T10:00")), entry(1, at("2026-11-25T08:00"))), today, zone)

        assertEquals(listOf("Today"), days.map { it.label })
        assertEquals(2, days.single().entries.size)
    }

    @Test
    fun `no headline, no day`() {
        assertTrue(FeedDays.group(emptyList(), today, zone).isEmpty())
    }

    @Test
    fun `an older day reads as its short day and date`() {
        assertEquals("Wed 18 Nov", FeedDays.labelOf(LocalDate.of(2026, 11, 18), today))
        assertEquals("Yesterday", FeedDays.labelOf(LocalDate.of(2026, 11, 24), today))
        assertEquals("Today", FeedDays.labelOf(today, today))
    }
}
