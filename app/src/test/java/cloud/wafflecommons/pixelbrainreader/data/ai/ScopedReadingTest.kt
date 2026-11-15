package cloud.wafflecommons.pixelbrainreader.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopedReadingTest {

    @Test
    fun `the frontmatter is left out of the body`() {
        assertEquals("# Title\nText", ScopedReading.body("---\ntitle: x\ntags: []\n---\n# Title\nText\n"))
        assertEquals("No frontmatter", ScopedReading.body("No frontmatter\n"))
    }

    @Test
    fun `parts are cut between paragraphs and never exceed the size`() {
        val paragraphs = (1..10).map { "Paragraph $it " + "x".repeat(30) }
        val parts = ScopedReading.parts(paragraphs.joinToString("\n\n"), size = 100)
        assertTrue(parts.all { it.length <= 100 })
        // Every paragraph survives whole, in order.
        assertEquals(paragraphs, parts.flatMap { it.split("\n\n") })
    }

    @Test
    fun `a paragraph longer than a part is cut between lines, a line longer than that at the size`() {
        val parts = ScopedReading.parts("short line\n" + "y".repeat(250), size = 100)
        assertTrue(parts.all { it.length <= 100 })
        assertEquals("short line" + "y".repeat(250), parts.joinToString("").replace("\n", ""))
    }

    @Test
    fun `each part's prompt is French, holds the question and ends with the directive`() {
        val prompt = ScopedReading.partPrompt("Garden", 2, 5, "Les tomates en avril.", "Quand planter ?")
        assertTrue(prompt.contains("partie 2 sur 5"))
        assertTrue(prompt.contains("« Quand planter ? »"))
        assertTrue(prompt.contains("Les tomates en avril."))
        assertTrue(prompt.endsWith(AiLanguage.DIRECTIVE))
    }

    @Test
    fun `the answer reads only the useful summaries, and nothing when none was`() {
        assertTrue(ScopedReading.isNothing(" rien. "))
        assertFalse(ScopedReading.isNothing("Rien de prévu, sauf les tomates."))
        val context = ScopedReading.summariesContext("Garden", listOf("RIEN", "Tomates en avril.", ""))!!
        assertEquals("[Note : Garden — résumé de la partie 2 sur 3]\nTomates en avril.", context)
        assertNull(ScopedReading.summariesContext("Garden", listOf("RIEN", "Rien.")))
    }
}
