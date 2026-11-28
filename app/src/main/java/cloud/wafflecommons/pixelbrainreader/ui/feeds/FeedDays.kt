package cloud.wafflecommons.pixelbrainreader.ui.feeds

import cloud.wafflecommons.pixelbrainreader.data.watch.FeedEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One day of the Feeds tab's stream: its date, its header and its headlines, newest first. */
data class FeedDay(val date: LocalDate, val label: String, val entries: List<FeedEntry>)

/** The Feeds tab's days (13.4, F2), pure. */
object FeedDays {

    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /**
     * [entries] (newest first) by the day they were published in [zone]: "Today", "Yesterday",
     * else "Wed 25 Nov" (Locale.ENGLISH); the days newest first, each keeping its headlines' order.
     * A headline dated after [today] (a feed whose clock runs ahead) counts as today's: its age
     * reads "Just now" and it sorts first, so it sits under "Today", not under a day to come.
     */
    fun group(entries: List<FeedEntry>, today: LocalDate, zone: ZoneId): List<FeedDay> {
        if (entries.isEmpty()) return emptyList()
        val byDay = LinkedHashMap<LocalDate, MutableList<FeedEntry>>()
        entries.forEach { entry ->
            val published = Instant.ofEpochMilli(entry.publishedAt).atZone(zone).toLocalDate()
            val day = if (published.isAfter(today)) today else published
            byDay.getOrPut(day) { ArrayList() }.add(entry)
        }
        return byDay.entries
            .sortedByDescending { it.key }
            .map { (day, mine) -> FeedDay(day, labelOf(day, today), mine) }
    }

    /** "Today", "Yesterday", else the short day and date ("Wed 25 Nov"). */
    fun labelOf(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DAY)
    }
}
