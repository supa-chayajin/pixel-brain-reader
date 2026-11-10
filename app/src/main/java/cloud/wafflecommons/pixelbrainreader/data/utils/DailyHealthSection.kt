package cloud.wafflecommons.pixelbrainreader.data.utils

import cloud.wafflecommons.pixelbrainreader.data.health.DailyHealthMetrics
import java.util.Locale

/**
 * The `## 🩺 Health` section that ends each burned daily note (12.11.0, D3): the day's sleep, steps,
 * HRV and resting heart rate, measured values only (0 = not measured, so it is left out), read from
 * the day's health file at each burn.
 *
 * It is derived: nothing in it enters Room. [DailyMarkdownParser] knows the exact heading and skips
 * the lines [lines] writes, so the board's round trip holds (settled decision #7); any other line
 * found there (a line added at the end of the note on the desktop) moves into Notes instead of being
 * lost. A burn that changes only this section requests no push, like the health file it comes from:
 * it goes up with the next commit.
 */
object DailyHealthSection {

    /** Matched whole, emoji included: a user's own "## Health" heading inside Notes stays theirs. */
    const val HEADING = "## 🩺 Health"

    // ASCII digits spelled out: ICU's \d is Unicode-aware, the JVM's is not (AndroidRegexGuardTest).
    private val DERIVED = listOf(
        Regex("^- Sleep: (?:[0-9]+ h [0-9]{2}|[0-9]{1,2}) min$"),
        Regex("^- Steps: [0-9][0-9,]*$"),
        Regex("^- HRV: [0-9]+ ms$"),
        Regex("^- Resting heart rate: [0-9]+ bpm$")
    )

    /** The section's lines for [metrics], measured values only; empty when nothing was measured. */
    fun lines(metrics: DailyHealthMetrics?): List<String> {
        if (metrics == null) return emptyList()
        return buildList {
            if (metrics.sleepDurationMinutes > 0) add("- Sleep: ${duration(metrics.sleepDurationMinutes)}")
            if (metrics.steps > 0) add("- Steps: ${String.format(Locale.US, "%,d", metrics.steps)}")
            val hrv = Math.round(metrics.hrvRmssdMs)
            if (hrv > 0) add("- HRV: $hrv ms")
            if (metrics.restingHeartRate > 0) add("- Resting heart rate: ${metrics.restingHeartRate} bpm")
        }
    }

    /** True for a line [lines] writes (trimmed), whatever its value. */
    fun isDerivedLine(trimmedLine: String): Boolean = DERIVED.any { it.matches(trimmedLine) }

    /**
     * [content] without its Health section (the heading, the blank line before it, then the blank
     * and derived lines after it), to tell whether a burn changed anything else. Unchanged when the
     * note has no such section.
     */
    fun strip(content: String): String {
        val lines = content.split("\n")
        val heading = lines.indexOfFirst { it.trim() == HEADING }
        if (heading < 0) return content
        val start = if (heading > 0 && lines[heading - 1].isBlank()) heading - 1 else heading
        var end = heading + 1
        while (end < lines.size && (lines[end].isBlank() || isDerivedLine(lines[end].trim()))) end++
        // Keep the final empty element of a content ending with "\n", so the stripped text ends as
        // the note did before the section was added.
        if (end == lines.size && lines.last().isEmpty()) end--
        return (lines.subList(0, start) + lines.subList(end, lines.size)).joinToString("\n")
    }

    private fun duration(minutes: Long): String {
        val hours = minutes / 60
        val rest = minutes % 60
        return if (hours > 0) "$hours h ${rest.toString().padStart(2, '0')} min" else "$rest min"
    }
}
