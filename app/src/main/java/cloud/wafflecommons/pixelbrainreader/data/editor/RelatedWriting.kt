package cloud.wafflecommons.pixelbrainreader.data.editor

/**
 * Related while writing (K5, 12.20): the paragraph the caret is in, as the question for the notes
 * closest to it, and the `[[link]]` a tap on one of them leaves at the caret (the owner's D8). Pure.
 */
object RelatedWriting {

    /** A paragraph shorter than this says too little to find anything close to it. */
    const val MIN_CHARS = 40

    /** The question keeps the end of a long paragraph: what is being written now. */
    const val MAX_CHARS = 1_000

    /**
     * The paragraph around [caret], between the blank lines before and after it, its heading lines
     * left out; null in the frontmatter or when what is left is shorter than [MIN_CHARS].
     */
    fun paragraphAt(text: String, caret: Int): String? {
        val at = caret.coerceIn(0, text.length)
        // A paragraph never starts before the body: the frontmatter is the note's data, not its text.
        val bodyStart = if (text.startsWith("---")) {
            val close = text.indexOf("\n---", startIndex = 3)
            if (close < 0) return null
            (close + 4).let { if (it < text.length && text[it] == '\n') it + 1 else it }
        } else {
            0
        }
        if (at < bodyStart) return null
        val start = maxOf(bodyStart, if (at == 0) 0 else text.lastIndexOf("\n\n", at - 1).let { if (it < 0) 0 else it + 2 })
        val end = text.indexOf("\n\n", at).let { if (it < 0) text.length else it }
        if (start >= end) return null
        val body = text.substring(start, end)
            .lines()
            .filterNot { it.trimStart().startsWith("#") }
            .joinToString("\n")
            .trim()
        return body.takeIf { it.length >= MIN_CHARS }?.takeLast(MAX_CHARS)
    }

    /** The link a note gets in the vault's notes: its file name without `.md`. */
    fun linkName(path: String): String = path.substringAfterLast('/').removeSuffix(".md")

    /**
     * [text] with `[[name]]` for [path] at [caret], a space before it when the caret follows a
     * word, and where the caret goes after it.
     */
    fun insertLink(text: String, caret: Int, path: String): Pair<String, Int> {
        val at = caret.coerceIn(0, text.length)
        val before = text.substring(0, at)
        val space = if (before.isEmpty() || before.last().isWhitespace()) "" else " "
        val link = "$space[[${linkName(path)}]]"
        return (before + link + text.substring(at)) to (at + link.length)
    }
}
