package cloud.wafflecommons.pixelbrainreader.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontmatterLinesTest {

    private val note = """
        ---
        title: "Gateway"
        date_created: '2026-01-02'
        date_updated: '2026-01-05'
        type: projet
        status: 🏗️ wip
        tags:
          - gateway
          - security
        aliases: []
        next_step: "Write the spec"
        ---

        # Gateway

        Body with status: not frontmatter
    """.trimIndent() + "\n"

    @Test
    fun `reads scalars without their quotes`() {
        assertEquals("Gateway", FrontmatterLines.get(note, "title"))
        assertEquals("2026-01-05", FrontmatterLines.get(note, "date_updated"))
        assertEquals("🏗️ wip", FrontmatterLines.get(note, "status"))
        assertEquals("Write the spec", FrontmatterLines.get(note, "next_step"))
        assertNull(FrontmatterLines.get(note, "blockers"))
    }

    @Test
    fun `reads block and inline lists`() {
        assertEquals(listOf("gateway", "security"), FrontmatterLines.getList(note, "tags"))
        assertEquals(emptyList<String>(), FrontmatterLines.getList(note, "aliases"))
        assertEquals(listOf("a", "b"), FrontmatterLines.getList("---\ntags: [a, \"b\"]\n---\n", "tags"))
    }

    @Test
    fun `setting a key changes only its line`() {
        val moved = FrontmatterLines.set(note, "status", "🟢 done")
        assertEquals(note.replace("status: 🏗️ wip", "status: 🟢 done"), moved)
    }

    @Test
    fun `setting a list key replaces its continuation lines`() {
        val retagged = FrontmatterLines.set(note, "tags", "[gateway]")
        assertTrue(retagged.contains("tags: [gateway]\naliases: []"))
        assertFalse(retagged.contains("  - security"))
    }

    @Test
    fun `a missing standard key goes to its R1 position`() {
        val withoutUpdated = FrontmatterLines.remove(note, "date_updated")
        val restored = FrontmatterLines.set(withoutUpdated, "date_updated", "'2026-10-18'")
        assertTrue(restored.contains("date_created: '2026-01-02'\ndate_updated: '2026-10-18'\ntype: projet"))
    }

    @Test
    fun `a missing custom key goes before the closing delimiter`() {
        val blocked = FrontmatterLines.set(note, "blockers", "\"Waiting for access\"")
        assertTrue(blocked.contains("next_step: \"Write the spec\"\nblockers: \"Waiting for access\"\n---"))
    }

    @Test
    fun `a note without frontmatter gets a block with just that key`() {
        assertEquals("---\nstatus: 🟢 done\n---\n# T\n", FrontmatterLines.set("# T\n", "status", "🟢 done"))
    }

    @Test
    fun `a leading thematic break is not frontmatter`() {
        val hr = "---\nJust text\n---\nMore"
        assertFalse(FrontmatterLines.hasFrontmatter(hr))
        assertNull(FrontmatterLines.get(hr, "status"))
    }

    @Test
    fun `the legacy hollow block is skipped`() {
        val legacy = "---\n---\nstatus: active\n---\nBody"
        assertEquals("active", FrontmatterLines.get(legacy, "status"))
        assertEquals("---\n---\nstatus: 🟢 done\n---\nBody", FrontmatterLines.set(legacy, "status", "🟢 done"))
    }

    @Test
    fun `windows line endings survive an edit`() {
        val crlf = "---\r\ntitle: \"A\"\r\nstatus: x\r\n---\r\nBody\r\n"
        assertEquals("---\r\ntitle: \"A\"\r\nstatus: y\r\n---\r\nBody\r\n", FrontmatterLines.set(crlf, "status", "y"))
    }

    @Test
    fun `keys are listed in order`() {
        assertEquals(
            listOf("title", "date_created", "date_updated", "type", "status", "tags", "aliases", "next_step"),
            FrontmatterLines.keys(note)
        )
    }
}
