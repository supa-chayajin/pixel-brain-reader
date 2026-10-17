package cloud.wafflecommons.pixelbrainreader.data.utils

import cloud.wafflecommons.pixelbrainreader.ui.utils.ObsidianImagePlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultFileIndexTest {

    private val index = VaultFileIndex(
        listOf(
            "assets/2024/Pasted image 20241010.png",
            "deep/nested/diagram.png",
            "diagram.png",
            "20_Projects/specs.pdf"
        )
    )

    @Test
    fun `an embed by bare name finds the attachment anywhere in the vault`() {
        assertEquals("assets/2024/Pasted image 20241010.png", index.resolve("Pasted image 20241010.png"))
    }

    @Test
    fun `an embed by path, size suffix or anchor resolves too, case-insensitively`() {
        assertEquals("20_Projects/specs.pdf", index.resolve("20_projects/SPECS.pdf"))
        assertEquals("assets/2024/Pasted image 20241010.png", index.resolve("Pasted image 20241010.png|300"))
        assertEquals("20_Projects/specs.pdf", index.resolve("./20_Projects/specs.pdf#page=2"))
    }

    @Test
    fun `when names collide the shallowest attachment wins`() {
        assertEquals("diagram.png", index.resolve("diagram.png"))
        assertEquals("deep/nested/diagram.png", index.resolve("deep/nested/diagram.png"))
    }

    @Test
    fun `unknown or blank targets resolve to null`() {
        assertNull(index.resolve("missing.png"))
        assertNull(index.resolve("  "))
    }

    @Test
    fun `notes, encrypted, private and hidden files are not attachments`() {
        assertTrue(VaultFileIndex.isAttachment("assets/a.png"))
        assertFalse(VaultFileIndex.isAttachment("10_Journal/2026-10-17.md"))
        assertFalse(VaultFileIndex.isAttachment("99_Private/diary.md.enc"))
        assertFalse(VaultFileIndex.isAttachment("99_Private/photo.png"))
        assertFalse(VaultFileIndex.isAttachment(".obsidian/workspace.json"))
    }

    @Test
    fun `the image plugin rewrites embeds through the resolver without touching the disk`() {
        val plugin = ObsidianImagePlugin { target -> index.resolve(target)?.let { "file:///vault/$it" } }

        val out = plugin.processMarkdown("Before ![[diagram.png|200]] and ![[missing.png]] after")

        assertEquals(
            "Before ![diagram.png](file:///vault/diagram.png) and <obsidian-image path=\"missing.png\" /> after",
            out
        )
    }
}
