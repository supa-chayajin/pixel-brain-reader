package cloud.wafflecommons.pixelbrainreader.data.web

import cloud.wafflecommons.pixelbrainreader.data.work.DraftWriter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The share's content kept beside its import (13.3, ME2): the share screen offers the shared link
 * as a game's map from the moment it opens, while the page loads or after it failed, until the
 * share is dismissed or saved. The test's own scope stands for the app scope: advanceUntilIdle()
 * runs its jobs, where it would leave backgroundScope's waiting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareImportRepositoryTest {

    private val loader = mockk<WebPageLoader>()
    private val draftWriter = mockk<DraftWriter>()
    private val summarizer = mockk<ClipSummarizer>()

    private val link = "https://ringofash.wiki.example.com/Interactive+Map"

    /** What a browser shares: the page's address, its title as the subject. */
    private val shared = SharedContent.of(link, "Interactive Map | Ring Wiki")

    private fun article() = WebClip(
        url = link,
        title = "Interactive Map",
        siteName = "Ring Wiki",
        author = null,
        published = null,
        description = null,
        heroImage = null,
        markdown = "Every region of the game, its bosses and its shortcuts. ".repeat(25),
        words = 200
    )

    @Test
    fun `a share keeps its link and title until dismissed`() = runTest {
        coEvery { loader.load(link, any()) } returns article()
        val repository = ShareImportRepository(loader, draftWriter, summarizer, this)

        repository.start(shared, null)

        // At once, before the page is read: the screen can offer the link while it loads.
        assertTrue(repository.state.value is ShareImport.Loading)
        assertEquals(link, repository.shared.value?.url)
        assertEquals("Interactive Map | Ring Wiki", repository.shared.value?.titleHint)

        advanceUntilIdle()
        assertTrue(repository.state.value is ShareImport.Article)
        assertEquals(shared, repository.shared.value)

        repository.dismiss()
        assertNull(repository.shared.value)
        assertNull(repository.state.value)
    }

    @Test
    fun `a page that can't be read, then read as shown, keeps the share`() = runTest {
        coEvery { loader.load(link, any()) } throws PageUnavailableException(PageProblem.BLOCKED, 403)
        coEvery { loader.clipRendered(any(), link) } returns article()
        val repository = ShareImportRepository(loader, draftWriter, summarizer, this)

        repository.start(shared, null)
        advanceUntilIdle()
        assertTrue(repository.state.value is ShareImport.LinkOnly)
        assertEquals(shared, repository.shared.value)

        repository.importRendered("<html><body><p>The map.</p></body></html>", link)
        advanceUntilIdle()
        assertTrue(repository.state.value is ShareImport.Article)
        assertEquals(shared, repository.shared.value)
    }

    @Test
    fun `a new share replaces the last one`() = runTest {
        coEvery { loader.load(any(), any()) } returns article()
        val repository = ShareImportRepository(loader, draftWriter, summarizer, this)
        val other = SharedContent.of("https://brickwars.wiki.example.org/Maps", "Maps | Brick Wars Wiki")

        repository.start(shared, null)
        repository.start(other, null)
        advanceUntilIdle()

        assertEquals(other, repository.shared.value)
    }

    @Test
    fun `saving clears it`() = runTest {
        coEvery { loader.load(link, any()) } returns article()
        coEvery { draftWriter.write(any(), any()) } answers { firstArg() }
        val repository = ShareImportRepository(loader, draftWriter, summarizer, this)
        repository.start(shared, null)
        advanceUntilIdle()

        val path = repository.save("Interactive Map", "00_Inbox", editedMarkdown = null, summarize = false)

        assertEquals("00_Inbox/Interactive Map.md", path)
        assertNull(repository.shared.value)
        assertNull(repository.state.value)
        coVerify(exactly = 0) { summarizer.summarize(any()) }
    }
}
