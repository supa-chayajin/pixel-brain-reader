package cloud.wafflecommons.pixelbrainreader.ui.ai

import cloud.wafflecommons.pixelbrainreader.data.ai.AiLanguage
import cloud.wafflecommons.pixelbrainreader.data.ai.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPromptTest {

    private fun hit(file: String, size: Int) = SearchHit(file, "x".repeat(size), isPrivate = false)

    @Test
    fun `every chat persona carries the French-answer directive`() {
        ChatMode.entries.forEach { mode ->
            assertTrue("$mode persona must end with the directive", personaFor(mode).trim().endsWith(AiLanguage.SYSTEM_DIRECTIVE))
        }
    }

    @Test
    fun `the grounded persona never invites general-knowledge answers`() {
        val cortex = personaFor(ChatMode.ORACLE)
        assertTrue(cortex.contains("N'invente rien"))
        assertFalse(cortex.contains("connaissances générales"))
    }

    @Test
    fun `retrieved notes are cut at the budget, keeping whole chunks best first`() {
        val (context, used) = buildRagContext(
            listOf(hit("a.md", 1_200), hit("b.md", 1_200), hit("c.md", 1_200)),
            budget = 3_000
        )

        assertEquals(listOf("a.md", "b.md"), used.map { it.fileId })
        assertTrue(context!!.length <= 3_000)
    }

    @Test
    fun `an oversized best chunk is truncated rather than dropped`() {
        val (context, used) = buildRagContext(listOf(hit("big.md", 5_000)), budget = 3_000)
        assertEquals(3_000, context!!.length)
        assertEquals(listOf("big.md"), used.map { it.fileId })
    }

    @Test
    fun `no hits means no context block at all`() {
        val (context, used) = buildRagContext(emptyList())
        assertNull(context)
        assertTrue(used.isEmpty())
    }
}
