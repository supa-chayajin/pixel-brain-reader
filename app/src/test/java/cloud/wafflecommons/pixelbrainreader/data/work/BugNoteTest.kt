package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.vault.FrontmatterLines
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** C8: the bug note from the vault's template (or without it), in the right folder. */
class BugNoteTest {

    private val now = LocalDateTime.of(2026, 11, 10, 21, 30)
    private val trace = ErrorTrace.parse("Fatal error: Uncaught Waffle\\Queue\\Bad: \"x\" broke in /srv/a.php:3\n#0 /srv/b.php(9): y()")!!
    private val template = """
        ---
        title: "🐞 Bug: {{title}}"
        date_created: '2026-04-13'
        date_updated: '2026-04-13'
        type: system
        status: 🟢 done
        tags: []
        aliases: []
        ---

        # 🐞 Bug: {{title}}

        ## 📍 Context
        - **Project**:

        ## 📝 Description
        <!-- What happens vs What should happen -->

        ## 🔬 Reproduction
        1.
    """.trimIndent()
    private val occurrences = listOf(
        ErrorLookup.Occurrence("20_Projects/21_Garden_Planner/Notes/Queue.md", "x", viaWords = true),
        ErrorLookup.Occurrence("99_Private/journal.md.enc", "x", viaWords = false)
    )

    @Test
    fun `a project's bugs go to its Bugs folder, the rest to the inbox`() {
        assertEquals("20_Projects/21_Garden_Planner/Bugs", BugNote.folder("20_Projects/21_Garden_Planner"))
        assertEquals("00_Inbox", BugNote.folder(null))
        assertEquals("00_Inbox/2026-11-10 Bug Bad x broke.md", BugNote.path("00_Inbox", now, trace))
    }

    @Test
    fun `the template is filled for its folder, with the explanation and the added sections`() {
        val path = "20_Projects/21_Garden_Planner/Bugs/2026-11-10 Bug Bad x broke.md"
        val note = BugNote.build(template, path, trace, "line 1\nline 2", "La file est pleine.", isGeneralKnowledge = false, occurrences, now)
        assertEquals("🐞 Bug: Bad: 'x' broke", FrontmatterLines.get(note, "title"))
        assertEquals("2026-11-10", FrontmatterLines.get(note, "date_created"))
        assertEquals(VaultSchema.typeFor(path), FrontmatterLines.get(note, "type"))
        assertEquals(VaultSchema.defaultStatus(VaultSchema.typeFor(path)), FrontmatterLines.get(note, "status"))
        val description = note.indexOf("## 📝 Description")
        assertTrue(note.indexOf("**Explication (IA sur l'appareil)** : La file est pleine.") in description..note.indexOf("## 🔬 Reproduction"))
        assertTrue(note.contains("- [[20_Projects/21_Garden_Planner/Notes/Queue]]"))
        assertFalse("a private note is never named", note.contains("99_Private"))
        assertTrue(note.contains("## 📜 Trace\n\n```\nline 1\nline 2\n```"))
    }

    @Test
    fun `without the template, a note with the same sections, and a long trace cut`() {
        val raw = (1..100).joinToString("\n") { "frame $it" }
        val note = BugNote.build(null, "00_Inbox/x.md", trace, raw, null, isGeneralKnowledge = false, emptyList(), now)
        assertTrue(note.startsWith("---\ntitle: \"🐞 Bug: "))
        assertTrue(note.contains("Pas vu dans tes notes."))
        assertTrue(note.contains("frame 80\n```"))
        assertTrue(note.contains("(20 lignes de plus non recopiées)"))
    }

    @Test
    fun `an answer from general knowledge says so`() {
        val note = BugNote.build(null, "00_Inbox/x.md", trace, "x", "Une file pleine.", isGeneralKnowledge = true, emptyList(), now)
        assertTrue(note.contains("connaissances générales : pas tes notes"))
    }
}
