package cloud.wafflecommons.pixelbrainreader.data.vault

/**
 * Notes a desktop script copies into the vault from a source outside it and rewrites on its next
 * run: `repo_mirror.py`'s copies of a repository's documents and of Claude's memory
 * (`mirror_of:` in the frontmatter), and `worktrace.py`'s copy of a repository's `TODO.md` (a
 * "Mirrored automatically from …" banner right after the frontmatter). An edit made on the phone
 * would be overwritten, so they are read-only here: no editor, no lint, no board move, and they
 * stay out of a project's actions and recent notes. They are still listed, searched and linked.
 *
 * The desktop's worklogs ([isWorklog]) are generated too, and read-only in the editor the same way,
 * but they stay in a project's notes: they are the record of the work. [isGenerated] is either.
 */
object MirrorNotes {

    private val MIRROR_KEY = Regex("""^mirror_of\s*:\s*\S""")
    /** Same rule as `VaultLint` and `ProjectFormat.isWorklog`. */
    private val WORKLOG = Regex("""(^|/)Worklog/\d{4}-\d{2}-\d{2}\.md$""")
    private const val TODO_BANNER = "> Mirrored automatically from "
    private const val INDEX_SUFFIX = ":__index__"
    private const val PROJECTS = "20_Projects/"
    private const val CLAUDE_MEMORY = "/Claude/Memory/"

    /** True for a note a desktop script regenerates. Reads only the head of the note. */
    fun isMirror(content: String): Boolean {
        val head = head(content)
        return head.mirrorOf != null || head.firstBodyLine?.startsWith(TODO_BANNER) == true
    }

    /**
     * A worklog (`<project>/Worklog/yyyy-MM-dd.md`): the desktop's worktrace writes it at session end
     * and rewrites it on its next run, so an edit made here would be lost. Read from the path alone.
     */
    fun isWorklog(path: String?): Boolean = path != null && WORKLOG.containsMatchIn(path)

    /** Read-only in the editor: a desktop mirror (from its head) or a worklog (from its path). */
    fun isGenerated(path: String?, content: String?): Boolean =
        isWorklog(path) || (content != null && isMirror(content))

    /** The index `repo_mirror.py` writes next to its copies (`mirror_of: "<manifest>:__index__"`). */
    fun isMirrorIndex(content: String): Boolean = head(content).mirrorOf?.endsWith(INDEX_SUFFIX) == true

    /**
     * Claude's memory mirrored under a project (`<project>/Claude/Memory/`): never embedded (settled
     * decision #16). It repeats the desktop's own memory and is noise for retrieval;
     * the mirrored repository documents (`Repo/`) stay in the index.
     */
    fun isClaudeMemory(path: String): Boolean = path.startsWith(PROJECTS) && path.contains(CLAUDE_MEMORY)

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
