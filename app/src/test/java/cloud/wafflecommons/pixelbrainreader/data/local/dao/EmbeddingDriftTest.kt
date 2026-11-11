package cloud.wafflecommons.pixelbrainreader.data.local.dao

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * A note rewritten with the same content (a pull, a rebase) keeps vectors that were current; it is
 * never re-embedded for a file time alone. Stale or pending vectors are left for the indexer.
 */
class EmbeddingDriftTest {

    private val dao: EmbeddingDao = mockk(relaxed = true)

    @Test
    fun `current vectors follow the note's new file time`() = runTest {
        coEvery { dao.oldestStamp("a.md") } returns 1_000L

        dao.absorbMtimeDrift("a.md", oldMtime = 900L, newMtime = 5_000L)

        coVerify { dao.restamp("a.md", 5_000L) }
    }

    @Test
    fun `stale or pending vectors are left for the indexer`() = runTest {
        coEvery { dao.oldestStamp("a.md") } returns 0L // marked stale, or kept by a stopped run

        dao.absorbMtimeDrift("a.md", oldMtime = 900L, newMtime = 5_000L)

        coVerify(exactly = 0) { dao.restamp(any(), any()) }
    }

    @Test
    fun `vectors older than the note's last change are left for the indexer`() = runTest {
        coEvery { dao.oldestStamp("a.md") } returns 800L // embedded before the edit at 900

        dao.absorbMtimeDrift("a.md", oldMtime = 900L, newMtime = 5_000L)

        coVerify(exactly = 0) { dao.restamp(any(), any()) }
    }

    @Test
    fun `a note without vectors has nothing to follow`() = runTest {
        coEvery { dao.oldestStamp("a.md") } returns null

        dao.absorbMtimeDrift("a.md", oldMtime = 900L, newMtime = 5_000L)

        coVerify(exactly = 0) { dao.restamp(any(), any()) }
    }
}
