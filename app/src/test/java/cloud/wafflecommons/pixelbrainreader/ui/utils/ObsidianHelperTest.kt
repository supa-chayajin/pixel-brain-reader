package cloud.wafflecommons.pixelbrainreader.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObsidianHelperTest {

    @Test
    fun parse_ExtractsFrontmatterAndPreservesMidFileHorizontalRule() {
        val raw = """
            ---
            title: Daily Journal
            tags: [journal, daily]
            ---
            # Entry
            
            ---
            
            Footer
        """.trimIndent()

        val parsed = ObsidianHelper.parse(raw)
        assertEquals("Daily Journal", parsed.metadata["title"])
        assertEquals(listOf("journal", "daily"), parsed.tags)
        assertTrue(parsed.cleanContent.startsWith("# Entry"))
        assertTrue(parsed.cleanContent.contains("---"))
        assertTrue(parsed.cleanContent.endsWith("Footer"))
    }

    @Test
    fun WikiLink_Regex_Matches() {
        val simpleMatch = ObsidianHelper.WIKI_LINK_REGEX.find("[[My Note]]")
        assertNotNull(simpleMatch)
        assertEquals("My Note", simpleMatch?.groupValues?.get(1))

        val aliasMatch = ObsidianHelper.WIKI_LINK_REGEX.find("[[My Note|Alias]]")
        assertNotNull(aliasMatch)
        assertEquals("My Note", aliasMatch?.groupValues?.get(1))
        assertEquals("Alias", aliasMatch?.groupValues?.get(2))
    }
}
