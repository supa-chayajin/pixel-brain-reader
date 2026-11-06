package cloud.wafflecommons.pixelbrainreader.data.editor

/** A `#`–`###` heading that starts a section of a note. */
data class NoteHeading(val level: Int, val text: String)

/** A piece of a note as the reader shows it. */
sealed interface NoteSegment {
    /** Plain markdown, rendered as is; [heading] when the segment starts with one. */
    data class Markdown(val text: String, val heading: NoteHeading? = null) : NoteSegment

    /** A GFM table, its own view so a wide one can scroll sideways. */
    data class Table(val markdown: String, val columns: Int) : NoteSegment

    /** A ```mermaid block: drawn by the bundled Mermaid, offline, on demand. */
    data class Mermaid(val source: String) : NoteSegment
}

/**
 * Splits a note (frontmatter already stripped) for the reader of long notes: a new segment at each
 * `#`–`###` heading, so the outline can jump to it, each table apart, and each closed ```mermaid
 * block as a diagram. Other fenced code is never split, and nothing inside it counts as a heading
 * or a table. Pure, line by line.
 */
object NoteSegments {

    const val OUTLINE_LEVELS = 3
    // A closing run of #s needs a space before it: "# C#" is the heading "C#".
    private val HEADING = Regex("""^(#{1,6}) +(.+?)(?:\s+#+)?\s*$""")
    private val DELIMITER_CELL = Regex("""^:?-+:?$""")

    fun split(markdown: String): List<NoteSegment> {
        val out = mutableListOf<NoteSegment>()
        val buffer = StringBuilder()
        var heading: NoteHeading? = null
        fun flush() {
            if (buffer.isNotBlank()) out += NoteSegment.Markdown(buffer.toString().trimEnd('\n'), heading)
            buffer.clear()
            heading = null
        }

        val lines = markdown.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = fenceOf(line)
            if (fence != null) {
                var end = i + 1
                while (end < lines.size && !closes(lines[end], fence)) end++
                if (fence.info.substringBefore(' ').equals("mermaid", ignoreCase = true) && end < lines.size) {
                    flush()
                    out += NoteSegment.Mermaid(lines.subList(i + 1, end).joinToString("\n"))
                } else {
                    for (k in i..minOf(end, lines.lastIndex)) buffer.append(lines[k]).append('\n')
                }
                i = end + 1
                continue
            }
            val h = headingOf(line)
            if (h != null && h.level <= OUTLINE_LEVELS) {
                flush()
                heading = h
                buffer.append(line).append('\n')
                i++
                continue
            }
            val columns = if (i + 1 < lines.size && line.contains('|')) delimiterColumns(lines[i + 1]) else null
            if (columns != null) {
                flush()
                var end = i + 2
                while (end < lines.size && lines[end].isNotBlank() && lines[end].contains('|') && fenceOf(lines[end]) == null) end++
                out += NoteSegment.Table(lines.subList(i, end).joinToString("\n"), columns)
                i = end
                continue
            }
            buffer.append(line).append('\n')
            i++
        }
        flush()
        return out
    }

    /** The headings the outline offers, with the index of the segment each one starts. */
    fun outline(segments: List<NoteSegment>): List<Pair<Int, NoteHeading>> =
        segments.withIndex().mapNotNull { (index, s) -> (s as? NoteSegment.Markdown)?.heading?.let { index to it } }

    fun headingOf(line: String): NoteHeading? =
        HEADING.matchEntire(line)?.let { NoteHeading(it.groupValues[1].length, it.groupValues[2].trim()) }

    /** A GFM delimiter row (`| --- | :-: |`): its number of columns, else null. */
    internal fun delimiterColumns(line: String): Int? {
        val trimmed = line.trim()
        // Pipes required: a lone "---" under a line is a thematic break or a setext heading.
        if (!trimmed.contains('|')) return null
        val cells = trimmed.removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
        if (cells.isEmpty() || cells.any { !DELIMITER_CELL.matches(it) }) return null
        return cells.size
    }

    /** A fence opener: its character and length (``` or ~~~, three or more). */
    private class Fence(val char: Char, val length: Int, val info: String)

    private fun fenceOf(line: String): Fence? {
        val trimmed = line.trimStart()
        val char = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
        val length = trimmed.takeWhile { it == char }.length
        if (length < 3) return null
        return Fence(char, length, trimmed.drop(length).trim())
    }

    private fun closes(line: String, fence: Fence): Boolean {
        val trimmed = line.trim()
        return trimmed.length >= fence.length && trimmed.all { it == fence.char }
    }
}
