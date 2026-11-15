package cloud.wafflecommons.pixelbrainreader.data.ai

import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository
import cloud.wafflecommons.pixelbrainreader.data.search.FullTextHit
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteRetrievalTest {

    private val vectors: VectorSearchEngine = mockk()
    private val discovery: VaultDiscoveryRepository = mockk()
    private val prefs: UserPreferencesRepository = mockk()
    private val retrieval = NoteRetrieval(vectors, discovery, prefs)

    private fun hit(path: String) = SearchHit(path, "text of $path", isPrivate = false)
    private fun words(vararg paths: String) = paths.map { FullTextHit(it, score = 1.0, excerpt = "") }

    @Test
    fun `off, it is the meaning search alone at the size asked`() = runTest {
        every { prefs.hybridSearch } returns flowOf(false)
        coEvery { vectors.search("q", limit = 6) } returns listOf(hit("a.md"))

        assertEquals(listOf("a.md"), retrieval.search("q", 6).map { it.fileId })
        coVerify(exactly = 0) { discovery.fullTextHits(any(), any(), any()) }
    }

    @Test
    fun `on, twenty candidates by meaning re-ranked by words, then the size asked`() = runTest {
        every { prefs.hybridSearch } returns flowOf(true)
        coEvery { vectors.search("q", limit = 20) } returns (0 until 20).map { hit("n$it.md") }
        coEvery { discovery.fullTextHits("q", limit = 20, anyTerm = true) } returns words("elsewhere.md", "n12.md")

        assertEquals(
            listOf("n12.md", "n0.md", "n1.md", "n2.md", "n3.md", "n4.md"),
            retrieval.search("q", 6).map { it.fileId }
        )
    }

    @Test
    fun `on, nothing by meaning stays nothing and the words are not even asked`() = runTest {
        every { prefs.hybridSearch } returns flowOf(true)
        coEvery { vectors.search("q", limit = 20) } returns emptyList()

        assertEquals(emptyList<SearchHit>(), retrieval.search("q", 6))
        coVerify(exactly = 0) { discovery.fullTextHits(any(), any(), any()) }
    }

    @Test
    fun `a failing word search leaves the meaning order`() = runTest {
        every { prefs.hybridSearch } returns flowOf(true)
        coEvery { vectors.search("q", limit = 20) } returns listOf(hit("a.md"), hit("b.md"))
        coEvery { discovery.fullTextHits(any(), any(), any()) } throws IllegalStateException("fts")

        assertEquals(listOf("a.md", "b.md"), retrieval.search("q", 6).map { it.fileId })
    }
}
