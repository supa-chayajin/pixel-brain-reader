package cloud.wafflecommons.pixelbrainreader.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FullTextSearchTest {

    @Test
    fun `typed words become prefix terms`() {
        assertEquals("jgit* reba*", FtsQuery.build("JGit reba"))
        assertEquals("réseau*", FtsQuery.build("  réseau  "))
    }

    @Test
    fun `fts syntax never reaches the match expression`() {
        assertEquals("a* or* b* near* c*", FtsQuery.build("\"a\" OR b NEAR -c*"))
        assertEquals("content* x*", FtsQuery.build("content:x"))
        assertNull(FtsQuery.build("*** -- \"\""))
    }

    @Test
    fun `duplicate words are searched once`() {
        assertEquals("git*", FtsQuery.build("git Git GIT"))
    }

    /** matchinfo('pcnalx') for one phrase over (path, content). */
    private fun matchInfo(rows: Int, avgPath: Int, avgBody: Int, lenPath: Int, lenBody: Int,
                          hitsPath: Int, docsPath: Int, hitsBody: Int, docsBody: Int) = intArrayOf(
        1, 2, rows,
        avgPath, avgBody,
        lenPath, lenBody,
        hitsPath, hitsPath, docsPath,
        hitsBody, hitsBody, docsBody
    )

    @Test
    fun `more occurrences score higher`() {
        val once = Bm25.score(matchInfo(100, 5, 200, 5, 200, 0, 1, 1, 10))
        val thrice = Bm25.score(matchInfo(100, 5, 200, 5, 200, 0, 1, 3, 10))
        assertTrue(thrice > once)
    }

    @Test
    fun `rare terms weigh more than common ones`() {
        val rare = Bm25.score(matchInfo(100, 5, 200, 5, 200, 0, 1, 1, 2))
        val common = Bm25.score(matchInfo(100, 5, 200, 5, 200, 0, 1, 1, 80))
        assertTrue(rare > common)
    }

    @Test
    fun `a hit in the weighted path column outranks one in the body`() {
        val weights = doubleArrayOf(2.0, 1.0)
        val inPath = Bm25.score(matchInfo(100, 5, 200, 5, 200, 1, 3, 0, 3), weights)
        val inBody = Bm25.score(matchInfo(100, 5, 200, 5, 200, 0, 3, 1, 3), weights)
        assertTrue(inPath > inBody)
    }

    @Test
    fun `short documents beat long ones at equal counts`() {
        val short = Bm25.score(matchInfo(100, 5, 200, 5, 50, 0, 1, 2, 10))
        val long = Bm25.score(matchInfo(100, 5, 200, 5, 2000, 0, 1, 2, 10))
        assertTrue(short > long)
    }

    @Test
    fun `truncated matchinfo scores zero instead of crashing`() {
        assertEquals(0.0, Bm25.score(intArrayOf(1, 2)), 0.0)
        assertEquals(0.0, Bm25.score(intArrayOf(1, 2, 10, 1)), 0.0)
    }

    @Test
    fun `the blob decodes in native byte order`() {
        val buffer = ByteBuffer.allocate(12).order(ByteOrder.nativeOrder())
        buffer.putInt(1).putInt(2).putInt(300)
        assertEquals(listOf(1, 2, 300), Bm25.decode(buffer.array()).toList())
    }
}
