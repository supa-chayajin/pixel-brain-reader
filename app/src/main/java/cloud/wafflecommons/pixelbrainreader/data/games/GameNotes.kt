package cloud.wafflecommons.pixelbrainreader.data.games

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The derived sections' shared edit (pure): one heading matched whole, its lines replaced in place;
 * everything above it, and any section after it, stays as written. A section that isn't there yet
 * goes right after the section [apply]'s `after` finds, when another section follows that one;
 * otherwise it is appended at the end of the note.
 */
internal object PageSections {

    fun apply(
        content: String,
        heading: String,
        isHeading: (trimmedLine: String) -> Boolean,
        lines: List<String>,
        after: ((trimmedLine: String) -> Boolean)? = null
    ): String {
        val all = content.split("\n")
        val start = all.indexOfFirst { isHeading(it.trim()) }
        if (start < 0) {
            if (lines.isEmpty()) return content
            val anchor = if (after == null) -1 else all.indexOfFirst { after(it.trim()) }
            if (anchor >= 0) {
                val end = sectionEnd(all, anchor)
                if (end < all.size) {
                    // Between the anchor's section and the owner's next one, a blank line before the heading.
                    val before = all.subList(0, end)
                    val gap = if (before.last().isBlank()) emptyList() else listOf("")
                    return (before + gap + listOf(heading, "") + lines + "" + all.subList(end, all.size)).joinToString("\n")
                }
            }
            return content.trimEnd('\n') + "\n\n" + heading + "\n\n" + lines.joinToString("\n") + "\n"
        }
        val end = sectionEnd(all, start)
        val before = all.subList(0, start)
        val rest = all.subList(end, all.size)
        if (rest.isEmpty()) {
            // The section closes the note: the note ends right after it, or right after what preceded it.
            val head = before.joinToString("\n").trimEnd('\n')
            return if (lines.isEmpty()) "$head\n" else head + "\n\n" + heading + "\n\n" + lines.joinToString("\n") + "\n"
        }
        val section = if (lines.isEmpty()) emptyList() else listOf(heading, "") + lines + ""
        return (before + section + rest).joinToString("\n")
    }

    /** The first line after [start] that opens another section (`## ` after spaces, or `# `); the end otherwise. */
    private fun sectionEnd(all: List<String>, start: Int): Int =
        (start + 1 until all.size).firstOrNull { all[it].trimStart().startsWith("## ") || all[it].startsWith("# ") } ?: all.size
}

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
    fun apply(content: String, lines: List<String>): String = PageSections.apply(content, HEADING, { it == HEADING }, lines)

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

/**
 * The `## 🎖️ Achievements` section of a game page (13.3, XB2, pure): Xbox's own section beside 🏆,
 * rewritten at each sync with the EARNED achievements only, dated, with their gamerscore. The
 * heading is matched whole, with or without the emoji's variation selector (an editor may drop it),
 * and written with it. A page without the section gets it right after its 🏆 section when another
 * section follows that one, else at the end.
 */
object AchievementSection {

    /** U+1F396 U+FE0F written as escapes, so no editor drops the variation selector from the source. */
    const val HEADING = "## \uD83C\uDF96\uFE0F Achievements"
    const val ICON = "\uD83C\uDF96\uFE0F"

    /** [HEADING] without U+FE0F. */
    private const val BARE_HEADING = "## \uD83C\uDF96 Achievements"

    fun isHeading(trimmedLine: String): Boolean = trimmedLine == HEADING || trimmedLine == BARE_HEADING

    /**
     * The section's lines for the page's titles: `*N of M achievements · G of T G · P %*`, then one
     * line per earned achievement by date (the title named when the page holds more than one); empty
     * when no file holds an achievement.
     */
    fun lines(files: List<AchievementSetFile>, zone: ZoneId): List<String> {
        val sets = files.filter { it.achievements.isNotEmpty() }
        if (sets.isEmpty()) return emptyList()
        val total = sets.sumOf { it.achievements.size }
        val totalScore = sets.sumOf { it.totalGamerscore }
        val earned = sets.flatMap { set -> set.achievements.mapNotNull { a -> earnedOn(a, zone)?.let { Triple(set, a, it) } } }
        val score = earned.sumOf { it.second.gamerscore }
        val percent = if (total == 0) 0 else earned.size * 100 / total
        val summary = "*${earned.size} of $total achievements · $score of $totalScore G · $percent %*"
        val named = sets.size > 1
        val sorted = earned.sortedWith(
            compareBy({ it.third }, { it.second.id.toIntOrNull() ?: Int.MAX_VALUE }, { it.second.id })
        )
        return listOf(summary, "") + sorted.map { (set, achievement, day) ->
            val where = if (named && set.title.isNotBlank()) " · ${set.title}" else ""
            "- $ICON ${achievement.name.ifBlank { "Achievement ${achievement.id}" }} — $day · ${achievement.gamerscore} G$where"
        }
    }

    /** [content] with its section replaced by [lines] (placed when absent, removed when [lines] is empty). */
    fun apply(content: String, lines: List<String>): String =
        PageSections.apply(content, HEADING, ::isHeading, lines, after = { it == TrophySection.HEADING })

    private fun earnedOn(achievement: AchievementRecord, zone: ZoneId): LocalDate? =
        achievement.earnedAt?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDate() }.getOrNull() }
}

/**
 * Achievement markers (13.3, pure), like [TrophyMarkers]: a checklist line ending with
 * `<!--achievement:<title id>#<id>-->` waits for that Xbox achievement. When it is earned the sync
 * ticks the line and adds ` ✅ yyyy-MM-dd` before the marker; a ticked line is never unticked, and a
 * line already dated isn't dated twice. In the app an unticked marked line shows as ??? until
 * revealed.
 */
object AchievementMarkers {

    private val LINE = Regex(
        "^(\\s*[-*+]\\s+\\[)([ xX])(\\]\\s+)(.*?)(\\s*<!--\\s*achievement:([0-9]+)#([A-Za-z0-9_]+)\\s*-->\\s*)$"
    )

    /** [content] with every waiting line whose achievement [earnedOn] dates ticked. */
    fun tick(content: String, earnedOn: (titleId: String, id: String) -> LocalDate?): String =
        content.split("\n").joinToString("\n") { line ->
            val m = LINE.matchEntire(line.removeSuffix("\r")) ?: return@joinToString line
            if (m.groupValues[2] != " ") return@joinToString line
            val day = earnedOn(m.groupValues[6], m.groupValues[7]) ?: return@joinToString line
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
