package cloud.wafflecommons.pixelbrainreader.data.games

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The `## 🏆 Trophies` section of a game page (D12, pure): rewritten at each sync like a daily
 * note's 🩺 Health, with the EARNED trophies only, dated, so the note shows no spoiler in Obsidian.
 * The heading is matched whole; everything above it, and any section after it, stays as written.
 */
object TrophySection {

    const val HEADING = "## 🏆 Trophies"

    /** The section's lines for the page's sets; empty when the page has no set. */
    fun lines(sets: List<TrophySetFile>, zone: ZoneId): List<String> {
        if (sets.isEmpty()) return emptyList()
        val total = sets.sumOf { it.trophies.size }
        val earned = sets.flatMap { set -> set.trophies.mapNotNull { t -> earnedOn(t, zone)?.let { Triple(set, t, it) } } }
        val percent = if (total == 0) 0 else earned.size * 100 / total
        val summary = "*${earned.size} of $total trophies · $percent %*"
        val named = sets.size > 1
        return listOf(summary, "") + earned.sortedWith(compareBy({ it.third }, { it.second.id })).map { (set, trophy, day) ->
            val where = if (named && set.title.isNotBlank()) " · ${set.title}" else ""
            "- ${gradeIcon(trophy.grade)} ${trophy.name.ifBlank { "Trophy ${trophy.id}" }} — $day$where"
        }
    }

    /** [content] with its section replaced by [lines] (appended when absent, removed when [lines] is empty). */
    fun apply(content: String, lines: List<String>): String {
        val all = content.split("\n")
        val start = all.indexOfFirst { it.trim() == HEADING }
        if (start < 0) {
            if (lines.isEmpty()) return content
            return content.trimEnd('\n') + "\n\n" + HEADING + "\n\n" + lines.joinToString("\n") + "\n"
        }
        val end = (start + 1 until all.size).firstOrNull { all[it].trimStart().startsWith("## ") || all[it].startsWith("# ") } ?: all.size
        val before = all.subList(0, start)
        val after = all.subList(end, all.size)
        if (after.isEmpty()) {
            // The section closes the note: the note ends right after it, or right after what preceded it.
            val head = before.joinToString("\n").trimEnd('\n')
            return if (lines.isEmpty()) "$head\n" else head + "\n\n" + HEADING + "\n\n" + lines.joinToString("\n") + "\n"
        }
        val section = if (lines.isEmpty()) emptyList() else listOf(HEADING, "") + lines + ""
        return (before + section + after).joinToString("\n")
    }

    fun gradeIcon(grade: String): String = when (grade.lowercase()) {
        "platinum" -> "🏆"
        "gold" -> "🥇"
        "silver" -> "🥈"
        else -> "🥉"
    }

    private fun earnedOn(trophy: TrophyRecord, zone: ZoneId): LocalDate? =
        trophy.earnedAt?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDate() }.getOrNull() }
}

/**
 * Trophy markers (D3, pure): a checklist line ending with `<!--trophy:<set>#<number>-->` waits for
 * that trophy. When it is earned the sync ticks the line and adds the date (` ✅ yyyy-MM-dd`, the
 * Obsidian Tasks done date); a ticked line is never unticked. In the app an unticked marked line
 * shows as ??? until the owner reveals the page's checklists.
 */
object TrophyMarkers {

    private val LINE = Regex("^(\\s*[-*+]\\s+\\[)([ xX])(\\]\\s+)(.*?)(\\s*<!--\\s*trophy:([^#\\s>]+)#(\\d+)\\s*-->\\s*)$")

    /** [content] with every waiting line whose trophy [earnedOn] dates ticked. */
    fun tick(content: String, earnedOn: (set: String, id: Int) -> LocalDate?): String =
        content.split("\n").joinToString("\n") { line ->
            val m = LINE.matchEntire(line.removeSuffix("\r")) ?: return@joinToString line
            if (m.groupValues[2] != " ") return@joinToString line
            val day = earnedOn(m.groupValues[6], m.groupValues[7].toInt()) ?: return@joinToString line
            val text = m.groupValues[4].trimEnd()
            val dated = if (text.contains("✅")) text else "$text ✅ $day"
            val cr = if (line.endsWith("\r")) "\r" else ""
            m.groupValues[1] + "x" + m.groupValues[3] + dated + m.groupValues[5] + cr
        }

    /** [content] with the text of every unticked marked line replaced by ???. */
    fun masked(content: String): String =
        content.split("\n").joinToString("\n") { line ->
            val m = LINE.matchEntire(line) ?: return@joinToString line
            if (m.groupValues[2] != " ") line else m.groupValues[1] + " " + m.groupValues[3] + "???" + m.groupValues[5]
        }

    /** True when [content] has a marked line still waiting: the page offers "Reveal". */
    fun hasWaiting(content: String): Boolean =
        content.split("\n").any { line -> LINE.matchEntire(line)?.groupValues?.get(2) == " " }
}
