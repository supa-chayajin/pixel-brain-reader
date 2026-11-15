package cloud.wafflecommons.pixelbrainreader.data.work

import java.time.LocalDate

/**
 * Inbox triage's snooze (E7, 12.21): a note leaves the inbox list until a day, kept on the phone
 * as `yyyy-MM-dd|path` entries (the note itself is never touched). Pure.
 */
object InboxSnooze {

    const val DAYS = 7

    /** The entries still running on [today]; past ones are dropped. */
    fun active(entries: Set<String>, today: LocalDate): Map<String, LocalDate> =
        entries.mapNotNull { entry ->
            val until = runCatching { LocalDate.parse(entry.substringBefore('|')) }.getOrNull() ?: return@mapNotNull null
            val path = entry.substringAfter('|', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (until.isAfter(today)) path to until else null
        }.toMap()

    /** [entries] with [path] snoozed until [days] after [today], older entries for it and past ones gone. */
    fun snooze(entries: Set<String>, path: String, today: LocalDate, days: Int = DAYS): Set<String> =
        active(entries, today).filterKeys { it != path }
            .map { (p, until) -> "$until|$p" }.toSet() + "${today.plusDays(days.toLong())}|$path"

    /** [entries] without [path]: it shows again. */
    fun wake(entries: Set<String>, path: String, today: LocalDate): Set<String> =
        active(entries, today).filterKeys { it != path }.map { (p, until) -> "$until|$p" }.toSet()
}
