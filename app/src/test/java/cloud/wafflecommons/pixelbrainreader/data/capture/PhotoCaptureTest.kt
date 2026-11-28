package cloud.wafflecommons.pixelbrainreader.data.capture

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.repository.AssetRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.CaptureRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.NoteRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.data.utils.SafeFileProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

class PhotoCaptureTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a photo is kept at most 1600 on its longest side, its shape kept`() {
        assertEquals(1600 to 1200, PhotoCapture.targetSize(4000, 3000))
        assertEquals(900 to 1600, PhotoCapture.targetSize(2250, 4000))
        assertEquals(800 to 600, PhotoCapture.targetSize(800, 600))
        assertEquals(0 to 0, PhotoCapture.targetSize(0, 0))
    }

    @Test
    fun `a game picture is kept at most 768 on its longest side`() {
        assertEquals(768, PhotoCapture.COVER_SIDE)
        assertEquals(85, PhotoCapture.COVER_QUALITY)
        assertEquals(768 to 576, PhotoCapture.targetSize(4000, 3000, PhotoCapture.COVER_SIDE))
        assertEquals(384 to 768, PhotoCapture.targetSize(1000, 2000, PhotoCapture.COVER_SIDE))
        assertEquals(768 to 768, PhotoCapture.targetSize(1024, 1024, PhotoCapture.COVER_SIDE))
        // A smaller picture keeps its own size.
        assertEquals(500 to 300, PhotoCapture.targetSize(500, 300, PhotoCapture.COVER_SIDE))
    }

    @Test
    fun `what the photo said comes after what was typed`() {
        assertEquals("Le tableau du jeudi\n\nLundi : réunion", PhotoCapture.withRead("Le tableau du jeudi  ", "  Lundi : réunion\n"))
        assertEquals("Lundi : réunion", PhotoCapture.withRead("", "Lundi : réunion"))
        assertEquals("Mon texte", PhotoCapture.withRead("Mon texte", "   "))
    }

    @Test
    fun `the note shows the photo first, then the text`() {
        assertEquals("![[Pasted image 1.jpg]]\n\nLundi\n", PhotoCapture.body("Pasted image 1.jpg", " Lundi "))
        assertEquals("![[Pasted image 1.jpg]]\n", PhotoCapture.body("Pasted image 1.jpg", ""))
    }

    @Test
    fun `a shared image is read only from another app's provider`() {
        val own = setOf("cloud.wafflecommons.pixelbrainreader.fileprovider", "cloud.wafflecommons.pixelbrainreader.androidx-startup")
        assertTrue(PhotoCapture.isForeign("content", "com.google.android.apps.photos.contentprovider", own))
        assertFalse(PhotoCapture.isForeign("content", "cloud.wafflecommons.pixelbrainreader.fileprovider", own))
        // A user prefix reaches the same provider: still one of ours.
        assertFalse(PhotoCapture.isForeign("content", "0@cloud.wafflecommons.pixelbrainreader.fileprovider", own))
        assertFalse(PhotoCapture.isForeign("content", "0@", own))
        assertTrue(PhotoCapture.isForeign("content", "0@com.google.android.apps.photos.contentprovider", own))
        assertFalse(PhotoCapture.isForeign("file", null, own))
        assertFalse(PhotoCapture.isForeign("content", null, own))
        assertFalse(PhotoCapture.isForeign("content", " ", own))
    }

    @Test
    fun `a photo capture keeps the photo, then writes the note that shows it`() = runBlocking {
        val filesDir = temp.newFolder("filesDir")
        File(filesDir, "vault/.git").mkdirs()
        val context = mockk<Context> { every { this@mockk.filesDir } returns filesDir }
        val notes = mockk<NoteRepository>(relaxed = true)
        val assets = mockk<AssetRepository>()
        val jpeg = byteArrayOf(1, 2, 3)
        coEvery { assets.saveDropped("00_Inbox/Capture 2026-11-15 2130.md", "image/jpeg", null, any()) } returns
            "00_Inbox/attachments/Pasted image 20261115213000.jpg"
        val repository = CaptureRepository(notes, SafeFileProvider(context), mockk(relaxed = true), assets)

        val path = repository.savePhotoNote(LocalDateTime.of(2026, 11, 15, 21, 30), "Lundi : réunion", jpeg, "tile")

        assertEquals("00_Inbox/Capture 2026-11-15 2130.md", path)
        val written = slot<String>()
        coVerify { notes.saveNote(path, capture(written)) }
        assertTrue(written.captured.contains("  - photo\n"))
        assertTrue(written.captured.contains("![[Pasted image 20261115213000.jpg]]\n\nLundi : réunion\n"))
    }

    @Test
    fun `no note when the photo couldn't be kept`() = runBlocking {
        val filesDir = temp.newFolder("filesDir2")
        File(filesDir, "vault/.git").mkdirs()
        val context = mockk<Context> { every { this@mockk.filesDir } returns filesDir }
        val notes = mockk<NoteRepository>(relaxed = true)
        val assets = mockk<AssetRepository> { coEvery { saveDropped(any(), any(), any(), any()) } returns null }
        val pushes = mockk<VaultPushScheduler>(relaxed = true)
        val repository = CaptureRepository(notes, SafeFileProvider(context), pushes, assets)

        val failed = runCatching { repository.savePhotoNote(LocalDateTime.of(2026, 11, 15, 21, 30), "x", byteArrayOf(1), "tile") }

        assertTrue(failed.isFailure)
        coVerify(exactly = 0) { notes.saveNote(any(), any()) }
    }

    @Test
    fun `nothing is saved before the vault's first clone, which would replace the folder`() = runBlocking {
        val filesDir = temp.newFolder("filesDir3")
        val context = mockk<Context> { every { this@mockk.filesDir } returns filesDir }
        val notes = mockk<NoteRepository>(relaxed = true)
        val assets = mockk<AssetRepository>(relaxed = true)
        val pushes = mockk<VaultPushScheduler>(relaxed = true)
        val repository = CaptureRepository(notes, SafeFileProvider(context), pushes, assets)
        val at = LocalDateTime.of(2026, 11, 15, 21, 30)

        val photo = runCatching { repository.savePhotoNote(at, "x", byteArrayOf(1), "share") }
        val typed = runCatching { repository.saveQuickNote(at, "x", "notes") }

        assertTrue(photo.exceptionOrNull() is CaptureRepository.NoVaultYetException)
        assertTrue(typed.exceptionOrNull() is CaptureRepository.NoVaultYetException)
        coVerify(exactly = 0) { assets.saveDropped(any(), any(), any(), any()) }
        coVerify(exactly = 0) { notes.saveNote(any(), any()) }
        coVerify(exactly = 0) { pushes.requestPush() }
    }
}
