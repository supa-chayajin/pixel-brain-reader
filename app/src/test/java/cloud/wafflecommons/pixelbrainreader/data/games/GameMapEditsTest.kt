package cloud.wafflecommons.pixelbrainreader.data.games

import cloud.wafflecommons.pixelbrainreader.data.vault.FrontmatterLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 13.3 (ME1–ME4): a page's `game_maps` edited entry by entry, the rest of the page as written. */
class GameMapEditsTest {

    private val today = LocalDate.of(2026, 11, 27)
    private val interactive = "https://ringofash.wiki.example.com/Interactive+Map"
    private val maps = "https://ringofash.wiki.example.com/Maps"

    private val page = """
        ---
        title: "Ring of Ash"
        date_created: '2026-11-14'
        date_updated: '2026-11-20'
        type: area
        status: active
        tags:
          - games
        aliases: []
        game_template: souls-like
        game_state: playing
        game_maps:
          - "Interactive map | https://ringofash.wiki.example.com/Interactive+Map"
          - 'https://ringofash.wiki.example.com/Maps'
        started: '2026-11-01'
        ---
        # Ring of Ash

        My notes on this run.

        ## Bosses
        - [ ] The first knight <!--trophy:NPWR00001_00#3-->
        - [x] The gatekeeper

        ## 🏆 Trophies

        *1 of 2 trophies · 50 %*

        - 🥉 First Steps — 2026-11-02
    """.trimIndent() + "\n"

    private val dated: String get() = page.replace("date_updated: '2026-11-20'", "date_updated: '2026-11-27'")

    private fun saved(edit: MapEdit): String = (edit as MapEdit.Saved).content

    @Test
    fun `an address is added at the end as the vault writes it`() {
        val added = saved(GameMapEdits.add(page, "https://two.example.org/Maps", "Maps (images)", today))
        assertEquals(
            dated.replace(
                "  - 'https://ringofash.wiki.example.com/Maps'\n",
                "  - 'https://ringofash.wiki.example.com/Maps'\n  - \"Maps (images) | https://two.example.org/Maps\"\n"
            ),
            added
        )
        assertEquals("  - \"Maps (images) | https://two.example.org/Maps\"", GameMapEdits.line("https://two.example.org/Maps", "Maps (images)"))
        assertEquals(
            listOf(interactive, maps, "https://two.example.org/Maps"),
            GamePages.parse("30_Areas/Games/Ring/Ring.md", added)!!.maps.map { it.url }
        )
    }

    @Test
    fun `game_maps empty, an empty list or an empty string becomes a block list`() {
        listOf("game_maps:", "game_maps: []", "game_maps: ''").forEach { empty ->
            val content = "---\ntitle: \"Brick Wars\"\ngame_template: lego\n$empty\nstarted: '2026-11-01'\n---\n# Brick Wars\n"
            assertEquals(
                "---\ntitle: \"Brick Wars\"\ngame_template: lego\ngame_maps:\n  - \"https://bricks.example.com/Map\"\nstarted: '2026-11-01'\n---\n# Brick Wars\n",
                saved(GameMapEdits.add(content, "https://bricks.example.com/Map", null, today))
            )
        }
        // An inline list becomes a block list on the first change, its entries quoted the vault's way.
        val inline = "---\ngame_template: lego\ngame_maps: [https://a.example.com/x, 'Old | https://b.example.com/y']\n---\n"
        assertEquals(
            "---\ngame_template: lego\ngame_maps:\n  - \"https://a.example.com/x\"\n  - \"Old | https://b.example.com/y\"\n  - \"https://c.example.com/z\"\n---\n",
            saved(GameMapEdits.add(inline, "https://c.example.com/z", null, today))
        )
        // No change, no rewrite: the inline list stays as typed.
        assertEquals(inline, saved(GameMapEdits.change(inline, "https://a.example.com/x", "https://a.example.com/x", null, today)))
    }

    @Test
    fun `a missing game_maps is added before the closing delimiter`() {
        val content = "---\ntitle: \"Brick Wars\"\ndate_updated: '2026-11-01'\ngame_template: lego\n---\n# Brick Wars\n"
        assertEquals(
            "---\ntitle: \"Brick Wars\"\ndate_updated: '2026-11-27'\ngame_template: lego\ngame_maps:\n  - \"Map | https://bricks.example.com/Map\"\n---\n# Brick Wars\n",
            saved(GameMapEdits.add(content, "https://bricks.example.com/Map", "Map", today))
        )
    }

    @Test
    fun `change replaces one entry in its place, label or address, and drops its duplicates`() {
        val withDuplicate = page.replace(
            "  - 'https://ringofash.wiki.example.com/Maps'\n",
            "  - 'https://ringofash.wiki.example.com/Maps'\n  - \"Again | https://ringofash.wiki.example.com/Interactive+Map\"\n"
        )
        assertEquals(
            dated.replace(
                "  - \"Interactive map | https://ringofash.wiki.example.com/Interactive+Map\"\n",
                "  - \"World map | https://ringofash.other.example.net/World\"\n"
            ),
            saved(GameMapEdits.change(withDuplicate, interactive, "https://ringofash.other.example.net/World", "World map", today))
        )
        // A label alone: the address stays, the entry is written the vault's way.
        assertEquals(
            dated.replace("  - 'https://ringofash.wiki.example.com/Maps'\n", "  - \"Maps (images) | https://ringofash.wiki.example.com/Maps\"\n"),
            saved(GameMapEdits.change(page, maps, maps, "Maps (images)", today))
        )
        // The label taken away.
        assertEquals(
            dated.replace(
                "  - \"Interactive map | https://ringofash.wiki.example.com/Interactive+Map\"\n",
                "  - \"https://ringofash.wiki.example.com/Interactive+Map\"\n"
            ),
            saved(GameMapEdits.change(page, interactive, interactive, null, today))
        )
    }

    @Test
    fun `the same text keeps the line as typed`() {
        // Single quotes: the same entry is no change, and nothing moves, the date included.
        assertEquals(page, saved(GameMapEdits.change(page, maps, " $maps ", null, today)))
        val unquoted = page.replace(
            "  - \"Interactive map | https://ringofash.wiki.example.com/Interactive+Map\"",
            "  -   Interactive map | https://ringofash.wiki.example.com/Interactive+Map"
        )
        assertEquals(unquoted, saved(GameMapEdits.change(unquoted, interactive, interactive, " Interactive  map ", today)))
        // Another entry's change leaves that line as typed.
        val other = saved(GameMapEdits.change(unquoted, maps, maps, "Maps (images)", today))
        assertTrue(other.contains("game_maps:\n  -   Interactive map | https://ringofash.wiki.example.com/Interactive+Map\n  - \"Maps (images) |"))
    }

    @Test
    fun `remove drops every entry of that address`() {
        val withDuplicate = page.replace(
            "  - 'https://ringofash.wiki.example.com/Maps'\n",
            "  - 'https://ringofash.wiki.example.com/Maps'\n  - \"Again | https://ringofash.wiki.example.com/Interactive+Map\"\n"
        )
        val removed = saved(GameMapEdits.remove(withDuplicate, interactive, today))
        assertEquals(dated.replace("  - \"Interactive map | https://ringofash.wiki.example.com/Interactive+Map\"\n", ""), removed)
        // The last one gone, the key stays, as an empty list.
        assertTrue(saved(GameMapEdits.remove(removed, maps, today)).contains("\ngame_state: playing\ngame_maps: []\nstarted: '2026-11-01'\n"))
    }

    @Test
    fun `make main goes before the first readable entry, unreadable ones stay where they are`() {
        assertEquals(
            dated.replace(
                "  - \"Interactive map | https://ringofash.wiki.example.com/Interactive+Map\"\n  - 'https://ringofash.wiki.example.com/Maps'\n",
                "  - 'https://ringofash.wiki.example.com/Maps'\n  - \"Interactive map | https://ringofash.wiki.example.com/Interactive+Map\"\n"
            ),
            saved(GameMapEdits.makeMain(page, maps, today))
        )
        val content = "---\ngame_template: souls-like\ngame_maps:\n" +
            "  - \"Notes, not a map\"\n" +
            "  - \"Interactive map | $interactive\"\n" +
            "  - \"http://old.example.com/map\"\n" +
            "  - \"Maps | $maps\"\n" +
            "---\n"
        assertEquals(
            "---\ngame_template: souls-like\ngame_maps:\n" +
                "  - \"Notes, not a map\"\n" +
                "  - \"Maps | $maps\"\n" +
                "  - \"Interactive map | $interactive\"\n" +
                "  - \"http://old.example.com/map\"\n" +
                "---\n",
            saved(GameMapEdits.makeMain(content, maps, today))
        )
        // Already the main map: nothing changes.
        assertEquals(content, saved(GameMapEdits.makeMain(content, interactive, today)))
        // The main map's duplicates go, and it keeps its place.
        val doubled = content.removeSuffix("---\n") + "  - \"Again | $interactive\"\n---\n"
        assertEquals(content, saved(GameMapEdits.makeMain(doubled, interactive, today)))
    }

    @Test
    fun `unreadable entries (http, a private address, words) are kept as typed through every edit`() {
        val unreadable = listOf("  - http://old.example.com/map", "  - \"https://192.168.1.10/map\"", "  -  'Some words'")
        val content = "---\ntitle: \"Ring of Ash\"\ndate_updated: '2026-11-20'\ngame_template: souls-like\ngame_maps:\n" +
            "${unreadable[0]}\n  - \"Interactive map | $interactive\"\n${unreadable[1]}\n${unreadable[2]}\n---\n# Ring of Ash\n"
        val edits = listOf(
            GameMapEdits.add(content, "https://two.example.org/Maps", null, today),
            GameMapEdits.change(content, interactive, "https://two.example.org/Maps", "Maps", today),
            GameMapEdits.makeMain(saved(GameMapEdits.add(content, maps, null, today)), maps, today),
            GameMapEdits.remove(content, interactive, today)
        ).map(::saved)
        edits.forEach { edited ->
            assertEquals(unreadable, FrontmatterLines.listItems(edited, GameMaps.KEY).map { it.line }.filter { it in unreadable })
        }
        // Their places too: an address added goes after them all, the main map just before the first one the app reads.
        assertEquals(
            listOf(unreadable[0], "  - \"Interactive map | $interactive\"", unreadable[1], unreadable[2], "  - \"https://two.example.org/Maps\""),
            FrontmatterLines.listItems(edits[0], GameMaps.KEY).map { it.line }
        )
        assertEquals(
            listOf(unreadable[0], "  - \"Maps | https://two.example.org/Maps\"", unreadable[1], unreadable[2]),
            FrontmatterLines.listItems(edits[1], GameMaps.KEY).map { it.line }
        )
        assertEquals(
            listOf(unreadable[0], "  - \"$maps\"", "  - \"Interactive map | $interactive\"", unreadable[1], unreadable[2]),
            FrontmatterLines.listItems(edits[2], GameMaps.KEY).map { it.line }
        )
        assertEquals(unreadable, FrontmatterLines.listItems(edits[3], GameMaps.KEY).map { it.line })
    }

    @Test
    fun `refusals change nothing - not public https, already there, gone`() {
        assertEquals(MapEdit.Refused(MapEditResult.NOT_PUBLIC_HTTPS), GameMapEdits.add(page, "http://two.example.org/Maps", null, today))
        assertEquals(MapEdit.Refused(MapEditResult.NOT_PUBLIC_HTTPS), GameMapEdits.add(page, "https://192.168.1.10/map", null, today))
        assertEquals(MapEdit.Refused(MapEditResult.NOT_PUBLIC_HTTPS), GameMapEdits.add(page, "some words", null, today))
        assertEquals(MapEdit.Refused(MapEditResult.ALREADY_THERE), GameMapEdits.add(page, " $maps ", "Another label", today))
        assertEquals(MapEdit.Refused(MapEditResult.NOT_PUBLIC_HTTPS), GameMapEdits.change(page, maps, "http://two.example.org/Maps", null, today))
        assertEquals(MapEdit.Refused(MapEditResult.ALREADY_THERE), GameMapEdits.change(page, maps, interactive, null, today))
        assertEquals(
            MapEdit.Refused(MapEditResult.GONE),
            GameMapEdits.change(page, "https://gone.example.com/x", "https://two.example.org/Maps", null, today)
        )
        assertEquals(MapEdit.Refused(MapEditResult.GONE), GameMapEdits.remove(page, "https://gone.example.com/x", today))
        assertEquals(MapEdit.Refused(MapEditResult.GONE), GameMapEdits.makeMain(page, "https://gone.example.com/x", today))
        // An entry the app can't read is never named: the app can't remove it or make it main.
        val unreadable = "---\ngame_maps:\n  - http://old.example.com/map\n---\n"
        assertEquals(MapEdit.Refused(MapEditResult.GONE), GameMapEdits.remove(unreadable, "http://old.example.com/map", today))
        assertEquals(MapEdit.Refused(MapEditResult.GONE), GameMapEdits.makeMain(unreadable, "http://old.example.com/map", today))
    }

    @Test
    fun `outside game_maps the page is byte-identical`() {
        fun outside(text: String): String =
            text.split("\n").filterNot { it.startsWith("  - ") || it.startsWith("game_maps:") || it.startsWith("date_updated:") }.joinToString("\n")
        val edited = listOf(
            GameMapEdits.add(page, "https://two.example.org/Maps", "Maps (images)", today),
            GameMapEdits.change(page, interactive, "https://two.example.org/Maps", null, today),
            GameMapEdits.remove(page, maps, today),
            GameMapEdits.makeMain(page, maps, today)
        ).map(::saved)
        edited.forEach { assertEquals(outside(page), outside(it)) }
        // A Windows page keeps its line endings, the new lines included.
        val crlf = page.replace("\n", "\r\n")
        assertEquals(edited[0].replace("\n", "\r\n"), saved(GameMapEdits.add(crlf, "https://two.example.org/Maps", "Maps (images)", today)))
        assertEquals(edited[2].replace("\n", "\r\n"), saved(GameMapEdits.remove(crlf, maps, today)))
        assertEquals(edited[3].replace("\n", "\r\n"), saved(GameMapEdits.makeMain(crlf, maps, today)))
    }

    @Test
    fun `labels are one trimmed line of at most 80 characters, quotes escaped`() {
        assertEquals(
            "  - \"He said \\\"go\\\" now | https://two.example.org/Maps\"",
            GameMapEdits.line("https://two.example.org/Maps", "  He said \"go\"\n  now  ")
        )
        assertEquals(
            "  - \"" + "L".repeat(80) + " | https://two.example.org/Maps\"",
            GameMapEdits.line("https://two.example.org/Maps", "L".repeat(100))
        )
        assertEquals("  - \"https://two.example.org/Maps\"", GameMapEdits.line("https://two.example.org/Maps", " \t "))
        // Read back, the label is the one typed.
        val added = saved(GameMapEdits.add(page, "https://two.example.org/Maps", "He said \"go\"", today))
        assertEquals("He said \"go\"", GamePages.parse("30_Areas/Games/Ring/Ring.md", added)!!.maps.last().label)
    }

    @Test
    fun `unreadable counts the entries the app can't open`() {
        assertEquals(0, GameMapEdits.unreadable(page))
        val content = "---\ngame_maps:\n  - http://old.example.com/map\n  - \"https://192.168.1.10/map\"\n  - Some words\n  - \"$maps\"\n  - \"\"\n---\n"
        assertEquals(3, GameMapEdits.unreadable(content))
        assertEquals(0, GameMapEdits.unreadable("---\ngame_maps: []\n---\n"))
        assertEquals(0, GameMapEdits.unreadable("# No frontmatter\n"))
    }

    // --- 13.4 ------------------------------------------------------------------------------

    @Test
    fun `an inline list with a quoted comma keeps its entry through an edit`() {
        val content = "---\ngame_template: other\ngame_maps: [\"Bosses, NPCs | https://glass.example.com/map\", https://tin.example.com/x]\n---\n# Glass Tower\n"
        val maps = GamePages.parse("30_Areas/Games/Glass_Tower/Glass_Tower.md", content)!!.maps
        assertEquals(listOf("https://glass.example.com/map", "https://tin.example.com/x"), maps.map { it.url })
        assertEquals("Bosses, NPCs", maps.first().label)
        assertEquals(0, GameMapEdits.unreadable(content))
        assertEquals(
            "---\ngame_template: other\ngame_maps:\n  - \"Bosses, NPCs | https://glass.example.com/map\"\n  - \"https://tin.example.com/x\"\n" +
                "  - \"https://bay.example.org/map\"\n---\n# Glass Tower\n",
            saved(GameMapEdits.add(content, "https://bay.example.org/map", null, today))
        )
    }

    @Test
    fun `an edit whose list doesn't read back is refused`() {
        val items = FrontmatterLines.listItems(page, GameMaps.KEY)
        // A blank line ends a block list: the entry after it would be lost at the next read.
        val broken = items.map { it.line } + "" + "  - \"https://two.example.org/Maps\""
        assertEquals(MapEdit.Refused(MapEditResult.FAILED), GameMapEdits.write(page, items, broken, today))
        // A list that reads back as written is saved, as the edits write it.
        assertEquals(
            saved(GameMapEdits.add(page, "https://two.example.org/Maps", null, today)),
            saved(GameMapEdits.write(page, items, items.map { it.line } + "  - \"https://two.example.org/Maps\"", today))
        )
    }
}
