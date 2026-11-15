package cloud.wafflecommons.pixelbrainreader.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class HybridRankingTest {

    private data class Passage(val id: String, val note: String)

    private fun fuse(meaning: List<Passage>, words: List<String>) =
        HybridRanking.fuse(meaning, { it.note }, words).map { it.id }

    @Test
    fun `nothing found by meaning stays empty whatever the words find`() {
        assertEquals(emptyList<String>(), fuse(emptyList(), listOf("a.md", "b.md")))
    }

    @Test
    fun `words never bring in a note meaning did not find`() {
        val meaning = listOf(Passage("1", "a.md"), Passage("2", "b.md"))
        val fused = fuse(meaning, listOf("z.md", "y.md", "b.md"))
        assertEquals(setOf("1", "2"), fused.toSet())
        assertEquals(2, fused.size)
    }

    @Test
    fun `a note sharing the question's words climbs above one that shares none`() {
        val meaning = (0 until 10).map { Passage("p$it", "n$it.md") }
        val fused = fuse(meaning, listOf("n7.md"))
        assertEquals("p7", fused.first())
        // The rest keep their meaning order.
        assertEquals(listOf("p0", "p1", "p2", "p3", "p4", "p5", "p6", "p8", "p9"), fused.drop(1))
    }

    @Test
    fun `without any shared word the meaning order is kept`() {
        val meaning = listOf(Passage("1", "a.md"), Passage("2", "b.md"), Passage("3", "c.md"))
        assertEquals(listOf("1", "2", "3"), fuse(meaning, emptyList()))
        assertEquals(listOf("1", "2", "3"), fuse(meaning, listOf("elsewhere.md")))
    }

    @Test
    fun `both ranks count, so the better word rank wins between two word matches`() {
        val meaning = listOf(Passage("1", "a.md"), Passage("2", "b.md"), Passage("3", "c.md"))
        // c is words' first, a words' fourth: 1/61 + 1/64 (a) < 1/63 + 1/61 (c).
        assertEquals(listOf("3", "1", "2"), fuse(meaning, listOf("c.md", "x.md", "y.md", "a.md")))
        // Equal sums keep the meaning order: a at meaning 0 / words 2 ties c at meaning 2 / words 0.
        assertEquals(listOf("1", "3", "2"), fuse(meaning, listOf("c.md", "x.md", "a.md")))
    }

    @Test
    fun `every passage of a note gets its note's word rank, and a repeated path counts once`() {
        val meaning = listOf(Passage("a1", "a.md"), Passage("b1", "b.md"), Passage("b2", "b.md"), Passage("c1", "c.md"))
        assertEquals(listOf("b1", "b2", "a1", "c1"), fuse(meaning, listOf("b.md", "b.md")))
    }
}
