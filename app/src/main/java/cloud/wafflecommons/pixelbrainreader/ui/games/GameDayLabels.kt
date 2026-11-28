package cloud.wafflecommons.pixelbrainreader.ui.games

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A day as the Games pages say it (13.4): "today", "yesterday", else "25 Nov". [today] comes from
 * the screen's state (AppClock's day), never read here: a page left open overnight says "yesterday"
 * once its state moves to the new day (the audit's day-labels-stale-overnight).
 */
fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "today"
    today.minusDays(1) -> "yesterday"
    else -> date.format(DAY_MONTH)
}

private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
