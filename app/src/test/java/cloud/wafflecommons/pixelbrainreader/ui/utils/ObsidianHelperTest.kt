package cloud.wafflecommons.pixelbrainreader.ui.utils

import org.junit.Assert.assertTrue
import org.junit.Test

class ObsidianHelperTest {

    @Test
    fun `processCallouts_GeneratesCorrectHtml`() {
        // CALLOUT_REGEX in production requires a space after [!type]
        val content = "> [!info] My Title\n> Some info content"
        
        val result = ObsidianHelper.parse(content)
        val html = result.cleanContent
        
        // Check basics
        assertTrue(html.contains("background-color: rgba(33, 150, 243, 0.12)"))
        assertTrue(html.contains("border-left: 5px solid #2196F3"))
        assertTrue(html.contains("ℹ️"))
        assertTrue(html.contains("My Title"))
        assertTrue(html.contains("Some info content"))
    }

    @Test
    fun `processCallouts_HandlesDifferentTypes`() {
        val types = listOf(
            "tip" to "💡",
            "success" to "✅",
            "warning" to "⚠️",
            "danger" to "🐞"
        )
        
        for ((type, icon) in types) {
            // Added space after ] to match "^>\\s\\[!(\\w+)\\]\\s(.*)$"
            val content = "> [!$type] \n> Content"
            val result = ObsidianHelper.parse(content)
            assertTrue("Should contain $icon for $type. Result: ${result.cleanContent}", result.cleanContent.contains(icon))
        }
    }
}
