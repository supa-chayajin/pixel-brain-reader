package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoReviewStoreTest {

    private val day = 24L * 60 * 60 * 1000
    private var onDisk: String? = null
    private val noteRepository: NoteRepository = mockk {
        coEvery { getNoteContent(EchoReviewStore.PATH) } answers { onDisk }
        val written = slot<String>()
        coEvery { saveNote(EchoReviewStore.PATH, capture(written)) } answers { onDisk = written.captured }
    }
    private val fileRepository: FileRepository = mockk { every { moveAsideCorrupt(any()) } returns null }
    private val pushScheduler: VaultPushScheduler = mockk(relaxed = true)
    private val store = EchoReviewStore(noteRepository, fileRepository, pushScheduler)

    @Test
    fun `a review is written to the vault, read back, and queues a push`() = runBlocking {
        store.markReviewed("30_Areas/a.md", now = 100 * day)

        assertEquals(mapOf("30_Areas/a.md" to 100 * day), store.reviewedAt())
        verify { pushScheduler.requestPush() }
    }

    @Test
    fun `old reviews are pruned and newer ones kept when another note is reviewed`() = runBlocking {
        store.markReviewed("old.md", now = 10 * day)
        store.markReviewed("recent.md", now = 90 * day)
        store.markReviewed("new.md", now = 100 * day)

        assertEquals(setOf("recent.md", "new.md"), store.reviewedAt().keys)
    }

    @Test
    fun `an unreadable file is moved aside before it is rewritten`() = runBlocking {
        onDisk = "{ not json"

        store.markReviewed("a.md", now = day)

        coVerify { fileRepository.moveAsideCorrupt(EchoReviewStore.PATH) }
        assertTrue(onDisk!!.contains("a.md"))
    }
}
