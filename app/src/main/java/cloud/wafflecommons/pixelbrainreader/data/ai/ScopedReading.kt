package cloud.wafflecommons.pixelbrainreader.data.ai

/**
 * Reading one note for a question (K2, 12.20). A note that fits the prompt goes in whole; a longer
 * one is read in parts, each summarized for the question, and the answer reads the summaries (the
 * owner's D6: chained summaries). A note too long even for that is answered from its passages
 * closest to the question instead. Prompts are French (settled decision #5). Pure.
 */
object ScopedReading {

    /** A note up to this length goes into the prompt whole (Nano's window is about 4,000 tokens). */
    const val WHOLE_NOTE_CHARS = 4_500

    /** One part of a longer note, for one summary call. */
    const val PART_CHARS = 4_000

    /** Beyond this many parts, the note is answered from its closest passages. */
    const val MAX_PARTS = 6

    /** What a part's summary says when nothing in it helps. */
    const val NOTHING = "RIEN"

    /** The note's body, its frontmatter left out. */
    fun body(content: String): String {
        if (!content.startsWith("---")) return content.trim()
        val end = content.indexOf("\n---", startIndex = 3)
        return if (end < 0) content.trim() else content.substring(end + 4).trim()
    }

    /**
     * [text] cut into parts of at most [size] characters between paragraphs; a paragraph longer
     * than [size] is cut between lines, and a line longer than that at [size].
     */
    fun parts(text: String, size: Int = PART_CHARS): List<String> {
        val pieces = text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
            .flatMap { paragraph -> if (paragraph.length <= size) listOf(paragraph) else splitLong(paragraph, size) }
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (piece in pieces) {
            if (current.isNotEmpty() && current.length + 2 + piece.length > size) {
                parts += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(piece)
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }

    private fun splitLong(paragraph: String, size: Int): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (line in paragraph.lines()) {
            var rest = line
            while (rest.length > size) {
                if (current.isNotEmpty()) {
                    out += current.toString()
                    current.clear()
                }
                out += rest.take(size)
                rest = rest.drop(size)
            }
            if (current.isNotEmpty() && current.length + 1 + rest.length > size) {
                out += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(rest)
        }
        if (current.isNotEmpty()) out += current.toString()
        return out.filter { it.isNotBlank() }
    }

    /** The context for a note that fits whole. */
    fun wholeContext(title: String, text: String): String = "[Note : $title]\n$text"

    /** The prompt that summarizes part [index] (from 1) of [count] for [question]. */
    fun partPrompt(title: String, index: Int, count: Int, part: String, question: String): String =
        "Voici la partie $index sur $count de la note « $title ».\n\n" +
            "$part\n\n" +
            "Résume en quelques phrases ce que cette partie dit d'utile pour répondre à la question : " +
            "« $question ». Garde les faits, les dates et les noms tels qu'ils sont écrits. " +
            "Si rien dans cette partie n'aide à répondre, écris seulement : $NOTHING.\n\n" +
            AiLanguage.DIRECTIVE

    /** Whether a part's summary says there was nothing useful in it. */
    fun isNothing(summary: String): Boolean =
        summary.trim().trimEnd('.', '!').uppercase() == NOTHING

    /** The context the answer reads: each useful part's summary, or null when none was. */
    fun summariesContext(title: String, summaries: List<String>): String? {
        val useful = summaries.withIndex().filterNot { isNothing(it.value) || it.value.isBlank() }
        if (useful.isEmpty()) return null
        return useful.joinToString("\n\n") { (i, summary) ->
            "[Note : $title — résumé de la partie ${i + 1} sur ${summaries.size}]\n${summary.trim()}"
        }
    }
}
