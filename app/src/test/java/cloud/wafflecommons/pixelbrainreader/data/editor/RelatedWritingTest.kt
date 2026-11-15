package cloud.wafflecommons.pixelbrainreader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelatedWritingTest {

    private val long = "Les tomates se plantent en avril, après les dernières gelées du printemps."

    @Test
    fun `the paragraph around the caret, between blank lines, headings aside`() {
        val text = "# Garden\n\nFirst paragraph about something else entirely, long enough.\n\n## Plan\n$long\n\nAfter."
        val caret = text.indexOf("avril")
        assertEquals(long, RelatedWriting.paragraphAt(text, caret))
    }

    @Test
    fun `too short a paragraph, or the frontmatter, asks nothing`() {
        assertNull(RelatedWriting.paragraphAt("Short line.\n\n$long", 3))
        val withFrontmatter = "---\ntitle: Garden notes and a long enough title to count\n---\n$long"
        assertNull(RelatedWriting.paragraphAt(withFrontmatter, 10))
        assertEquals(long, RelatedWriting.paragraphAt(withFrontmatter, withFrontmatter.length))
        assertNull(RelatedWriting.paragraphAt("", 0))
    }

    @Test
    fun `a long paragraph keeps its end, what is being written`() {
        val paragraph = "x".repeat(RelatedWriting.MAX_CHARS) + " the end"
        val result = RelatedWriting.paragraphAt(paragraph, paragraph.length)!!
        assertEquals(RelatedWriting.MAX_CHARS, result.length)
        assertEquals(" the end", result.takeLast(8))
    }

    @Test
    fun `a link lands at the caret, with a space after a word, and the caret after it`() {
        assertEquals("See [[Plan]] today" to 12, RelatedWriting.insertLink("See today", 3, "20_Projects/21_Garden/Plan.md"))
        assertEquals("See [[Plan]]" to 12, RelatedWriting.insertLink("See ", 4, "Plan.md"))
        assertEquals("[[Plan]]" to 8, RelatedWriting.insertLink("", 0, "Plan.md"))
    }
}
