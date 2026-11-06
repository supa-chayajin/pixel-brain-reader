package cloud.wafflecommons.pixelbrainreader.data.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The notes a desktop script rewrites (repo_mirror.py, worktrace.py's TODO copy) are recognized from their head. */
class MirrorNotesTest {

    private val repoMirror = """
        ---
        title: "🧭 Roadmap v2"
        date_created: '2026-10-01'
        date_updated: '2026-11-05'
        type: projet
        status: 🏗️ wip
        tags:
          - mirror
        aliases: []
        mirror_of: "demo:docs/roadmaps/Roadmap_v2.md"
        ---
        # 🧭 Roadmap v2

        > [!INFO] Read-only mirror
        > `docs/roadmaps/Roadmap_v2.md` in **demo**. Edit the source: `scripts/repo_mirror.py` rewrites this copy.

        - [ ] an acceptance criterion
    """.trimIndent()

    private val todoMirror = """
        ---
        title: "📋 Demo — Next Actions (TODO)"
        date_created: '2026-07-09'
        date_updated: '2026-11-05'
        type: note
        tags:
          - todo
        aliases: []
        ---

        > Mirrored automatically from `demo/TODO.md` — edit the source, not this copy; it's overwritten every session-end/nightly run. See [[README]].

        # Demo workspace TODO
    """.trimIndent()

    @Test
    fun `a repo_mirror copy and worktrace's TODO copy are mirrors`() {
        assertTrue(MirrorNotes.isMirror(repoMirror))
        assertTrue(MirrorNotes.isMirror(repoMirror.replace("\n", "\r\n")))
        assertTrue(MirrorNotes.isMirror(todoMirror))
        // An older TODO copy without frontmatter.
        assertTrue(MirrorNotes.isMirror("> Mirrored automatically from `demo/TODO.md` — edit the source.\n\n- [ ] x\n"))
    }

    @Test
    fun `hand-written notes are not mirrors`() {
        assertFalse(MirrorNotes.isMirror("---\ntitle: \"Plan\"\n---\n# Plan\n\n- [ ] @moi ship it\n"))
        assertFalse(MirrorNotes.isMirror(""))
        assertFalse(MirrorNotes.isMirror("# No frontmatter\n"))
        // An empty value, a nested key, a key in the body and a quoted banner further down don't count.
        assertFalse(MirrorNotes.isMirror("---\ntitle: x\nmirror_of:\n---\n# x\n"))
        assertFalse(MirrorNotes.isMirror("---\ntitle: x\nsource:\n  mirror_of: y\n---\n# x\n"))
        assertFalse(MirrorNotes.isMirror("---\ntitle: x\n---\n# x\n\nmirror_of: y\n"))
        assertFalse(MirrorNotes.isMirror("---\ntitle: x\n---\n# x\n\n> Mirrored automatically from `a` — quoted in a note.\n"))
        // A leading thematic break with no closing line is not frontmatter.
        assertFalse(MirrorNotes.isMirror("---\nmirror_of: y\nsome text\n"))
    }

    @Test
    fun `the mirror's index is told apart from its copies`() {
        assertTrue(MirrorNotes.isMirrorIndex("---\ntitle: \"Index\"\nmirror_of: \"demo:__index__\"\n---\n# Index\n"))
        assertFalse(MirrorNotes.isMirrorIndex(repoMirror))
        assertFalse(MirrorNotes.isMirrorIndex(todoMirror))
    }

    @Test
    fun `claude's mirrored memory is found by its folder under a project`() {
        assertTrue(MirrorNotes.isClaudeMemory("20_Projects/21_Demo/Claude/Memory/feedback.md"))
        assertTrue(MirrorNotes.isClaudeMemory("20_Projects/22_Demo/app/Claude/Memory/notes/x.md"))
        assertFalse(MirrorNotes.isClaudeMemory("20_Projects/21_Demo/Claude/Reports/2026-11-06_Audit.md"))
        assertFalse(MirrorNotes.isClaudeMemory("20_Projects/21_Demo/Repo/Docs/memory.md"))
        assertFalse(MirrorNotes.isClaudeMemory("30_Areas/Claude/Memory/x.md"))
    }
}
