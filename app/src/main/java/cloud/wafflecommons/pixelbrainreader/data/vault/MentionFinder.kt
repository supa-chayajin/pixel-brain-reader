package cloud.wafflecommons.pixelbrainreader.data.vault

import java.text.Normalizer

/**
 * Unlinked mentions (E2): a note's name written in another note's text but not as a link. Matched
 * as a whole word, case and accents aside, outside `[[links]]`, inline code and code fences.
 */
object MentionFinder {

    /** Names too short to mean a note. */
    private const val MIN_NAME = 4

    /** The first line of [content] that mentions one of [names] without linking it, trimmed; null when none does. */
    fun firstMention(content: String, names: Collection<String>): String? =
        mentionLine(content, names)?.let { content.lines()[it].trim() }

    /**
     * [content] with the first unlinked mention of [name] turned into `[[name]]` (the text keeps its
     * own spelling as the alias when it differs), or null when there is none.
     */
    fun link(content: String, name: String): String? {
        val lines = content.lines().toMutableList()
        val index = mentionLine(content, listOf(name)) ?: return null
        val line = lines[index]
        val at = plainOccurrence(line, fold(name)) ?: return null
        val written = line.substring(at, at + name.length)
        val link = if (written == name) "[[$name]]" else "[[$name|$written]]"
        lines[index] = line.substring(0, at) + link + line.substring(at + name.length)
        return lines.joinToString("\n")
    }

    private fun mentionLine(content: String, names: Collection<String>): Int? {
        val folded = names.map { it.trim() }.filter { it.length >= MIN_NAME }.map(::fold).distinct()
        if (folded.isEmpty()) return null
        var inFence = false
        var inFrontmatter = false
        content.lines().forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (index == 0 && trimmed == "---") {
                inFrontmatter = true
                return@forEachIndexed
            }
            if (inFrontmatter) {
                if (trimmed == "---") inFrontmatter = false
                return@forEachIndexed
            }
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence
                return@forEachIndexed
            }
            if (inFence) return@forEachIndexed
            if (folded.any { plainOccurrence(line, it) != null }) return index
        }
        return null
    }

    /** Index of [foldedName] in [line] as a whole word, outside links and inline code; null when absent. */
    private fun plainOccurrence(line: String, foldedName: String): Int? {
        val folded = fold(line)
        val masked = maskLinksAndCode(folded)
        var from = 0
        while (true) {
            val at = masked.indexOf(foldedName, from)
            if (at < 0) return null
            val end = at + foldedName.length
            val beforeOk = at == 0 || !masked[at - 1].isLetterOrDigit()
            val afterOk = end >= masked.length || !masked[end].isLetterOrDigit()
            if (beforeOk && afterOk) return at
            from = at + 1
        }
    }

    /** Same length as [text], with `[[…]]`, `[…](…)` and `` `…` `` spans blanked out. */
    private fun maskLinksAndCode(text: String): String {
        val chars = text.toCharArray()
        fun blank(from: Int, to: Int) { for (i in from until to.coerceAtMost(chars.size)) chars[i] = '\u0000' }
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("[[", i) -> {
                    val end = text.indexOf("]]", i + 2).let { if (it < 0) text.length else it + 2 }
                    blank(i, end)
                    i = end
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1).let { if (it < 0) text.length else it + 1 }
                    blank(i, end)
                    i = end
                }
                text[i] == '[' && text.indexOf("](", i) > i -> {
                    val close = text.indexOf(')', text.indexOf("](", i)).let { if (it < 0) text.length else it + 1 }
                    blank(i, close)
                    i = close
                }
                else -> i++
            }
        }
        return String(chars)
    }

    /** Lower case without accents, one char in, one char out (offsets stay valid). */
    private fun fold(text: String): String = String(CharArray(text.length) { i ->
        val c = text[i]
        if (c.code < 128) c.lowercaseChar() else Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0].lowercaseChar()
    })
}
