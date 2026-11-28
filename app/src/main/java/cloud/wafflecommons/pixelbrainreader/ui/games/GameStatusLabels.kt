package cloud.wafflecommons.pixelbrainreader.ui.games

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * When a console last synced, as the Games lines and the console pages say it (13.4): its day, then
 * its time, "today 06:00", "yesterday 23:40", "25 Nov 23:40". A time alone made a sync three days
 * old read like last night's, on the very line meant to show a stalled sync (the audit's
 * status-lines-mislead). [today] comes from the screen's state.
 */
fun syncedLabel(millis: Long, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
    val moment = Instant.ofEpochMilli(millis).atZone(zone)
    return "${dayLabel(moment.toLocalDate(), today)} ${moment.toLocalTime().format(HOURS_MINUTES)}"
}

private val HOURS_MINUTES: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
