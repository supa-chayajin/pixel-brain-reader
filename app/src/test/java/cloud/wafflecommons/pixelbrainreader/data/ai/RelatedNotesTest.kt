package cloud.wafflecommons.pixelbrainreader.data.ai

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelatedNotesTest {

    private val vectors: VectorSearchEngine = mockk()
    private val related = RelatedNotes(vectors)

    private fun hit(path: String) = SearchHit(path, "text", isPrivate = false)

    @Test
    fun `three distinct notes, never the note being written nor a private one`() = runTest {
        val within = slot<(String) -> Boolean>()
        coEvery { vectors.search("paragraph", limit = 12, within = capture(within)) } returns listOf(
            hit("a.md"), hit("a.md"), hit("b.md"), hit("c.md"), hit("d.md")
        )

        assertEquals(listOf("a.md", "b.md", "c.md"), related.closestTo("paragraph", "me.md"))
        assertFalse(within.captured("me.md"))
        assertFalse(within.captured("99_Private/Journal.md"))
        assertTrue(within.captured("20_Projects/plan.md"))
    }
}
