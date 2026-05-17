package cloud.wafflecommons.pixelbrainreader.data.utils

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Parses inline `/event` commands the user types in daily-note bodies into
 * structured [ParsedEvent] payloads for Google Calendar creation.
 *
 * Grammar (case-insensitive, bilingual):
 *
 *   /event <when?> <title>
 *
 * <when> tokens (consumed greedily, in any order, max 1 date + 1 time):
 *   - "today" | "aujourd'hui" | "auj"   → today
 *   - "tomorrow" | "demain"             → today + 1
 *   - "YYYY-MM-DD"                      → ISO date
 *   - "HHh" | "HHhMM" | "HH:MM"         → time
 *
 * Missing date → today. Missing time → 09:00. End time is decided by the
 * caller (default duration lives in GoogleCalendarRepository).
 *
 * Examples:
 *   /event Demain 15h Dentiste
 *   /event today 14:30 Stand-up
 *   /event 2026-06-12 09h Project review
 */
object MarkdownCommandParser {
    private val EVENT_PREFIX = Regex("""^\s*/event\s+""", RegexOption.IGNORE_CASE)
    private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val TIME_REGEX = Regex("""(\d{1,2})(?:[h:](\d{2}))?""")

    private val TODAY_KEYWORDS = setOf("today", "aujourd'hui", "auj")
    private val TOMORROW_KEYWORDS = setOf("tomorrow", "demain")

    data class ParsedEvent(val title: String, val startsAt: LocalDateTime)

    fun parseEvent(line: String, today: LocalDate = LocalDate.now()): ParsedEvent? {
        val prefix = EVENT_PREFIX.find(line) ?: return null
        val remainder = line.substring(prefix.range.last + 1).trim()
        if (remainder.isEmpty()) return null

        val tokens = remainder.split(Regex("""\s+"""))
        var date: LocalDate? = null
        var time: LocalTime? = null
        var titleStart = 0

        for ((i, token) in tokens.withIndex()) {
            val lower = token.lowercase()
            when {
                date == null && lower in TODAY_KEYWORDS -> { date = today; titleStart = i + 1 }
                date == null && lower in TOMORROW_KEYWORDS -> { date = today.plusDays(1); titleStart = i + 1 }
                date == null && ISO_DATE.matches(token) -> { date = LocalDate.parse(token); titleStart = i + 1 }
                time == null && looksLikeTime(token) -> { time = parseTime(token); titleStart = i + 1 }
                else -> break
            }
        }

        val title = tokens.drop(titleStart).joinToString(" ").trim()
        if (title.isEmpty()) return null

        return ParsedEvent(
            title = title,
            startsAt = LocalDateTime.of(date ?: today, time ?: LocalTime.of(9, 0))
        )
    }

    private fun looksLikeTime(token: String): Boolean =
        TIME_REGEX.matchEntire(token) != null && token.any { it == 'h' || it == ':' }

    private fun parseTime(token: String): LocalTime? {
        val m = TIME_REGEX.matchEntire(token) ?: return null
        val hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }
}
