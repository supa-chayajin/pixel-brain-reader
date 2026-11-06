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
    fun `a sub-project's cockpit and a Next_Actions live list are news too`() {
        val sub = "20_Projects/28_Work/app_one"
        val subTodo = "$sub/TODO.md"
        val nextActions = "20_Projects/22_Demo/Next_Actions.md"
        val changes = listOf(
            ChangedPath(subTodo, "modify"),
            ChangedPath("$sub/Claude/Reports/2026-11-06_Cockpit.md", "add"),
            ChangedPath(nextActions, "modify"),
            ChangedPath("20_Projects/28_Work/Notes/TODO.md", "modify"), // not a sub-project: no Handoff.md
            ChangedPath("20_Projects/22_Demo/Repo/Claude/Reports/2026-11-06_Copied.md", "add") // inside a mirror
        )
        val before = mapOf(subTodo to "- [ ] @moi Old line\n", nextActions to "", "20_Projects/28_Work/Notes/TODO.md" to "")
        val after = mapOf(
            subTodo to "- [ ] @moi Old line\n- [ ] @moi Sync the clone\n",
            nextActions to "- [ ] @moi Answer the e-mail\n",
            "20_Projects/28_Work/Notes/TODO.md" to "- [ ] @moi a note's own list\n"
        )
        assertTrue(WhatsNew.needsContent(subTodo, setOf(sub)))
        assertTrue(!WhatsNew.needsContent(subTodo))
        assertTrue(WhatsNew.needsContent(nextActions))
        assertEquals(sub, WhatsNew.subProjectCandidate("$sub/Handoff.md"))
        assertEquals(sub, WhatsNew.subProjectCandidate("$sub/Claude/Reports/2026-11-06_Cockpit.md"))
        assertTrue(WhatsNew.isReport("$sub/Claude/Reports/2026-11-06_Cockpit.md", setOf(sub)))
        assertTrue(!WhatsNew.isReport("$sub/Claude/Reports/2026-11-06_Cockpit.md"))

        val news = WhatsNew.of(changes, before, after, subRoots = setOf(sub)).associateBy { it.root }
        assertEquals(setOf("20_Projects/22_Demo", sub), news.keys)
        assertEquals(listOf("Sync the clone"), news.getValue(sub).myLines)
        assertEquals(listOf("$sub/Claude/Reports/2026-11-06_Cockpit.md"), news.getValue(sub).reports)
        assertEquals(listOf("Answer the e-mail"), news.getValue("20_Projects/22_Demo").myLines)
        assertTrue(news.getValue("20_Projects/22_Demo").reports.isEmpty())
    }

    @Test
    fun `only the projects' TODO and hand-off files need their contents`() {
        assertTrue(WhatsNew.needsContent(todo))
        assertTrue(WhatsNew.needsContent(handoff))
        assertTrue(!WhatsNew.needsContent("$root/Notes/TODO.md"))
        assertTrue(!WhatsNew.needsContent("10_Journal/2026-11-06.md"))
        assertTrue(WhatsNew.isReport("$root/Claude/Reports/x.md"))
        assertTrue(!WhatsNew.isReport("30_Areas/Claude/Reports/x.md"))
        assertTrue(!WhatsNew.isReport("$root/Repo/Claude/Reports/x.md"))
    }
}
