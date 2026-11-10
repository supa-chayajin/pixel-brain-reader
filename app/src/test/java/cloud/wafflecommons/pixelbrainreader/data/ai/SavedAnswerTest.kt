package cloud.wafflecommons.pixelbrainreader.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/** A saved chat answer keeps its sources as links, never naming a private note (E13, 12.9.0). */
class SavedAnswerTest {

    @Test
    fun `the notes the model saw become path links, once each, in order`() {
        val section = SavedAnswer.sourcesSection(
            listOf("20_Projects/21_Demo/README.md", "30_Areas/runbook.md", "20_Projects/21_Demo/README.md")
        )
        assertEquals("\n## Sources\n\n- [[20_Projects/21_Demo/README]]\n- [[30_Areas/runbook]]\n", section)
    }

    @Test
    fun `a private source is never named in the clear note`() {
        val section = SavedAnswer.sourcesSection(listOf("99_Private/2026-11-10.md.enc", "10_Journal/2026-11-09.md", "notes/secret.md.enc"))
        assertEquals("\n## Sources\n\n- [[10_Journal/2026-11-09]]\n", section)
    }

    @Test
    fun `no source, no section`() {
        assertEquals("", SavedAnswer.sourcesSection(emptyList()))
        assertEquals("", SavedAnswer.sourcesSection(listOf("99_Private/People/alias.md.enc")))
    }
}
