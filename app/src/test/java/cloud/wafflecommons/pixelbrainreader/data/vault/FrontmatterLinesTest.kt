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

    // --- 13.3: a list's items as written, and a list written line by line ----------------------

    @Test
    fun `list items keep a block list's lines as written`() {
        val content = "---\ngame_maps:\n  - \"Map | https://a.example.com/x\"\n  -   'https://b.example.com/y'\n    - plain words\n---\nBody\n"
        val items = FrontmatterLines.listItems(content, "game_maps")
        assertEquals(
            listOf("  - \"Map | https://a.example.com/x\"", "  -   'https://b.example.com/y'", "    - plain words"),
            items.map { it.line }
        )
        assertEquals(listOf("Map | https://a.example.com/x", "https://b.example.com/y", "plain words"), items.map { it.value })
        // The values are the ones getList reads.
        assertEquals(FrontmatterLines.getList(content, "game_maps"), items.map { it.value })
    }

    @Test
    fun `an inline list or a scalar becomes new quoted lines`() {
        assertEquals(
            listOf(FrontmatterLines.ListItem("  - \"a\"", "a"), FrontmatterLines.ListItem("  - \"b c\"", "b c")),
            FrontmatterLines.listItems("---\nk: [a, 'b c']\n---\n", "k")
        )
        assertEquals(listOf(FrontmatterLines.ListItem("  - \"one\"", "one")), FrontmatterLines.listItems("---\nk: 'one'\n---\n", "k"))
        assertEquals(
            listOf(FrontmatterLines.ListItem("  - \"say \\\"hi\\\"\"", "say \"hi\"")),
            FrontmatterLines.listItems("---\nk: [say \"hi\"]\n---\n", "k")
        )
    }

    @Test
    fun `empty forms read as no item`() {
        listOf("k:", "k: []", "k: ''", "k: \"\"").forEach { line ->
            assertTrue(line, FrontmatterLines.listItems("---\n$line\nnext: x\n---\n", "k").isEmpty())
        }
        assertTrue(FrontmatterLines.listItems("---\nother: x\n---\n", "k").isEmpty())
        assertTrue(FrontmatterLines.listItems("---\nk:\n  - \"\"\n  -\n---\n", "k").isEmpty())
        assertTrue(FrontmatterLines.listItems("No frontmatter\n", "k").isEmpty())
    }

    @Test
    fun `setListLines writes the lines verbatim in the key's place`() {
        val content = "---\na: 1\nk:\n  - old\n  - older\nb: 2\n---\nBody\n"
        assertEquals(
            "---\na: 1\nk:\n  - 'x'\n  -   y\nb: 2\n---\nBody\n",
            FrontmatterLines.setListLines(content, "k", listOf("  - 'x'", "  -   y"))
        )
        // An inline list becomes a block list in its place.
        assertEquals("---\na: 1\nk:\n  - \"x\"\nb: 2\n---\n", FrontmatterLines.setListLines("---\na: 1\nk: [old]\nb: 2\n---\n", "k", listOf("  - \"x\"")))
    }

    @Test
    fun `an empty list is written as an empty inline list`() {
        assertEquals("---\na: 1\nk: []\nb: 2\n---\n", FrontmatterLines.setListLines("---\na: 1\nk:\n  - old\nb: 2\n---\n", "k", emptyList()))
    }

    @Test
    fun `CRLF lines survive`() {
        val crlf = "---\r\na: 1\r\nk:\r\n  - old\r\nb: 2\r\n---\r\nBody\r\n"
        assertEquals(
            "---\r\na: 1\r\nk:\r\n  - x\r\n  - y\r\nb: 2\r\n---\r\nBody\r\n",
            FrontmatterLines.setListLines(crlf, "k", listOf("  - x", "  - y"))
        )
        assertEquals(listOf("  - old"), FrontmatterLines.listItems(crlf, "k").map { it.line })
        assertEquals("---\r\na: 1\r\nk: []\r\nb: 2\r\n---\r\nBody\r\n", FrontmatterLines.setListLines(crlf, "k", emptyList()))
    }

    @Test
    fun `a missing key goes where set puts it`() {
        assertEquals("---\na: 1\nk:\n  - x\n---\nBody\n", FrontmatterLines.setListLines("---\na: 1\n---\nBody\n", "k", listOf("  - x")))
        // A standard key keeps its R1 place.
        assertEquals(
            "---\ntitle: \"T\"\naliases:\n  - \"A\"\ngame_template: lego\n---\n",
            FrontmatterLines.setListLines("---\ntitle: \"T\"\ngame_template: lego\n---\n", "aliases", listOf("  - \"A\""))
        )
        // In a Windows note whose key was missing, the new lines come out like set's line.
        assertEquals(
            "---\r\na: 1\r\nk:\n  - x\n---\r\n",
            FrontmatterLines.setListLines("---\r\na: 1\r\n---\r\n", "k", listOf("  - x"))
        )
    }

    // --- 13.4: inline lists split outside quotes ------------------------------------------------

    @Test
    fun `inline lists split only outside quotes`() {
        val content = "---\nk: [\"a, b\", c, 'it''s, fine', \"say \\\"x, y\\\"\", 'unclosed, still one]\n---\n"
        val expected = listOf("a, b", "c", "it's, fine", "say \"x, y\"", "'unclosed, still one")
        assertEquals(expected, FrontmatterLines.getList(content, "k"))
        assertEquals(expected, FrontmatterLines.listItems(content, "k").map { it.value })
        // Empty items still go, and a plain list reads as before.
        assertEquals(listOf("a", "b"), FrontmatterLines.getList("---\ntags: [a, , \"b\",]\n---\n", "tags"))
    }

    @Test
    fun `an apostrophe inside an unquoted item is no quote`() {
        assertEquals(listOf("Lantern's Bay", "Tin Orchard"), FrontmatterLines.getList("---\naliases: [Lantern's Bay, Tin Orchard]\n---\n", "aliases"))
        assertEquals(listOf("a 'b'", "c"), FrontmatterLines.getList("---\nk: [a 'b', c]\n---\n", "k"))
        assertEquals(listOf("quoted", "plain's"), FrontmatterLines.getList("---\nk: ['quoted', plain's]\n---\n", "k"))
    }
}
