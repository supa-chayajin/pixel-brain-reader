package cloud.wafflecommons.pixelbrainreader.data.web

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedContentTest {

    @Test
    fun `a browser shares the address, with the page title as subject`() {
        assertEquals(SharedContent("https://example.com/a", null, null), SharedContent.of("https://example.com/a", null))
        assertEquals(SharedContent("https://example.com/a", "Page title", null), SharedContent.of("https://example.com/a", "Page title"))
    }

    @Test
    fun `text around the link is the title when short`() {
        assertEquals(SharedContent("https://example.com/a", "Great read", null), SharedContent.of("Great read https://example.com/a", null))
        assertEquals(SharedContent("https://example.com/a", "Great read", null), SharedContent.of("Great read - https://example.com/a", null))
    }

    @Test
    fun `a quoted highlight is the passage, the subject the title`() {
        val shared = SharedContent.of("“Some highlighted sentence.”\nhttps://example.com/a#:~:text=Some%20highlighted", "Page title")

        assertEquals("https://example.com/a#:~:text=Some%20highlighted", shared.url)
        assertEquals("Page title", shared.titleHint)
        assertEquals("Some highlighted sentence.", shared.passage)
    }

    @Test
    fun `punctuation after a link isn't part of it`() {
        assertEquals("https://example.com/a", SharedContent.of("See (https://example.com/a).", null).url)
        assertEquals("https://example.com/a?b=1", SharedContent.of("https://example.com/a?b=1, nice", null).url)
        assertEquals("https://fr.wikipedia.org/wiki/Kotlin_(langage)", SharedContent.of("https://fr.wikipedia.org/wiki/Kotlin_(langage)", null).url)
        assertEquals("https://fr.wikipedia.org/wiki/Kotlin_(langage)", SharedContent.of("(voir https://fr.wikipedia.org/wiki/Kotlin_(langage)).", null).url)
    }

    @Test
    fun `text without a link is the note, its first line the title`() {
        val shared = SharedContent.of("# Groceries\n- milk\n- eggs", null)

        assertEquals(null, shared.url)
        assertEquals("Groceries", shared.titleHint)
        assertEquals("# Groceries\n- milk\n- eggs", shared.passage)
    }

    @Test
    fun `a long message with a link is kept as the passage`() {
        val message = "Look at this one, it explains the whole migration we talked about.\nWorth reading before Monday."
        val shared = SharedContent.of("$message https://example.com/a", null)

        assertEquals("https://example.com/a", shared.url)
        assertEquals(null, shared.titleHint)
        assertEquals(message, shared.passage)
    }
}
