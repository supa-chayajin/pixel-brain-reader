package cloud.wafflecommons.pixelbrainreader.data.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
