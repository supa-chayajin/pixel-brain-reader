package cloud.wafflecommons.pixelbrainreader.data.vault

/**
 * Notes a desktop script copies into the vault from a source outside it and rewrites on its next
 * run: `repo_mirror.py`'s copies of a repository's documents and of Claude's memory
 * (`mirror_of:` in the frontmatter), and `worktrace.py`'s copy of a repository's `TODO.md` (a
 * "Mirrored automatically from …" banner right after the frontmatter). An edit made on the phone
 * would be overwritten, so they are read-only here: no editor, no lint, no board move, and they
 * stay out of a project's actions and recent notes. They are still listed, searched and linked.
 */
object MirrorNotes {

    private val MIRROR_KEY = Regex("""^mirror_of\s*:\s*\S""")
    private const val TODO_BANNER = "> Mirrored automatically from "
    private const val INDEX_SUFFIX = ":__index__"

    /** True for a note a desktop script regenerates. Reads only the head of the note. */
    fun isMirror(content: String): Boolean {
        val head = head(content)
        return head.mirrorOf != null || head.firstBodyLine?.startsWith(TODO_BANNER) == true
    }

    /** The index `repo_mirror.py` writes next to its copies (`mirror_of: "<manifest>:__index__"`). */
    fun isMirrorIndex(content: String): Boolean = head(content).mirrorOf?.endsWith(INDEX_SUFFIX) == true

    /** The `mirror_of:` value (unquoted) and the first non-blank line after the frontmatter. */
    private class Head(val mirrorOf: String?, val firstBodyLine: String?)

    private fun head(content: String): Head {
        val lines = content.lineSequence().map { it.trimEnd('\r') }.iterator()
        val first = nextNonBlank(lines) ?: return Head(null, null)
        if (first.trimEnd() != "---") return Head(null, first)
        var mirrorOf: String? = null
        while (lines.hasNext()) {
            val line = lines.next()
            if (line.trimEnd() == "---") return Head(mirrorOf, nextNonBlank(lines))
            if (mirrorOf == null && MIRROR_KEY.containsMatchIn(line)) {
                mirrorOf = line.substringAfter(':').trim().removeSurrounding("\"").removeSurrounding("'")
            }
        }
        // No closing `---`: a thematic break, not frontmatter.
        return Head(null, first)
    }

    private fun nextNonBlank(lines: Iterator<String>): String? {
        while (lines.hasNext()) {
            val line = lines.next()
            if (line.isNotBlank()) return line
        }
        return null
    }
}
