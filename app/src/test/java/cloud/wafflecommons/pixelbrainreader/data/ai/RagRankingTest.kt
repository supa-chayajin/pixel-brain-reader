package cloud.wafflecommons.pixelbrainreader.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class RagRankingTest {

    /** A unit vector at [degrees] from the x axis, padded to 384 dims: cos(angle) is the similarity. */
    private fun unit(degrees: Double): FloatArray {
        val rad = Math.toRadians(degrees)
        return FloatArray(384).also { it[0] = cos(rad).toFloat(); it[1] = sin(rad).toFloat() }
    }

    private fun chunk(id: String, fileId: String, vector: FloatArray) = IndexedChunk(id, fileId, false, vector)

    private val query = unit(0.0)

    @Test
    fun `the relevance floor is the calibrated 0_35 - settled decision`() {
        // Lowering it (0.22 + a path boost, in 866e0d5) fed unrelated notes to the model as context.
        assertEquals(0.35f, RagRanking.MIN_SIMILARITY)
    }

    @Test
    fun `only chunks clearing the floor are relevant, best first`() {
        val ranking = RagRanking.rank(
            query,
            listOf(
                chunk("weak", "a.md", unit(80.0)), // cos ≈ 0.17
                chunk("best", "b.md", unit(10.0)), // cos ≈ 0.98
                chunk("ok", "c.md", unit(60.0)) // cos = 0.50
            )
        )

        assertNull(ranking.rejection)
        assertEquals(listOf("best", "ok"), ranking.relevant.map { it.chunk.id })
        assertEquals(3, ranking.scored.size)
    }

    @Test
    fun `nothing above the floor means an empty result, never the closest unrelated note`() {
        val ranking = RagRanking.rank(query, listOf(chunk("a", "10_Journal/x.md", unit(75.0)), chunk("b", "y.md", unit(85.0))))

        assertTrue(ranking.relevant.isEmpty())
        assertNotNull(ranking.rejection)
    }

    @Test
    fun `the note's path never changes its score - no lexical boost`() {
        // A query about "journal" used to boost every 10_Journal chunk by +0.25.
        val ranking = RagRanking.rank(
            query,
            listOf(
                chunk("in-journal", "10_Journal/journal.md", unit(70.0)), // cos ≈ 0.34, just below the floor
                chunk("elsewhere", "z.md", unit(75.0)) // cos ≈ 0.26
            )
        )
        assertTrue(ranking.relevant.isEmpty())
        assertTrue(ranking.rejection!!.contains("cleared"))
    }

    @Test
    fun `a degenerate query vector is rejected`() {
        val ranking = RagRanking.rank(FloatArray(384), listOf(chunk("a", "a.md", unit(0.0))))
        assertTrue(ranking.relevant.isEmpty())
        assertNotNull(ranking.rejection)
    }

    @Test
    fun `a collapsed embedding space is rejected`() {
        val ranking = RagRanking.rank(query, listOf(chunk("a", "a.md", unit(5.0)), chunk("b", "b.md", unit(5.1))))
        assertTrue(ranking.relevant.isEmpty())
        assertTrue(ranking.rejection!!.contains("collapsed"))
    }

    @Test
    fun `stale-dimension rows are skipped, not scored zero`() {
        val ranking = RagRanking.rank(query, listOf(chunk("old", "a.md", FloatArray(100) { 1f }), chunk("new", "b.md", unit(0.0))))
        assertEquals(listOf("new"), ranking.relevant.map { it.chunk.id })
    }
}
