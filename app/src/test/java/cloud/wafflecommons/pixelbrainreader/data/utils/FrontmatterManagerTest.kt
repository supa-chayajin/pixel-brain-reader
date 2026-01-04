package cloud.wafflecommons.pixelbrainreader.data.utils

import org.junit.Assert.*
import org.junit.Test

class FrontmatterManagerTest {

    @Test
    fun `extractFrontmatter_ValidBlock_ReturnsMap`() {
        val content = """
            ---
            title: "My Note"
            tags: #obsidian #test
            date: 2026-01-04
            ---
            Markdown Body
        """.trimIndent()
        
        val result = FrontmatterManager.extractFrontmatter(content)
        
        assertEquals("My Note", result["title"])
        assertEquals("#obsidian #test", result["tags"])
        assertEquals("2026-01-04", result["date"])
    }

    @Test
    fun `extractFrontmatter_NoFrontmatter_ReturnsEmptyMap`() {
        val content = "Just markdown content"
        val result = FrontmatterManager.extractFrontmatter(content)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `stripFrontmatter_RemovesBlock`() {
        val content = """
            ---
            title: Hello
            ---
            Actual Content
        """.trimIndent()
        
        val result = FrontmatterManager.stripFrontmatter(content)
        
        assertFalse(result.contains("title: Hello"))
        assertTrue(result.contains("Actual Content"))
        assertEquals("Actual Content", result.trim())
    }

    @Test
    fun `stripFrontmatter_NoFrontmatter_ReturnsOriginal`() {
        val content = "No Frontmatter Here"
        val result = FrontmatterManager.stripFrontmatter(content)
        assertEquals(content, result)
    }

    @Test
    fun `prepareContentForDisplay_ConsistentWithStrip`() {
        val content = "---\na: b\n---\nBody"
        assertEquals(FrontmatterManager.stripFrontmatter(content), FrontmatterManager.prepareContentForDisplay(content))
    }
}
