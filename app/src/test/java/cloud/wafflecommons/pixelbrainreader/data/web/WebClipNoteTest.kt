package cloud.wafflecommons.pixelbrainreader.data.web

import cloud.wafflecommons.pixelbrainreader.data.vault.VaultLint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WebClipNoteTest {

    private val today = LocalDate.of(2026, 10, 18)
    private val body = (1..40).joinToString(" ") { "word$it" } + "\n\n## Section\n\n" + (1..140).joinToString(" ") { "more$it" }

    private fun clip(markdown: String = body, words: Int = 180) = WebClip(
        url = "https://blog.example.com/posts/flows",
        title = "Understanding flows",
        siteName = "Example Blog",
        author = "Ada",
        published = "2024-03-05",
        description = "All about flows.",
        heroImage = "https://cdn.example.com/hero.png",
        markdown = markdown,
        words = words
    )

    private fun lint(path: String, content: String) = VaultLint.check(path, content) { true }

    @Test
    fun `an article note follows the vault's rules`() {
        val note = WebClipNote.render(clip(), "Understanding flows", "00_Inbox", "- un\n- deux\n- trois", "A highlighted passage.", today)

        assertEquals(emptyList<Any>(), lint("00_Inbox/Understanding flows.md", note))
        assertTrue(note.startsWith("---\ntitle: \"Understanding flows\"\n"))
        listOf(
            "source: \"https://blog.example.com/posts/flows\"",
            "author: \"Ada\"",
            "published: '2024-03-05'",
            "captured: '2026-10-18'",
            "> [!info] Source\n> [Example Blog](https://blog.example.com/posts/flows)  ·  Ada  ·  2024-03-05",
            "![](https://cdn.example.com/hero.png)",
            "> [!abstract] Résumé (Gemini Nano, sur l'appareil)\n> - un\n> - deux",
            "> [!quote] Passage partagé\n> A highlighted passage."
        ).forEach { assertTrue(it, note.contains(it)) }
        assertFalse(note.contains("[!warning]"))
    }

    @Test
    fun `a partial article says so`() {
        val note = WebClipNote.render(clip(words = 60), "Understanding flows", "00_Inbox", null, null, today)

        assertTrue(note.contains("> [!warning] Extrait partiel"))
        assertFalse(note.contains("[!abstract]"))
    }

    @Test
    fun `the body keeps one H1, the note's`() {
        val note = WebClipNote.render(clip(markdown = "# Understanding flows\n\nIntro.\n\n# Appendix\n\n```\n# not a heading\n```"), "Understanding flows", "00_Inbox", null, null, today)

        var inFence = false
        val h1s = note.lines().count { line ->
            if (line.startsWith("```")) inFence = !inFence
            !inFence && line.startsWith("# ")
        }
        assertEquals(1, h1s)
        assertTrue(note.contains("## Appendix"))
        assertTrue(note.contains("```\n# not a heading\n```"))
    }

    @Test
    fun `a page that couldn't be read is kept as its link`() {
        val note = WebClipNote.linkOnly(
            "https://news.example.com/a", "news.example.com", "00_Inbox",
            WebClipNote.reason(PageProblem.PAYWALL, 402, null), "A teaser.", "My highlight.", today
        )

        assertEquals(emptyList<Any>(), lint("00_Inbox/news.example.com.md", note))
        assertTrue(note.contains("> [!warning] Page non importée\n> La page est réservée aux abonnés (HTTP 402)."))
        assertTrue(note.contains("A teaser."))
        assertTrue(note.endsWith("> My highlight.\n"))
    }

    @Test
    fun `shared text becomes a note under the folder's type`() {
        val note = WebClipNote.text("Groceries", "40_Resources", "# Groceries\n- milk", today)

        assertEquals(emptyList<Any>(), lint("40_Resources/Groceries.md", note))
        assertTrue(note.contains("type: ressource"))
        assertTrue(note.endsWith("# Groceries\n\n- milk\n"))
    }

    @Test
    fun `file names lose path and link characters`() {
        assertEquals("a b c", WebClipNote.fileName("a/b: c?"))
        assertEquals("Clipping", WebClipNote.fileName("///"))
        assertEquals(80, WebClipNote.fileName("x".repeat(200)).length)
        assertEquals("Title", WebClipNote.fileName("Title..."))
    }
}
