package cloud.wafflecommons.pixelbrainreader.data.watch

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/** The feeds live in the vault's `feeds.json` (D5): disk first, an unreadable file kept aside, no write on a read-only device. */
class FeedConfigTest {

    private val dev = Feed("https://dev.example/feed", "Dev", work = true)
    private val local = Feed("https://news.example/rss")

    @Test
    fun `a feed is added once, removed, and marked Work`() {
        val config = FeedConfig().add(dev).add(local).add(Feed("HTTPS://DEV.EXAMPLE/FEED", "Twice"))
        assertEquals(listOf(dev, local), config.feeds)
        assertEquals(listOf(local), config.remove("https://dev.example/feed").feeds)
        assertEquals(true, config.setWork(local.url, true).feeds.last().work)
    }

    @Test
    fun `a Work feed hides while work is out of the picture`() {
        val config = FeedConfig(listOf(dev, local))
        assertEquals(listOf(local), config.visible(workHidden = true))
        assertEquals(listOf(dev, local), config.visible(workHidden = false))
    }

    @Test
    fun `a feed without a name shows its host`() {
        assertEquals("news.example", local.label)
        assertEquals("Dev", dev.label)
    }

    // --- the vault file ------------------------------------------------------------------

    private var stored: String? = null
    private val fileRepository: FileRepository = mockk {
        coEvery { readFile(FeedConfigRepository.PATH) } answers { stored }
        coEvery { saveFileLocally(FeedConfigRepository.PATH, any()) } answers { stored = secondArg() }
        every { moveAsideCorrupt(FeedConfigRepository.PATH) } answers { stored = null; File("feeds.json.corrupt") }
    }
    private val pushScheduler: VaultPushScheduler = mockk(relaxed = true)
    private val repository = FeedConfigRepository(fileRepository, pushScheduler)

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        VaultWriteGate.readOnly = false
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `no file is no feed, and reading writes nothing`() = runTest {
        assertEquals(FeedConfig(), repository.read())
        assertNull(stored)
    }

    @Test
    fun `a change starts from the file on disk and is pushed`() = runTest {
        stored = """{ "feeds": [ { "url": "https://news.example/rss" } ] }"""

        val saved = repository.update { it.add(dev) }

        assertEquals(listOf(local, dev), saved?.feeds)
        assertEquals(saved, repository.read())
        verify { pushScheduler.requestPush() }
    }

    @Test
    fun `an unreadable file is moved aside, never overwritten`() = runTest {
        stored = "{ feeds: [ oops"

        assertEquals(FeedConfig(), repository.read())
        val saved = repository.update { it.add(dev) }

        verify { fileRepository.moveAsideCorrupt(FeedConfigRepository.PATH) }
        assertEquals(listOf(dev), saved?.feeds)
    }

    @Test
    fun `a read-only device changes nothing`() = runTest {
        VaultWriteGate.readOnly = true

        assertNull(repository.update { it.add(dev) })

        coVerify(exactly = 0) { fileRepository.saveFileLocally(any(), any()) }
    }
}
