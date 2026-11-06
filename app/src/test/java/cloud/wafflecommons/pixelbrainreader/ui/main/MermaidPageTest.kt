package cloud.wafflecommons.pixelbrainreader.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The offline Mermaid page: only the bundled script, the source as data that can't escape. */
class MermaidPageTest {

    @Test
    fun `the source is a script string that can't close the script element`() {
        val hostile = "graph TD\nA[\"quote\"] --> B</script><script>alert(1)</script> end"
        val js = MermaidPage.jsString(hostile)
        assertFalse(js.contains("</script>"))
        assertFalse(js.contains(" "))
        assertTrue(js.startsWith("\"") && js.endsWith("\""))
        assertTrue(js.contains("\\\"quote\\\""))
        assertTrue(js.contains("\\n"))
    }

    @Test
    fun `the page loads the bundled script only, in strict mode, with the theme asked`() {
        val page = MermaidPage.html("graph TD\nA-->B", dark = true, background = "#101010", foreground = "#eeeeee")
        assertTrue(page.contains("<script src=\"mermaid.min.js\"></script>"))
        assertEquals(1, Regex("<script src=").findAll(page).count())
        assertFalse(page.contains("http://") || page.contains("https://"))
        assertTrue(page.contains("securityLevel: 'strict'"))
        assertTrue(page.contains("theme: 'dark'"))
        assertTrue(page.contains("background:#101010"))
        assertEquals("file:///android_asset/mermaid/mermaid.min.js", MermaidPage.SCRIPT_URL)
        assertTrue(MermaidPage.html("x", dark = false, background = "#fff", foreground = "#000").contains("theme: 'default'"))
    }

    @Test
    fun `the viewer loads the page and the bundled script, nothing else`() {
        assertTrue(MermaidPage.allows("data:text/html;charset=utf-8;base64,PGh0bWw+"))
        assertTrue(MermaidPage.allows("file:///android_asset/mermaid/mermaid.min.js"))
        assertFalse(MermaidPage.allows("https://cdn.jsdelivr.net/npm/mermaid/dist/mermaid.min.js"))
        assertFalse(MermaidPage.allows("file:///android_asset/sentences_encoder.tflite"))
        assertFalse(MermaidPage.allows("file:///data/data/cloud.wafflecommons.pixelbrainreader/files/vault/x.md"))
        assertFalse(MermaidPage.allows("content://media/external/images/1"))
    }

    @Test
    fun `a diagram's kind is its first word, comments and directives aside`() {
        assertEquals("flowchart", MermaidPage.kindOf("%% a comment\n\nflowchart LR\n  A --> B"))
        assertEquals("sequenceDiagram", MermaidPage.kindOf("sequenceDiagram\n  A->>B: hi"))
        assertEquals("diagram", MermaidPage.kindOf("   \n"))
    }
}
