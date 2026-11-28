package cloud.wafflecommons.pixelbrainreader.data.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameChecklistsTest {

    private val page = """
        ---
        game_template: souls-like
        tags:
          - [ ] not a checklist
        ---
        # Ring of Ash

        ## Bosses
        - [ ] The first knight <!--trophy:NPWR00001_00#3-->
        - [x] The gatekeeper ✅ 2026-11-01 <!--trophy:NPWR00001_00#2-->

        ## Side quests
        * [ ] Find the merchant
        Some prose.

        ## 🏆 Trophies

        - 🥉 First Steps — 2026-11-02
    """.trimIndent() + "\n"

    @Test
    fun `checklists come per heading, frontmatter and the trophy section left out`() {
        val sections = GameChecklists.sections(page)
        assertEquals(listOf("Bosses", "Side quests"), sections.map { it.heading })
        val bosses = sections[0].items
        assertEquals(listOf("The first knight", "The gatekeeper ✅ 2026-11-01"), bosses.map { it.text })
        assertEquals(listOf(false, true), bosses.map { it.done })
        assertEquals(listOf(true, true), bosses.map { it.waitsForTrophy })
        assertEquals("Find the merchant", sections[1].items.single().text)
    }

    @Test
    fun `a tick flips exactly one line, and back`() {
        val line = "* [ ] Find the merchant"
        val item = GameChecklists.sections(page)[1].items.single()
        assertEquals(line, item.line)
        val ticked = GameChecklists.toggle(page, item.index, item.line)!!
        assertEquals(page.replace(line, "* [x] Find the merchant"), ticked)
        assertEquals(page, GameChecklists.toggle(ticked, item.index, "* [x] Find the merchant"))
        assertNull(GameChecklists.toggle(page, item.index, "- [ ] A line that isn't there"))
    }

    @Test
    fun `a spoiler line stays hidden until it is ticked, with or without PSN`() {
        val content = "---\ngame_template: souls-like\n---\n# Ring of Ash\n\n## Boss\n" +
            "- [ ] The hidden knight <!--spoiler-->\n" +
            "- [x] The gatekeeper <!-- spoiler -->\n" +
            "- [ ] My own line\n"
        val items = GameChecklists.sections(content).single().items
        assertEquals(listOf("The hidden knight", "The gatekeeper", "My own line"), items.map { it.text })
        assertEquals(listOf(true, true, false), items.map { it.spoiler })
        assertEquals(listOf(true, false, false), items.map { it.masked })
        assertEquals(listOf(false, false, false), items.map { it.waitsForTrophy })
        // The tick keeps the marker, so an unticked line hides again.
        val ticked = GameChecklists.toggle(content, items[0].index, items[0].line)!!
        assertTrue(ticked.contains("- [x] The hidden knight <!--spoiler-->\n"))
        assertEquals(content, GameChecklists.toggle(ticked, items[0].index, "- [x] The hidden knight <!--spoiler-->"))
    }

    @Test
    fun `achievement-marked lines wait and are masked, an owner's line under the achievements is its item`() {
        val content = "---\ngame_template: souls-like\n---\n# Ring of Ash\n\n## Bosses\n" +
            "- [ ] Beat the first knight <!--achievement:1915865634#12-->\n" +
            "- [x] The gatekeeper ✅ 2026-11-01 <!-- achievement:1915865634#2 -->\n" +
            "- [ ] My own line\n\n" +
            "${AchievementSection.HEADING}\n\n" +
            "*1 of 2 achievements · 10 of 60 G · 50 %*\n\n" +
            "- [ ] Not a line of mine\n"
        val sections = GameChecklists.sections(content)
        // 13.4: the section's own lines are left out; a checklist line the owner wrote in it is its item (the sync keeps it).
        val achievements = AchievementSection.HEADING.removePrefix("## ")
        assertEquals(listOf("Bosses", achievements), sections.map { it.heading })
        val items = sections.first().items
        assertEquals(listOf("Beat the first knight", "The gatekeeper ✅ 2026-11-01", "My own line"), items.map { it.text })
        assertEquals(listOf(true, true, false), items.map { it.waitsForTrophy })
        assertEquals(listOf(true, false, false), items.map { it.masked })
        assertEquals(listOf("Not a line of mine"), sections.last().items.map { it.text })
        // The same without the variation selector in the heading.
        val bare = content.replace(AchievementSection.HEADING, "## 🎖 Achievements")
        assertEquals(listOf("Bosses", "🎖 Achievements"), GameChecklists.sections(bare).map { it.heading })
    }

    // --- 13.4: a tick targets its own line -----------------------------------------------------

    private val twice = "---\ngame_template: other\n---\n# Glass Tower\n\n## Ground floor\n- [ ] Chest\n\n## Top floor\n- [ ] Chest\n"

    @Test
    fun `a tick by index flips that copy, not the first one`() {
        val items = GameChecklists.sections(twice).flatMap { it.items }
        // Indices in the whole note, the frontmatter counted.
        assertEquals(listOf(6, 9), items.map { it.index })
        val ticked = GameChecklists.toggle(twice, items[1].index, items[1].line)!!
        assertEquals("---\ngame_template: other\n---\n# Glass Tower\n\n## Ground floor\n- [ ] Chest\n\n## Top floor\n- [x] Chest\n", ticked)
        assertEquals(twice, GameChecklists.toggle(ticked, 9, "- [x] Chest"))
        // A Windows note keeps its endings.
        val crlf = twice.replace("\n", "\r\n")
        val crlfItems = GameChecklists.sections(crlf).flatMap { it.items }
        assertEquals(listOf(6, 9), crlfItems.map { it.index })
        assertEquals(ticked.replace("\n", "\r\n"), GameChecklists.toggle(crlf, crlfItems[1].index, crlfItems[1].line))
        // A note without frontmatter counts from its first line.
        assertEquals(listOf(3, 6), GameChecklists.sections(twice.substringAfter("---\n").substringAfter("---\n")).flatMap { it.items }.map { it.index })
    }

    @Test
    fun `a tick whose line moved is refused`() {
        val item = GameChecklists.sections(twice).flatMap { it.items }[1]
        // The desktop added a line above it meanwhile: the line at its index is another one.
        val moved = twice.replace("## Top floor\n", "## Top floor\n- [ ] Key\n")
        assertNull(GameChecklists.toggle(moved, item.index, item.line))
        assertNull(GameChecklists.toggle(twice, 99, item.line))
        assertNull(GameChecklists.toggle(twice, -1, item.line))
        // Only a checklist line flips.
        assertNull(GameChecklists.toggle(twice, 3, "# Glass Tower"))
    }
}

