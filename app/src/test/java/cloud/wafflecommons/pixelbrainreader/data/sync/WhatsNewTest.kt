package cloud.wafflecommons.pixelbrainreader.data.sync

import cloud.wafflecommons.pixelbrainreader.data.remote.ChangedPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sync digest's "for you" (C4): only what the pull brought that concerns you. */
class WhatsNewTest {

    private val root = "20_Projects/21_Demo"
    private val todo = "$root/TODO.md"
    private val handoff = "$root/Handoff.md"

    private val todoBefore = """
        # TODO

        ## 👤 Yours

        - [ ] @moi Review the release PR
        - [ ] @moi Decide on the ignore rule

        ## 🧇 Next (Claude, on your go)

        - [ ] Cut the branches
    """.trimIndent()

    private val todoAfter = """
        # TODO

        ## 👤 Yours

        - [ ] @moi Review the release PR
        - [ ] @moi Decide on the ignore rule, before Friday
        - [ ] @moi Open the upstream issue
        - [x] @moi Something already done

        ## 🧇 Next (Claude, on your go)

        - [ ] Cut the branches
        - [ ] A new line for Claude
    """.trimIndent()

    @Test
    fun `new and reworded lines of yours, new reports and picked-up hand-offs, per project`() {
        val changes = listOf(
            ChangedPath(todo, "modify"),
            ChangedPath(handoff, "modify"),
            ChangedPath("$root/Claude/Reports/2026-11-06_Audit.md", "add"),
            ChangedPath("$root/Claude/Reports/2026-11-01_Old.md", "modify"), // an edit, not a new report
            ChangedPath("$root/Notes/Plan.md", "add"),
            ChangedPath("10_Journal/2026-11-06.md", "modify")
        )
        val before = mapOf(
            todo to todoBefore,
            handoff to "- [ ] 2026-11-05 18:00 — Look at the flaky test\n- [ ] 2026-11-06 08:00 — Still open\n"
        )
        val after = mapOf(
            todo to todoAfter,
            handoff to "- [x] 2026-11-05 18:00 — Look at the flaky test\n- [ ] 2026-11-06 08:00 — Still open\n- [x] 2026-11-06 09:00 — Written and ticked on the desktop\n"
        )
        val news = WhatsNew.of(changes, before, after).single()
        assertEquals(root, news.root)
        assertEquals(listOf("Decide on the ignore rule, before Friday", "Open the upstream issue"), news.myLines)
        assertEquals(listOf("$root/Claude/Reports/2026-11-06_Audit.md"), news.reports)
        assertEquals(listOf("Look at the flaky test"), news.pickedUp)
        assertEquals("21_Demo", news.name)
    }

    @Test
    fun `a new live TODO is all news, a mirrored TODO never is`() {
        val created = WhatsNew.of(listOf(ChangedPath(todo, "add")), mapOf(todo to null), mapOf(todo to todoAfter)).single()
        assertEquals(3, created.myLines.size)

        val mirrored = "---\ntitle: \"TODO\"\n---\n\n> Mirrored automatically from `demo/TODO.md` — edit the source.\n\n- [ ] @moi a repository line\n"
        assertTrue(WhatsNew.of(listOf(ChangedPath(todo, "modify")), mapOf(todo to ""), mapOf(todo to mirrored)).isEmpty())
    }

    @Test
    fun `a Next_Actions live list is news too`() {
        val nextActions = "20_Projects/22_Demo/Next_Actions.md"
        assertTrue(WhatsNew.needsContent(nextActions))
        assertTrue(!WhatsNew.needsContent("20_Projects/22_Demo/Notes/Next_Actions.md"))
        val news = WhatsNew.of(listOf(ChangedPath(nextActions, "modify")), mapOf(nextActions to ""), mapOf(nextActions to "- [ ] @moi Answer the e-mail\n")).single()
        assertEquals("20_Projects/22_Demo", news.root)
        assertEquals(listOf("Answer the e-mail"), news.myLines)
    }

    @Test
    fun `only the projects' TODO and hand-off files need their contents`() {
        assertTrue(WhatsNew.needsContent(todo))
        assertTrue(WhatsNew.needsContent(handoff))
        assertTrue(!WhatsNew.needsContent("$root/Notes/TODO.md"))
        assertTrue(!WhatsNew.needsContent("10_Journal/2026-11-06.md"))
        assertTrue(WhatsNew.isReport("$root/Claude/Reports/x.md"))
        assertTrue(!WhatsNew.isReport("30_Areas/Claude/Reports/x.md"))
    }
}
