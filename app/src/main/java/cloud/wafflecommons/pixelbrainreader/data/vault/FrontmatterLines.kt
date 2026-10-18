package cloud.wafflecommons.pixelbrainreader.data.vault

/**
 * Surgical frontmatter edits: one key's line changes and every other byte of the note stays as it
 * was written. Re-serializing the whole block through a YAML library rewrites quoting, list style and
 * key order — the vault's own linter and canonical mutator own those, so the app never does it to an
 * existing note.
 *
 * Only top-level `key: value` lines are handled. A key whose value continues on indented lines (a
 * block list) is replaced together with those lines.
 */
object FrontmatterLines {

    private val KEY_LINE = Regex("""^([A-Za-z0-9_-]+)\s*:(.*)$""")
    private val INLINE_LIST = Regex("""^\[(.*)]$""")

    /** Line indices of the opening and closing `---`, or null when the note has no frontmatter. */
    private data class Block(val open: Int, val close: Int)

    private fun locate(lines: List<String>): Block? {
        var i = 0
        while (i < lines.size && lines[i].isBlank()) i++
        if (i >= lines.size || lines[i].trimEnd() != "---") return null
        val close = (i + 1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" } ?: return null
        if (close == i + 1) {
            // Legacy "hollow" block (`---\n---\n<yaml>\n---`): the real one follows.
            val realClose = (close + 1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" }
            if (realClose != null && looksLikeYaml(lines.subList(close + 1, realClose))) return Block(close, realClose)
            return null
        }
        // A leading `---` thematic break is not frontmatter.
        return if (looksLikeYaml(lines.subList(i + 1, close))) Block(i, close) else null
    }

    private fun looksLikeYaml(block: List<String>): Boolean {
        val first = block.map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return false
        return KEY_LINE.matches(first)
    }

    /** True when [content] opens with a frontmatter block. */
    fun hasFrontmatter(content: String): Boolean = locate(content.split("\n")) != null

    /** The top-level keys of the frontmatter, in order. */
    fun keys(content: String): List<String> {
        val lines = content.split("\n")
        val block = locate(lines) ?: return emptyList()
        return (block.open + 1 until block.close).mapNotNull { idx ->
            if (lines[idx].startsWith(" ") || lines[idx].startsWith("\t")) null
            else KEY_LINE.find(lines[idx].trimEnd('\r'))?.groupValues?.get(1)
        }
    }

    /** The scalar value of [key], without its quotes; null when absent. */
    fun get(content: String, key: String): String? {
        val lines = content.split("\n")
        val block = locate(lines) ?: return null
        val idx = keyLine(lines, block, key) ?: return null
        return unquote(KEY_LINE.find(lines[idx].trimEnd('\r'))!!.groupValues[2].trim())
    }

    /** The list value of [key] (inline `[a, b]` or a block list); empty when absent. */
    fun getList(content: String, key: String): List<String> {
        val lines = content.split("\n")
        val block = locate(lines) ?: return emptyList()
        val idx = keyLine(lines, block, key) ?: return emptyList()
        val inline = KEY_LINE.find(lines[idx].trimEnd('\r'))!!.groupValues[2].trim()
        INLINE_LIST.find(inline)?.let { match ->
            return match.groupValues[1].split(',').map { unquote(it.trim()) }.filter { it.isNotEmpty() }
        }
        if (inline.isNotEmpty()) return listOf(unquote(inline))
        return continuation(lines, block, idx)
            .map { lines[it].trim() }
            .filter { it.startsWith("- ") || it == "-" }
            .map { unquote(it.removePrefix("-").trim()) }
            .filter { it.isNotEmpty() }
    }

    /**
     * Sets [key] to the raw YAML [rawValue]. An existing line is replaced in place (with its
     * indented continuation lines); a missing standard key goes to its R1 position, any other key
     * before the closing `---`. A note without frontmatter gets a block holding just this key.
     */
    fun set(content: String, key: String, rawValue: String): String {
        val lines = content.split("\n").toMutableList()
        val newLine = "$key: $rawValue"
        val block = locate(lines)
            ?: return "---\n$newLine\n---\n" + content.trimStart('\n')
        val idx = keyLine(lines, block, key)
        if (idx != null) {
            val cont = continuation(lines, block, idx)
            val crlf = lines[idx].endsWith("\r")
            cont.reversed().forEach { lines.removeAt(it) }
            lines[idx] = if (crlf) "$newLine\r" else newLine
            return lines.joinToString("\n")
        }
        lines.add(insertionIndex(lines, block, key), newLine)
        return lines.joinToString("\n")
    }

    /** Removes [key] (and its continuation lines); unchanged when absent. */
    fun remove(content: String, key: String): String {
        val lines = content.split("\n").toMutableList()
        val block = locate(lines) ?: return content
        val idx = keyLine(lines, block, key) ?: return content
        (continuation(lines, block, idx) + idx).sortedDescending().forEach { lines.removeAt(it) }
        return lines.joinToString("\n")
    }

    private fun keyLine(lines: List<String>, block: Block, key: String): Int? =
        (block.open + 1 until block.close).firstOrNull { idx ->
            val line = lines[idx]
            !line.startsWith(" ") && !line.startsWith("\t") &&
                KEY_LINE.find(line.trimEnd('\r'))?.groupValues?.get(1) == key
        }

    /** Indented (or list-item) lines that belong to the key on line [idx]. */
    private fun continuation(lines: List<String>, block: Block, idx: Int): List<Int> {
        val out = mutableListOf<Int>()
        var k = idx + 1
        while (k < block.close) {
            val line = lines[k]
            val belongs = line.startsWith(" ") || line.startsWith("\t") || line.trimStart().startsWith("- ")
            if (!belongs || line.isBlank()) break
            out += k
            k++
        }
        return out
    }

    private fun insertionIndex(lines: List<String>, block: Block, key: String): Int {
        val order = VaultSchema.STANDARD_KEYS.indexOf(key)
        if (order < 0) return block.close
        // After the last present standard key that comes before [key] in R1 order…
        val before = VaultSchema.STANDARD_KEYS.take(order).mapNotNull { keyLine(lines, block, it) }.maxOrNull()
        if (before != null) {
            val cont = continuation(lines, block, before)
            return (cont.lastOrNull() ?: before) + 1
        }
        // …or before the first present standard key that comes after it.
        val after = VaultSchema.STANDARD_KEYS.drop(order + 1).mapNotNull { keyLine(lines, block, it) }.minOrNull()
        return after ?: block.close
    }

    private fun unquote(raw: String): String {
        val value = raw.trim()
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        if (value.length >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length - 1).replace("''", "'")
        }
        return value
    }
}
