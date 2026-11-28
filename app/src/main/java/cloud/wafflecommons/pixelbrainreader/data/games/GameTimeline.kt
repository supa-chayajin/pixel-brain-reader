package cloud.wafflecommons.pixelbrainreader.data.games

import cloud.wafflecommons.pixelbrainreader.data.utils.DailyMarkers
import cloud.wafflecommons.pixelbrainreader.data.utils.MarkdownBurner
import cloud.wafflecommons.pixelbrainreader.data.utils.TimelineLine
import java.time.LocalDate
import java.time.LocalTime

/** What the burn asks for: the day's game lines (empty when none, or when the games data can't be read). */
fun interface GameTimelineSource {
    suspend fun linesFor(date: LocalDate): List<TimelineLine>
}

/**
 * A game's line in a daily note's Timeline (RFC-009 §4.3, pure):
 * `- 21:05 🎮 <game> · 2 h 35 <!--game:<page>-->`. The line is derived from the sessions files:
 * today's burn writes it, [cloud.wafflecommons.pixelbrainreader.data.utils.DailyMarkdownParser]
 * leaves it out of the board, and a past day gets it through [applyToNote] (D11), never a re-burn.
 */
object GameTimeline {

    private val TIMED = Regex("^\\s*-\\s+([0-9]{1,2}):([0-9]{2})\\s+(.*)$")
    private val MARKER = Regex("<!--\\s*game:(.*?)-->")

    /** True when a Timeline line's text carries a game marker: it is derived, never board content. */
    fun isGameLine(text: String): Boolean = DailyMarkers.isGameLine(text)

    /** "45 min", "2 h", "2 h 05". */
    fun duration(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h %02d".format(minutes % 60)
    }

    /** What follows the time on the line. */
    fun text(line: GameDayLine): String =
        "🎮 ${MarkdownBurner.singleLine(line.page.title)} · ${duration(line.minutes)} <!--game:${line.page.key}-->"

    fun timelineLines(lines: List<GameDayLine>): List<TimelineLine> = lines.map { TimelineLine(it.start, text(it)) }

    /** The page key a game line's `<!--game:<key>-->` marker names, trimmed; null without one (13.4). */
    fun keyOf(text: String): String? = MARKER.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * [content] (a past day's note) with its Timeline's game lines replaced by [lines], in time order
     * among the other entries. Nothing else moves; a note without a Timeline section is unchanged. With
     * [knownKeys] (13.4: the current pages' keys), a game line is replaced only when its marker names
     * one of them (or none): the line of a page renamed or deleted since stays, as the day's history.
     * A Windows note (CRLF) is matched without its `\r` and its new lines get one too.
     */
    fun applyToNote(content: String, lines: List<TimelineLine>, knownKeys: Set<String>? = null): String {
        val all = content.split("\n").toMutableList()
        val heading = all.indexOfFirst(::isTimelineHeading)
        if (heading < 0) return content
        var end = (heading + 1 until all.size).firstOrNull { all[it].trimStart().startsWith("## ") || all[it].startsWith("# ") } ?: all.size
        val cr = if ((heading until end).any { all[it].endsWith("\r") }) "\r" else ""

        // Drop the derived lines this note holds (a line of a page that's gone stays, with knownKeys).
        var i = heading + 1
        while (i < end) {
            val line = all[i].trimEnd('\r')
            val derived = TIMED.matches(line) && isGameLine(line) &&
                (knownKeys == null || keyOf(line)?.let { it in knownKeys } ?: true)
            if (derived) {
                all.removeAt(i)
                end--
            } else {
                i++
            }
        }
        if (lines.isEmpty()) return all.joinToString("\n")

        // "*No events*" gives its place to the first line.
        val placeholder = (heading + 1 until end).firstOrNull { all[it].trim() == "*No events*" }
        if (placeholder != null) {
            all.removeAt(placeholder)
            end--
        }
        lines.sortedBy { it.time }.forEach { line ->
            val rendered = "- ${line.time.format(HH_MM)} ${line.text}$cr"
            val timed = (heading + 1 until end).filter { TIMED.matches(all[it].trimEnd('\r')) }
            val after = timed.firstOrNull { (timeOf(all[it]) ?: LocalTime.MIN) > line.time }
            val at = after ?: timed.lastOrNull()?.plus(1) ?: firstBodyLine(all, heading, end)
            all.add(at, rendered)
            end++
        }
        return all.joinToString("\n")
    }

    private fun timeOf(line: String): LocalTime? = TIMED.matchEntire(line.trimEnd('\r'))?.let { m ->
        runCatching { LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull()
    }

    /** Right under the heading's blank line, the burner's layout. */
    private fun firstBodyLine(lines: List<String>, heading: Int, end: Int): Int =
        if (heading + 1 < end && lines[heading + 1].isBlank()) heading + 2 else heading + 1

    /** `## 🗓️ Timeline`, with or without its emoji, any case. */
    internal fun isTimelineHeading(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.startsWith("## ")) return false
        val title = trimmed.removePrefix("## ").trim()
        if (!title.lowercase().endsWith("timeline")) return false
        return title.dropLast("timeline".length).none { it.isLetterOrDigit() }
    }
}

/** An earlier Timeline line that names a game (RFC-009 §4.3): a past session without a length. */
data class GameHistoryEntry(val date: LocalDate, val time: LocalTime, val page: GamePage, val text: String)

/** Reads the game sessions a daily note's own Timeline lines name (pure); derived game lines are left out. */
object GameHistory {

    private val TIMED = Regex("^\\s*-\\s+([0-9]{1,2}):([0-9]{2})\\s+(.*)$")

    fun entries(date: LocalDate, content: String, pages: List<GamePage>): List<GameHistoryEntry> {
        val lines = content.split("\n")
        val heading = lines.indexOfFirst(GameTimeline::isTimelineHeading)
        if (heading < 0) return emptyList()
        return lines.drop(heading + 1)
            .takeWhile { !it.trimStart().startsWith("## ") && !it.startsWith("# ") }
            .mapNotNull { line ->
                val m = TIMED.matchEntire(line.trimEnd('\r')) ?: return@mapNotNull null
                val text = m.groupValues[3].trim()
                if (GameTimeline.isGameLine(text)) return@mapNotNull null
                val page = GameNames.pageInLine(text, pages) ?: return@mapNotNull null
                val time = runCatching { LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull()
                    ?: return@mapNotNull null
                GameHistoryEntry(date, time, page, text)
            }
    }
}

