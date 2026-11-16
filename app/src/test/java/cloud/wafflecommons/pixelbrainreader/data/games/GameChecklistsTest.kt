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
        val ticked = GameChecklists.toggle(page, line)!!
        assertEquals(page.replace(line, "* [x] Find the merchant"), ticked)
        assertEquals(page, GameChecklists.toggle(ticked, "* [x] Find the merchant"))
        assertNull(GameChecklists.toggle(page, "- [ ] A line that isn't there"))
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
        val ticked = GameChecklists.toggle(content, "- [ ] The hidden knight <!--spoiler-->")!!
        assertTrue(ticked.contains("- [x] The hidden knight <!--spoiler-->\n"))
        assertEquals(content, GameChecklists.toggle(ticked, "- [x] The hidden knight <!--spoiler-->"))
    }
}

