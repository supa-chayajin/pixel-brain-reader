package cloud.wafflecommons.pixelbrainreader.data.repository

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.ai.CompiledVoiceResult
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.data.utils.SafeFileProvider
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

class CaptureRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val capturedAt = LocalDateTime.of(2026, 10, 17, 14, 32, 45)
    private val compiled = CompiledVoiceResult(
        rawTranscript = "acheter du pain\net penser au projet",
        structuredMarkdown = "- Penser au projet",
        detectedTasks = listOf("Acheter du pain")
    )

    @Test
    fun `a voice note goes to the inbox with a unique name and queues a push`() = runBlocking {
        val filesDir = temp.newFolder("filesDir")
        val context = mockk<Context> { every { this@mockk.filesDir } returns filesDir }
        val noteRepository = mockk<NoteRepository>(relaxed = true)
        val pushScheduler = mockk<VaultPushScheduler>(relaxed = true)
        val repository = CaptureRepository(noteRepository, SafeFileProvider(context), pushScheduler)
        // A capture from the same minute already exists.
        File(filesDir, "vault/00_Inbox/Voice note 2026-10-17 1432.md").apply { parentFile?.mkdirs() }.writeText("x")

        val path = repository.saveVoiceNote(capturedAt, compiled)

        assertEquals("00_Inbox/Voice note 2026-10-17 1432 (2).md", path)
        coVerify { noteRepository.saveNote(path, CaptureRepository.renderVoiceNote(capturedAt, compiled)) }
        verify { pushScheduler.requestPush() }
    }

    @Test
    fun `the note keeps the transcript and lists tasks without checkboxes`() {
        val md = CaptureRepository.renderVoiceNote(capturedAt, compiled)

        // The vault's standard frontmatter (R1, in order), then the capture's own keys.
        assertTrue(
            md.startsWith(
                "---\ntitle: \"Voice note 2026-10-17 14:32\"\ndate_created: '2026-10-17'\n" +
                    "date_updated: '2026-10-17'\ntype: inbox\nstatus: 📥 inbox\ntags:\n  - inbox\n  - voice\n" +
                    "aliases: []\nsource: voice\ncaptured: '2026-10-17T14:32'\n---\n\n# Voice note 2026-10-17 14:32\n"
            )
        )
        assertTrue(md.contains("- Penser au projet"))
        assertTrue(md.contains("## Added to the day's tasks\n\n- Acheter du pain\n"))
        // A second checkbox would be a second place to tick the task (and earn XP twice).
        assertFalse(md.contains("- [ ]"))
        assertTrue(md.contains("> acheter du pain\n> et penser au projet\n"))
    }
}
