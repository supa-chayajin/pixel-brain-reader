package cloud.wafflecommons.pixelbrainreader.data.games

/** One checklist line of a game page, as the app shows it. [line] is the exact line, for the tick. */
data class ChecklistItem(val line: String, val text: String, val done: Boolean, val waitsForTrophy: Boolean)

/** The checklist lines under one heading of a game page. */
data class ChecklistSection(val heading: String, val items: List<ChecklistItem>)

/**
 * A game page's checklists (RFC-009 §4.2, pure): the `- [ ]` / `- [x]` lines under each heading, the
 * frontmatter and the derived 🏆 section left out. A tick flips one exact line, read from disk first.
 */
object GameChecklists {

    private val ITEM = Regex("^\\s*[-*+]\\s+\\[([ xX])\\]\\s+(.*)$")
    private val MARKER = Regex("\\s*<!--\\s*trophy:[^>]*-->\\s*$")

    fun sections(content: String): List<ChecklistSection> {
        val lines = bodyLines(content)
        val out = mutableListOf<ChecklistSection>()
        var heading = ""
        var items = mutableListOf<ChecklistItem>()
        var inTrophies = false
        fun flush() {
            if (items.isNotEmpty()) out += ChecklistSection(heading, items)
            items = mutableListOf()
        }
        lines.forEach { raw ->
            val line = raw.trimEnd('\r')
            if (line.startsWith("#")) {
                flush()
                inTrophies = line.trim() == TrophySection.HEADING
                heading = line.trimStart('#').trim()
                return@forEach
            }
            if (inTrophies) return@forEach
            val m = ITEM.matchEntire(line) ?: return@forEach
            val text = m.groupValues[2]
            items += ChecklistItem(
                line = line,
                text = MARKER.replace(text, "").trim(),
                done = m.groupValues[1] != " ",
                waitsForTrophy = MARKER.containsMatchIn(text)
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
