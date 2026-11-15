package cloud.wafflecommons.pixelbrainreader.data.modes

import java.time.LocalDate

/**
 * A leave from the calendar (E3, 12.21): an all-day event named like a leave ("Congés", "Vacances",
 * "RTT", "Leave", "Vacation"…) becomes a [LeavePeriod] marked as the calendar's, so the next import
 * moves or removes it with its event. A leave typed in Modes settings is never touched. Pure.
 */
object CalendarLeaves {

    /** How far ahead the import looks. */
    const val DAYS_AHEAD = 120

    const val SOURCE = "calendar"

    /** An all-day event of the calendar: [end] is its last day (Google's end date is exclusive). */
    data class Event(val id: String, val title: String, val start: LocalDate, val end: LocalDate)

    private val WORDS = listOf(
        "congé", "congés", "conge", "conges", "vacances", "rtt", "leave", "vacation", "vacations",
        "holiday", "holidays", "ooo", "out of office", "pto", "day off", "jour off"
    )
    private val LEAVE = Regex(
        // Letters and spaces only: nothing to escape (Regex.escape's \\Q…\\E is left out on purpose).
        "(?<![\\p{L}\\p{N}])(?:" + WORDS.joinToString("|") + ")(?![\\p{L}\\p{N}])"
    )

    /** True when [title] names a leave. */
    fun isLeave(title: String): Boolean = LEAVE.containsMatchIn(title.lowercase())

    /** The leaves among an all-day event's [start] and exclusive [endExclusive], null when it isn't one. */
    fun of(id: String, title: String, start: LocalDate, endExclusive: LocalDate): Event? {
        if (!isLeave(title)) return null
        val end = endExclusive.minusDays(1).takeIf { !it.isBefore(start) } ?: start
        return Event(id, title.trim(), start, end)
    }

    /**
     * [leaves] with the calendar's ones replaced by [events]. A calendar leave that ended before
     * [today] stays as history; one from today on that no longer has its event goes. Typed leaves
     * stay exactly as they are. Null when nothing changes.
     */
    fun merge(leaves: List<LeavePeriod>, events: List<Event>, today: LocalDate): List<LeavePeriod>? {
        val kept = leaves.filter { leave ->
            leave.source != SOURCE || runCatching { LocalDate.parse(leave.end).isBefore(today) }.getOrDefault(false)
        }
        val keptIds = kept.filter { it.source == SOURCE }.map { it.eventId }.toSet()
        val fresh = events
            .filter { !it.end.isBefore(today) && it.id !in keptIds }
            .map { LeavePeriod(start = it.start.toString(), end = it.end.toString(), label = it.title, source = SOURCE, eventId = it.id) }
        val merged = kept + fresh
        return merged.takeIf { it.toSet() != leaves.toSet() }
    }
}
