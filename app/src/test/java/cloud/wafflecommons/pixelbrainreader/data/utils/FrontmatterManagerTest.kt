package cloud.wafflecommons.pixelbrainreader.data.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class FrontmatterManagerTest {

    @Test
    fun extractFrontmatter_ParsesStandardTags() {
        val input = """
            ---
            title: Hello World
            tags: [a, b, c]
            ---
            # Content
        """.trimIndent()
        
        val (metadata, body) = FrontmatterManager.extractFrontmatterAndBody(input)
        assertEquals("Hello World", metadata["title"])
        assertEquals(listOf("a", "b", "c"), metadata["tags"])
        assertEquals("# Content", body.trim())
    }

    @Test
    fun updateFrontmatter_PreservesMidFileHorizontalRule() {
        val input = """
            ---
            title: Note
            ---
            # Section 1
            
            ---
            
            # Section 2
        """.trimIndent()

        val updated = FrontmatterManager.updateFrontmatter(input, mapOf("mood_score" to 5))
        val (metadata, body) = FrontmatterManager.extractFrontmatterAndBody(updated)
        assertEquals("Note", metadata["title"])
        assertEquals(5, metadata["mood_score"])
        org.junit.Assert.assertTrue(body.contains("# Section 1"))
        org.junit.Assert.assertTrue(body.contains("---"))
        org.junit.Assert.assertTrue(body.contains("# Section 2"))
    }
    
    @Test
    fun stripFrontmatter_RemovesFirstBlock() {
        // Block B (PixelBrain) - If it's the first block, it should be stripped.
        val input = """
            ---
            pixel_brain_log: true
            meta: data
            ---
            # Content
        """.trimIndent()
        
        val expected = "\n# Content"
        val result = FrontmatterManager.stripFrontmatter(input)
        
        assertEquals(expected.trim(), result.trim()) 
    }

    @Test
    fun stripFrontmatter_RemovesStandardBlock() {
        // Block A (Standard) - Should NOW be stripped for display
        val input = """
            ---
            title: My Note
            tags: [journal]
            ---
            # Content
        """.trimIndent()
        
        // Expect content only
        val expected = "\n# Content"
        val result = FrontmatterManager.stripFrontmatter(input)
        
        assertEquals(expected.trim(), result.trim()) 
    }

    @Test
    fun stripFrontmatter_NoYaml_ReturnsOriginal() {
        val input = "# Just Content"
        val result = FrontmatterManager.stripFrontmatter(input)
        assertEquals(input, result)
    }
}
