package cloud.wafflecommons.pixelbrainreader.data.games

/**
 * One checklist line of a game page, as the app shows it. [line] is the exact line, for the tick.
 * [waitsForTrophy]: the line waits for a console award, a trophy or (13.3) an achievement.
 * [spoiler]: the line ends with `<!--spoiler-->` (13.1, the owner's call): like a trophy's line, the
 * app shows it as ??? until tapped or ticked, without waiting for PSN.
 */
data class ChecklistItem(val line: String, val text: String, val done: Boolean, val waitsForTrophy: Boolean, val spoiler: Boolean = false) {
    /** Shown as ??? until revealed: a trophy's line or a spoiler, while unticked. */
    val masked: Boolean get() = (waitsForTrophy || spoiler) && !done
}

/** The checklist lines under one heading of a game page. */
data class ChecklistSection(val heading: String, val items: List<ChecklistItem>)

/**
 * A game page's checklists (RFC-009 §4.2, pure): the `- [ ]` / `- [x]` lines under each heading, the
 * frontmatter and the derived 🏆 and 🎖️ sections left out. A tick flips one exact line, read from
 * disk first.
 */
object GameChecklists {

    private val ITEM = Regex("^\\s*[-*+]\\s+\\[([ xX])\\]\\s+(.*)$")
    private val MARKER = Regex("\\s*<!--\\s*(?:trophy|achievement):[^>]*-->\\s*$")
    private val SPOILER = Regex("\\s*<!--\\s*spoiler\\s*-->\\s*$")

    fun sections(content: String): List<ChecklistSection> {
        val lines = bodyLines(content)
        val out = mutableListOf<ChecklistSection>()
        var heading = ""
        var items = mutableListOf<ChecklistItem>()
        var inDerived = false
        fun flush() {
            if (items.isNotEmpty()) out += ChecklistSection(heading, items)
            items = mutableListOf()
        }
        lines.forEach { raw ->
            val line = raw.trimEnd('\r')
            if (line.startsWith("#")) {
                flush()
                inDerived = line.trim() == TrophySection.HEADING || AchievementSection.isHeading(line.trim())
                heading = line.trimStart('#').trim()
                return@forEach
            }
            if (inDerived) return@forEach
            val m = ITEM.matchEntire(line) ?: return@forEach
            val text = m.groupValues[2]
            items += ChecklistItem(
                line = line,
                text = SPOILER.replace(MARKER.replace(text, ""), "").trim(),
                done = m.groupValues[1] != " ",
                waitsForTrophy = MARKER.containsMatchIn(text),
                spoiler = SPOILER.containsMatchIn(text)
            )
        }
        flush()
        return out
    }

    /** [content] with [line] (an exact checklist line) ticked or unticked; null when the line isn't there. */
    fun toggle(content: String, line: String): String? {
        val all = content.split("\n").toMutableList()
        val index = all.indexOfFirst { it.trimEnd('\r') == line }
        if (index < 0) return null
        val cr = if (all[index].endsWith("\r")) "\r" else ""
        val m = ITEM.matchEntire(line) ?: return null
        val flipped = if (m.groupValues[1] == " ") line.replaceFirst("[ ]", "[x]") else line.replaceFirst(Regex("\\[[xX]\\]"), "[ ]")
        all[index] = flipped + cr
        return all.joinToString("\n")
    }

    private fun bodyLines(content: String): List<String> {
        val lines = content.split("\n")
        if (lines.firstOrNull()?.trimEnd('\r') != "---") return lines
        val close = (1 until lines.size).firstOrNull { lines[it].trimEnd('\r') == "---" } ?: return lines
        return lines.drop(close + 1)
    }
}
