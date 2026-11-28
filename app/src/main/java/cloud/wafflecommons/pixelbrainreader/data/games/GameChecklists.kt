package cloud.wafflecommons.pixelbrainreader.data.games

/**
 * One checklist line of a game page, as the app shows it. [line] is the exact line, for the tick.
 * [waitsForTrophy]: the line waits for a console award, a trophy or (13.3) an achievement.
 * [spoiler]: the line ends with `<!--spoiler-->` (13.1, the owner's call): like a trophy's line, the
 * app shows it as ??? until tapped or ticked, without waiting for PSN. [index] (13.4): the line's
 * index in the page's `content.split("\n")`, the frontmatter counted, so a tick flips this copy of a
 * line that appears twice; -1 when unknown.
 */
data class ChecklistItem(
    val line: String,
    val text: String,
    val done: Boolean,
    val waitsForTrophy: Boolean,
    val spoiler: Boolean = false,
    val index: Int = -1
) {
    /** Shown as ??? until revealed: a trophy's line or a spoiler, while unticked. */
    val masked: Boolean get() = (waitsForTrophy || spoiler) && !done
}

/** The checklist lines under one heading of a game page. */
data class ChecklistSection(val heading: String, val items: List<ChecklistItem>)

/**
 * A game page's checklists (RFC-009 §4.2, pure): the `- [ ]` / `- [x]` lines under each heading, the
 * frontmatter and the derived 🏆 and 🎖️ sections' own lines left out; a checklist line the owner
 * wrote inside one of those sections is an item under its heading (13.4: the sync keeps it). A tick
 * flips one line, read from disk first: since 13.4 the line at its index, when it is still the one
 * shown.
 */
object GameChecklists {

    private val ITEM = Regex("^\\s*[-*+]\\s+\\[([ xX])\\]\\s+(.*)$")
    private val MARKER = Regex("\\s*<!--\\s*(?:trophy|achievement):[^>]*-->\\s*$")
    private val SPOILER = Regex("\\s*<!--\\s*spoiler\\s*-->\\s*$")

    fun sections(content: String): List<ChecklistSection> {
        val all = content.split("\n")
        val out = mutableListOf<ChecklistSection>()
        var heading = ""
        var items = mutableListOf<ChecklistItem>()
        var derived: ((String) -> Boolean)? = null
        fun flush() {
            if (items.isNotEmpty()) out += ChecklistSection(heading, items)
            items = mutableListOf()
        }
        for (index in bodyStart(all) until all.size) {
            val line = all[index].trimEnd('\r')
            val trimmed = line.trim()
            if (isHeadingLine(line)) {
                flush()
                derived = when {
                    trimmed == TrophySection.HEADING -> TrophySection::isDerivedLine
                    AchievementSection.isHeading(trimmed) -> AchievementSection::isDerivedLine
                    else -> null
                }
                heading = trimmed.trimStart('#').trim()
                continue
            }
            val owns = derived
            if (owns != null && (trimmed.isEmpty() || owns(trimmed))) continue
            val m = ITEM.matchEntire(line) ?: continue
            val text = m.groupValues[2]
            items += ChecklistItem(
                line = line,
                text = SPOILER.replace(MARKER.replace(text, ""), "").trim(),
                done = m.groupValues[1] != " ",
                waitsForTrophy = MARKER.containsMatchIn(text),
                spoiler = SPOILER.containsMatchIn(text),
                index = index
            )
        }
        flush()
        return out
    }

    /**
     * [content] with the checklist line at [index] ticked or unticked (13.4), only while it still
     * reads [line] (without its `\r`); null when the line moved or changed meanwhile.
     */
    fun toggle(content: String, index: Int, line: String): String? {
        val all = content.split("\n").toMutableList()
        if (index !in all.indices) return null
        val current = all[index]
        if (current.trimEnd('\r') != line) return null
        return flipped(line)?.let { flip ->
            all[index] = flip + if (current.endsWith("\r")) "\r" else ""
            all.joinToString("\n")
        }
    }

    /** [line] ticked when open, unticked when ticked; null when it isn't a checklist line. */
    private fun flipped(line: String): String? {
        val m = ITEM.matchEntire(line) ?: return null
        return if (m.groupValues[1] == " ") line.replaceFirst("[ ]", "[x]") else line.replaceFirst(Regex("\\[[xX]\\]"), "[ ]")
    }

    /** The index of the first line after the frontmatter (0 without one). */
    private fun bodyStart(lines: List<String>): Int {
        if (lines.firstOrNull()?.trimEnd('\r') != "---") return 0
        val close = (1 until lines.size).firstOrNull { lines[it].trimEnd('\r') == "---" } ?: return 0
        return close + 1
    }
}
