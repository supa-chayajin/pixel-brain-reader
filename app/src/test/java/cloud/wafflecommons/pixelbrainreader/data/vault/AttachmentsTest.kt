package cloud.wafflecommons.pixelbrainreader.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class AttachmentsTest {

    private val note = "20_Projects/Projet/Plan.md"

    @Test
    fun `the folder follows the vault's Obsidian setting`() {
        assertEquals("99_System/Assets", Attachments.settingOf("""{"attachmentFolderPath": "99_System/Assets", "promptDelete": true}"""))
        assertNull(Attachments.settingOf("{not json"))
        assertNull(Attachments.settingOf(null))

        assertEquals("99_System/Assets", Attachments.folder("99_System/Assets", note))
        assertEquals("99_System/Assets", Attachments.folder("/99_System/Assets/", note))
        assertEquals("20_Projects/Projet", Attachments.folder("./", note))
        assertEquals("20_Projects/Projet/attachments", Attachments.folder("./attachments", note))
        assertEquals("", Attachments.folder("/", note))
        assertEquals(Attachments.FALLBACK_FOLDER, Attachments.folder(null, note))
    }

    @Test
    fun `dropped files are named like Obsidian's pasted images`() {
        val now = LocalDateTime.of(2026, 10, 18, 14, 32, 5)
        assertEquals("Pasted image 20261018143205.png", Attachments.fileName(now, "image/png"))
        assertEquals("Pasted image 20261018143205.jpg", Attachments.fileName(now, "image/jpeg", "photo.JPG"))
        assertEquals("Rapport 20261018143205.pdf", Attachments.fileName(now, "application/pdf", "Rapport.pdf"))
        assertEquals("Pasted file 20261018143205.bin", Attachments.fileName(now, "application/octet-stream"))
        assertEquals("![[Pasted image 20261018143205.png]]", Attachments.embed("99_System/Assets/Pasted image 20261018143205.png"))
    }
}
