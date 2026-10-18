package cloud.wafflecommons.pixelbrainreader.data.ai

/**
 * Splits a note into embedding chunks, each starting with a `[Note: title (folder) > heading]`
 * header (contextual retrieval).
 *
 * Prose is split on sentences and blank lines and packed into chunks that fit the embedder's
 * 256-token window. Each fenced code block becomes its own chunk(s), labelled `[code: <language>]`
 * and cut only between lines: mixed into the prose around it, code diluted both. A `#` comment
 * inside a fence is code, not a heading, and `#tag` is a tag: a heading needs `#` to `######`
 * followed by a space.
 *
 * Bump [VectorSearchEngine.EMBEDDER_SCHEMA_VERSION] when this changes: it re-embeds the vault.
 */
object NoteChunker {

    const val TARGET_SIZE = 400

    private const val DEFAULT_HEADING = "Général"
    private val SENTENCE_BREAK = Regex("(?<=[.!?。！？])\\s+|\\n+")

    private sealed class Block(val heading: String) {
        class Prose(heading: String, val text: String) : Block(heading)
        class Code(heading: String, val language: String, val code: String) : Block(heading)
    }

    fun chunk(text: String, filePath: String, targetSize: Int = TARGET_SIZE): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        val noteTitle = filePath.substringAfterLast("/").removeSuffix(".enc").removeSuffix(".md")
        val folder = filePath.substringBeforeLast("/", "").ifEmpty { "Vault" }
        val baseHeader = "[Note: $noteTitle ($folder)]\n"
        val maxBodySize = (targetSize - baseHeader.length - 50).coerceAtLeast(150)

        val chunks = mutableListOf<String>()
        for (block in blocks(trimmed)) {
            val header = "[Note: $noteTitle ($folder) > ${block.heading}]"
            when (block) {
                is Block.Prose -> packProse(block.text, maxBodySize).forEach { chunks += "$header\n$it" }
                is Block.Code -> splitCode(block.code, maxBodySize).forEach { chunks += "$header [code: ${block.language}]\n$it" }
            }
        }
        return chunks.ifEmpty { listOf(baseHeader + trimmed.take(targetSize)) }
    }

    /** Headings, prose and fenced code, in order. An unclosed fence runs to the end of the note. */
    private fun blocks(text: String): List<Block> {
        val result = mutableListOf<Block>()
        var heading = DEFAULT_HEADING
        val prose = StringBuilder()
        var fence: String? = null
        var language = ""
        val code = StringBuilder()

        fun flushProse() {
            if (prose.isNotBlank()) result += Block.Prose(heading, prose.toString().trim())
            prose.setLength(0)
        }

        fun flushCode() {
            if (code.isNotBlank()) result += Block.Code(heading, language.ifEmpty { "code" }, code.toString().trimEnd())
            code.setLength(0)
        }

        for (line in text.lines()) {
            val stripped = line.trim()
            val openFence = fence
            if (openFence != null) {
                if (closesFence(stripped, openFence)) {
                    flushCode()
                    fence = null
                } else {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(line)
                }
                continue
            }
            val opening = fenceOf(stripped)
            if (opening != null) {
                flushProse()
                fence = opening
                language = stripped.removePrefix(opening).trim()
                    .substringBefore(' ').substringBefore('{').trim().lowercase()
                continue
            }
            val headingText = headingOf(stripped)
            if (headingText != null) {
                flushProse()
                heading = headingText.take(60).ifEmpty { heading }
                continue
            }
            if (prose.isNotEmpty()) prose.append('\n')
            prose.append(line)
        }
        if (fence != null) flushCode() else flushProse()
        return result
    }

    /** The fence marker opening a code block (three or more backticks or tildes), or null. */
    private fun fenceOf(stripped: String): String? {
        val marker = stripped.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
        val length = stripped.takeWhile { it == marker }.length
        if (length < 3) return null
        // A backtick fence's info string can't contain backticks (that's inline code).
        if (marker == '`' && stripped.drop(length).contains('`')) return null
        return marker.toString().repeat(length)
    }

    private fun closesFence(stripped: String, fence: String): Boolean =
        stripped.length >= fence.length && stripped.all { it == fence[0] }

    /** The text of an ATX heading (`#` to `######` then a space), or null. */
    private fun headingOf(stripped: String): String? {
        val level = stripped.takeWhile { it == '#' }.length
        if (level !in 1..6) return null
        if (stripped.length > level && !stripped[level].isWhitespace()) return null
        return stripped.drop(level).trim().trimEnd('#').trim()
    }

    /** Sentences and lines packed into chunks of at most [maxBodySize] characters. */
    private fun packProse(text: String, maxBodySize: Int): List<String> {
        val units = text.split(SENTENCE_BREAK).map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (unit in units) {
            if (unit.length > maxBodySize) {
                if (current.isNotEmpty()) {
                    chunks += current.toString().trim()
                    current.setLength(0)
                }
                chunks += unit.chunked(maxBodySize)
                continue
            }
            if (current.isNotEmpty() && current.length + unit.length + 1 > maxBodySize) {
                chunks += current.toString().trim()
                current.setLength(0)
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(unit)
        }
        if (current.isNotBlank()) chunks += current.toString().trim()
        return chunks
    }

    /** Code cut between lines; a single line longer than [maxBodySize] is the only thing split inside. */
    private fun splitCode(code: String, maxBodySize: Int): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (line in code.lines()) {
            val pieces = if (line.length > maxBodySize) line.chunked(maxBodySize) else listOf(line)
            for (piece in pieces) {
                if (current.isNotEmpty() && current.length + piece.length + 1 > maxBodySize) {
                    chunks += current.toString().trimEnd()
                    current.setLength(0)
                }
                if (current.isNotEmpty()) current.append('\n')
                current.append(piece)
            }
        }
        if (current.isNotBlank()) chunks += current.toString().trimEnd()
        return chunks
    }
}
