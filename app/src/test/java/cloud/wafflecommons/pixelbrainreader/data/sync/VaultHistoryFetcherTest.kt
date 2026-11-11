package cloud.wafflecommons.pixelbrainreader.data.sync

import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.errors.TransportException
import org.junit.Test

/** The history after a shallow clone: step after step, a dropped step retried, then left for the next sync. */
class VaultHistoryFetcherTest {

    private val jGit = mockk<JGitProvider>()
    private val fetcher = VaultHistoryFetcher(jGit, CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))

    private fun step(complete: Boolean, commits: Int) = Result.success(JGitProvider.HistoryProgress(complete, commits))
    private val dropped = Result.failure<JGitProvider.HistoryProgress>(TransportException("Connection reset", java.net.SocketException("Connection reset")))

    @Test
    fun `steps until the history is complete, past a dropped one`() = runTest {
        coEvery { jGit.deepenHistory(any(), any()) } returnsMany listOf(step(false, 1001), dropped, step(false, 2001), step(true, 2400))

        fetcher.fetchRemaining(pauseMs = { 0L })

        coVerify(exactly = 4) { jGit.deepenHistory(any(), any()) }
    }

    @Test
    fun `a connection that keeps dropping leaves the rest for the next sync`() = runTest {
        coEvery { jGit.deepenHistory(any(), any()) } returns dropped

        fetcher.fetchRemaining(pauseMs = { 0L })

        coVerify(exactly = 4) { jGit.deepenHistory(any(), any()) }
    }

    @Test
    fun `a refused token stops at once`() = runTest {
        coEvery { jGit.deepenHistory(any(), any()) } returns Result.failure(TransportException("https://github.com/o/r.git: not authorized"))

        fetcher.fetchRemaining(pauseMs = { 0L })

        coVerify(exactly = 1) { jGit.deepenHistory(any(), any()) }
    }

    @Test
    fun `a complete history costs one check`() = runTest {
        coEvery { jGit.deepenHistory(any(), any()) } returns step(true, 10_400)

        fetcher.fetchRemaining(pauseMs = { 0L })

        coVerify(exactly = 1) { jGit.deepenHistory(any(), any()) }
    }
}
