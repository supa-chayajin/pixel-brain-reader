package cloud.wafflecommons.pixelbrainreader.data.games

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The characters text from the console servers loses before it reaches the vault (pure, 13.4): the
 * bidi marks, embeddings, overrides and isolates (U+202A to U+202E, U+2066 to U+2069, U+200E,
 * U+200F, U+061C, the set [GameMaps.cleanLabel] drops), the zero-width space U+200B, the word joiner
 * U+2060, the byte order mark U+FEFF and the soft hyphen U+00AD. A right-to-left override turns the
 * rest of a line around (a Timeline line's length then reads backwards), and a zero-width space
 * splits a word the owner's page spells whole. The joiners U+200D and U+200C and the tag characters
 * (U+E0020 to U+E007F, written as surrogates: an emoji flag's) carry meaning and are kept.
 */
internal fun isInvisibleFormat(ch: Char): Boolean =
    ch in '\u202A'..'\u202E' || ch in '\u2066'..'\u2069' || ch == '\u200E' || ch == '\u200F' || ch == '\u061C' ||
        ch == '\u200B' || ch == '\u2060' || ch == '\uFEFF' || ch == '\u00AD'

/**
 * Text from the console servers (a trophy's or an achievement's name, a set's or a game's title) as
 * one line of a note (pure, 13.3): the invisible formatting characters of [isInvisibleFormat]
 * dropped (13.4), whitespace runs, line breaks included, made one space and trimmed, control
 * characters dropped, everything else kept (™, ® and © too). A line break in a name would end a
 * derived section early or break a page's frontmatter.
 */
internal fun oneLine(text: String): String = buildString {
    var space = false
    for (ch in text) {
        when {
            isInvisibleFormat(ch) -> Unit
            ch.isWhitespace() -> space = true
            ch.isISOControl() -> Unit
            else -> {
                if (space && isNotEmpty()) append(' ')
                space = false
                append(ch)
            }
        }
    }
}

/**
 * A Markdown heading line (pure, 13.4): after its `\r` and its leading spaces, one to six `#` and a
 * space. Shared by the derived sections, the game page's prose and its checklists, so the three agree
 * on where a section ends.
 */
internal fun isHeadingLine(line: String): Boolean {
    val text = line.trimEnd('\r').trimStart()
    val hashes = text.takeWhile { it == '#' }.length
    return hashes in 1..6 && text.length > hashes && text[hashes] == ' '
}

/**
 * The derived sections' shared edit (pure): one heading matched whole, and only the lines the
 * section writes itself replaced (13.4). A section ends at the next heading of any level; inside it
 * a line the section doesn't own ([apply]'s `owns`: its summary and entry shapes) is the owner's, and
 * stays, in its order, right after the rewritten lines. Everything above the heading, and every
 * section after it, stays as written. A section that isn't there yet goes right before the line
 * `before` finds, else right after the section `after` finds when another section follows that one,
 * else at the end of the note.
 */
internal object PageSections {

    fun apply(
        content: String,
        heading: String,
        isHeading: (trimmedLine: String) -> Boolean,
        lines: List<String>,
        owns: (trimmedLine: String) -> Boolean,
        after: ((trimmedLine: String) -> Boolean)? = null,
        before: ((trimmedLine: String) -> Boolean)? = null
    ): String {
        val all = content.split("\n")
        val start = all.indexOfFirst { isHeading(it.trimEnd('\r').trim()) }
        if (start < 0) return placed(content, all, heading, lines, after, before)

        val end = (start + 1 until all.size).firstOrNull { isHeadingLine(all[it]) } ?: all.size
        val body = all.subList(start + 1, end)
        fun owned(line: String): Boolean = line.isBlank() || owns(line.trimEnd('\r').trim())
        val first = body.indexOfFirst { !owned(it) }
        // The owner's lines, from the first to the last; the section's own lines among them go (they
        // are rewritten above), the blank lines between two of the owner's stay as written.
        val foreign = if (first < 0) {
            emptyList()
        } else {
            body.subList(first, body.indexOfLast { !owned(it) } + 1).filter { it.isBlank() || !owned(it) }
        }
        val rest = all.subList(end, all.size)
        val above = all.subList(0, start)
        if (rest.isEmpty()) {
            // The section closes the note: the note ends right after it (and the owner's lines kept).
            val head = above.joinToString("\n").trimEnd('\n')
            return when {
                lines.isEmpty() && foreign.isEmpty() -> "$head\n"
                lines.isEmpty() -> head + "\n\n" + foreign.joinToString("\n") + "\n"
                else -> head + "\n\n" + heading + "\n\n" + lines.joinToString("\n") +
                    (if (foreign.isEmpty()) "" else "\n\n" + foreign.joinToString("\n")) + "\n"
            }
        }
        val section = if (lines.isEmpty()) emptyList() else listOf(heading, "") + lines + ""
        val kept = if (foreign.isEmpty()) emptyList() else foreign + ""
        return (above + section + kept + rest).joinToString("\n")
    }

    /** A section that isn't there yet: before [before]'s line, after [after]'s section, or at the end. */
    private fun placed(
        content: String,
        all: List<String>,
        heading: String,
        lines: List<String>,
        after: ((trimmedLine: String) -> Boolean)?,
        before: ((trimmedLine: String) -> Boolean)?
    ): String {
        if (lines.isEmpty()) return content
        val next = if (before == null) -1 else all.indexOfFirst { before(it.trimEnd('\r').trim()) }
        if (next >= 0) {
            val head = all.subList(0, next)
            val gap = if (head.isNotEmpty() && head.last().isNotBlank()) listOf("") else emptyList()
            return (head + gap + listOf(heading, "") + lines + "" + all.subList(next, all.size)).joinToString("\n")
        }
        val anchor = if (after == null) -1 else all.indexOfFirst { after(it.trimEnd('\r').trim()) }
        if (anchor >= 0) {
            val end = anchorEnd(all, anchor)
            if (end < all.size) {
                // Between the anchor's section and the owner's next one, a blank line before the heading.
                val head = all.subList(0, end)
                val gap = if (head.last().isBlank()) emptyList() else listOf("")
                return (head + gap + listOf(heading, "") + lines + "" + all.subList(end, all.size)).joinToString("\n")
            }
        }
        return content.trimEnd('\n') + "\n\n" + heading + "\n\n" + lines.joinToString("\n") + "\n"
    }

    /** Where the anchor's section ends, as 13.3 placed a section after it: the next `## ` (after spaces) or `# ` line. */
    private fun anchorEnd(all: List<String>, start: Int): Int =
        (start + 1 until all.size).firstOrNull { all[it].trimStart().startsWith("## ") || all[it].startsWith("# ") } ?: all.size
}

/**
 * The `## 🏆 Trophies` section of a game page (D12, pure): rewritten at each sync like a daily
 * note's 🩺 Health, with the EARNED trophies only, dated, so the note shows no spoiler in Obsidian.
 * The heading is matched whole; only the section's own lines ([isDerivedLine]) are rewritten, and
 * any other line the owner writes in it, or below it, stays (13.4). Everything above it, and any
 * section after it, stays as written.
 */
object TrophySection {

    const val HEADING = "## 🏆 Trophies"

    private val SUMMARY = Regex("^\\*[0-9]+ of [0-9]+ trophies · [0-9]+ %\\*$")
    private val ENTRY_END = Regex(" — [0-9]{4}-[0-9]{2}-[0-9]{2}( · .*)?$")
    private val ENTRY_STARTS = listOf("- 🏆 ", "- 🥇 ", "- 🥈 ", "- 🥉 ")

    /** The section's lines for the page's sets, names and titles made [oneLine]; empty when the page has no set. */
    fun lines(sets: List<TrophySetFile>, zone: ZoneId): List<String> {
        if (sets.isEmpty()) return emptyList()
        val total = sets.sumOf { it.trophies.size }
        val earned = sets.flatMap { set -> set.trophies.mapNotNull { t -> earnedOn(t, zone)?.let { Triple(set, t, it) } } }
        val percent = if (total == 0) 0 else earned.size * 100 / total
        val summary = "*${earned.size} of $total trophies · $percent %*"
        val named = sets.size > 1
        return listOf(summary, "") + earned.sortedWith(compareBy({ it.third }, { it.second.id })).map { (set, trophy, day) ->
            val title = if (named) oneLine(set.title) else ""
            val where = if (title.isNotEmpty()) " · $title" else ""
            "- ${gradeIcon(trophy.grade)} ${oneLine(trophy.name).ifBlank { "Trophy ${trophy.id}" }} — $day$where"
        }
    }

    /**
     * A line the section writes itself (13.4), [trimmed]: its summary `*N of M trophies · P %*`, or an
     * earned trophy `- <grade icon> <name> — yyyy-MM-dd`, with ` · <set>` when the page has several.
     */
    fun isDerivedLine(trimmed: String): Boolean =
        SUMMARY.matches(trimmed) || (ENTRY_STARTS.any { trimmed.startsWith(it) } && ENTRY_END.containsMatchIn(trimmed))

    /**
     * [content] with its section's own lines replaced by [lines] (placed right before an existing 🎖️
     * section, else appended, when absent; removed when [lines] is empty), the owner's lines kept.
     */
    fun apply(content: String, lines: List<String>): String =
        PageSections.apply(content, HEADING, { it == HEADING }, lines, owns = ::isDerivedLine, before = AchievementSection::isHeading)

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
 * Obsidian Tasks done date); a ticked line is never unticked, and a number too large to be a trophy's
 * leaves its line as written (13.4). In the app an unticked marked line shows as ??? until the owner
 * reveals the page's checklists.
 */
object TrophyMarkers {

    private val LINE = Regex("^(\\s*[-*+]\\s+\\[)([ xX])(\\]\\s+)(.*?)(\\s*<!--\\s*trophy:([^#\\s>]+)#([0-9]+)\\s*-->\\s*)$")

    /** [content] with every waiting line whose trophy [earnedOn] dates ticked. */
    fun tick(content: String, earnedOn: (set: String, id: Int) -> LocalDate?): String =
        content.split("\n").joinToString("\n") { line ->
            val m = LINE.matchEntire(line.removeSuffix("\r")) ?: return@joinToString line
            if (m.groupValues[2] != " ") return@joinToString line
            val number = m.groupValues[7].toIntOrNull() ?: return@joinToString line
            val day = earnedOn(m.groupValues[6], number) ?: return@joinToString line
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
 * and written with it. Only the section's own lines ([isDerivedLine]) are rewritten; the owner's
 * stay (13.4). A page without the section gets it right after its 🏆 section when another section
 * follows that one, else at the end.
 */
object AchievementSection {

    /** U+1F396 U+FE0F written as escapes, so no editor drops the variation selector from the source. */
    const val HEADING = "## 🎖️ Achievements"
    const val ICON = "🎖️"

    /** [HEADING] without U+FE0F. */
    private const val BARE_HEADING = "## 🎖 Achievements"

    /** An entry's start with [ICON], and without its variation selector. */
    private const val ENTRY_START = "- $ICON "
    private const val BARE_ENTRY_START = "- 🎖 "

    private val SUMMARY = Regex("^\\*[0-9]+ of [0-9]+ achievements · [0-9]+ of [0-9]+ G · [0-9]+ %\\*$")
    private val ENTRY_END = Regex(" — [0-9]{4}-[0-9]{2}-[0-9]{2} · [0-9]+ G( · .*)?$")

    fun isHeading(trimmedLine: String): Boolean = trimmedLine == HEADING || trimmedLine == BARE_HEADING

    /**
     * The section's lines for the page's titles: `*N of M achievements · G of T G · P %*`, then one
     * line per earned achievement by date (the title named when the page holds more than one), names
     * and titles made [oneLine]; empty when no file holds an achievement.
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
            val title = if (named) oneLine(set.title) else ""
            val where = if (title.isNotEmpty()) " · $title" else ""
            val name = oneLine(achievement.name).ifBlank { "Achievement ${oneLine(achievement.id)}" }
            "- $ICON $name — $day · ${achievement.gamerscore} G$where"
        }
    }

    /**
     * A line the section writes itself (13.4), [trimmed]: its summary, or an earned achievement
     * `- 🎖️ <name> — yyyy-MM-dd · N G`, with ` · <title>` when the page holds several (the icon with
     * or without its variation selector).
     */
    fun isDerivedLine(trimmed: String): Boolean =
        SUMMARY.matches(trimmed) ||
            ((trimmed.startsWith(ENTRY_START) || trimmed.startsWith(BARE_ENTRY_START)) && ENTRY_END.containsMatchIn(trimmed))

    /** [content] with its section's own lines replaced by [lines] (placed when absent, removed when [lines] is empty), the owner's lines kept. */
    fun apply(content: String, lines: List<String>): String =
        PageSections.apply(content, HEADING, ::isHeading, lines, owns = ::isDerivedLine, after = { it == TrophySection.HEADING })

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
