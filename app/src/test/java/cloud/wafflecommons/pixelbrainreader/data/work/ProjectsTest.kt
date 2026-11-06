package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.vault.LinkExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProjectsTest {

    private val today = LocalDate.of(2026, 11, 4)

    private val singleWorklog = """
        ---
        title: "🛰️ Demo Worklog 2026-11-04"
        type: log
        ---

        # 🛰️ Worklog — 2026-11-04

        ## 🧬 Commits

        ### demo — 2 commit(s)
        - feat(api): add the thing
        - fix(ci): run the tests

        ## 🤖 AI sessions

        - 2 session(s) · 759 prompt(s) · 718 tool call(s)
        - Tools: Bash×414
        - 202,877,309 tokens total (1,120 in · 949,430 out)
    """.trimIndent()

    private val perAppFile = """
        # 🛰️ app — 2026-11-03

        ## 🧬 Commits (1)

        - docs(readme): explain
    """.trimIndent()

    private val rollUp = """
        # 🛰️ Demo Worklog — 2026-11-03

        ## 🗂️ Apps touched

        - **app** — 1 commit(s) → `app/Worklog/2026-11-03.md`

        ## 🤖 AI sessions (workspace-wide)

        - 1 session(s) · 10 prompt(s) · 5 tool call(s)
        - 1,000 tokens total (1 in · 2 out)
    """.trimIndent()

    @Test
    fun `project roots and worklog paths`() {
        assertEquals("20_Projects/21_Demo", ProjectFormat.rootOf("20_Projects/21_Demo/Notes/a.md"))
        assertNull(ProjectFormat.rootOf("20_Projects/loose.md"))
        assertNull(ProjectFormat.rootOf("30_Areas/x/a.md"))
        assertTrue(ProjectFormat.isWorklog("20_Projects/21_Demo/app/Worklog/2026-11-03.md"))
        assertTrue(!ProjectFormat.isWorklog("20_Projects/21_Demo/Worklog/README.md"))
        assertEquals(LocalDate.of(2026, 11, 3), ProjectFormat.worklogDate("20_Projects/21_Demo/Worklog/2026-11-03.md"))
    }

    @Test
    fun `a single worklog gives commits, sessions and tokens`() {
        val day = ProjectFormat.parseWorklog(today, singleWorklog)
        assertEquals(2, day.commits)
        assertEquals(listOf("feat(api): add the thing", "fix(ci): run the tests"), day.subjects)
        assertEquals(2, day.sessions)
        assertEquals(759, day.prompts)
        assertEquals(202_877_309L, day.tokens)
    }

    private val currentWorklog = """
        # 🛰️ Demo Worklog — 2026-11-06

        ## 🧬 Commits

        - feat: one
        - feat: two
        - fix: three

        ## 🤖 AI sessions

        - 3 session(s) · 300 prompt(s) · 259 tool call(s)
        - Tools: Bash×139, Edit×79, Write×13, Read×13
        - 87,235,819 tokens total (464 in · 290,432 out · 1,311,497 cache-write · 85,633,426 cache-read)
    """.trimIndent()

    @Test
    fun `a current worklog gives tool calls, the tools and the four token buckets, an older one says not recorded`() {
        val day = ProjectFormat.parseWorklog(LocalDate.of(2026, 11, 6), currentWorklog)
        assertEquals(259, day.toolCalls)
        assertEquals(mapOf("Bash" to 139, "Edit" to 79, "Write" to 13, "Read" to 13), day.tools)
        assertEquals(464L, day.tokensIn)
        assertEquals(290_432L, day.tokensOut)
        assertEquals(1_311_497L, day.cacheWrite)
        assertEquals(85_633_426L, day.cacheRead)

        // The older single worklog has tool calls and the tools, but only two token buckets.
        val older = ProjectFormat.parseWorklog(today, singleWorklog)
        assertEquals(718, older.toolCalls)
        assertEquals(mapOf("Bash" to 414), older.tools)
        assertEquals(1_120L, older.tokensIn)
        assertNull(older.cacheRead)

        // No tools line, no tool calls: not recorded (null), never 0.
        val oldest = ProjectFormat.parseWorklog(today, "## 🤖 AI sessions\n\n- 1 session(s) · 10 prompt(s)\n- 1,000 tokens total\n")
        assertNull(oldest.toolCalls)
        assertNull(oldest.tools)
        assertNull(oldest.tokensIn)

        // "Tools: —" is recorded: no tool used.
        val idle = ProjectFormat.parseWorklog(today, "- 0 session(s) · 0 prompt(s) · 0 tool call(s)\n- Tools: —\n- 0 tokens total (0 in · 0 out · 0 cache-write · 0 cache-read)\n")
        assertEquals(emptyMap<String, Int>(), idle.tools)
        assertEquals(0, idle.toolCalls)
    }

    @Test
    fun `the pairing figures are measured over the window, and say so when a worklog didn't record them`() {
        val days = ProjectFormat.mergeDays(
            listOf(
                ProjectFormat.parseWorklog(LocalDate.of(2026, 11, 6), currentWorklog),
                ProjectFormat.parseWorklog(LocalDate.of(2026, 11, 4), singleWorklog),
                ProjectFormat.parseWorklog(LocalDate.of(2026, 11, 3), perAppFile)
            )
        )
        val stats = ProjectFormat.pairing(days)
        assertEquals(3, stats.days)
        assertEquals(2, stats.sessionDays)
        assertEquals(6, stats.commits)
        assertEquals(5, stats.sessions)
        assertEquals(1_059.0 / 5, stats.promptsPerSession!!, 1e-9)
        assertEquals(6.0 / 5, stats.commitsPerSession!!, 1e-9)
        assertEquals(259 + 718, stats.toolCalls)
        assertEquals(listOf("Bash" to 553, "Edit" to 79, "Read" to 13, "Write" to 13), stats.topTools!!.take(4))
        // Only the day with the four buckets counts for the cache share.
        assertEquals(85_633_426.0 / 87_235_819.0, stats.cacheReadShare!!, 1e-9)

        val unrecorded = ProjectFormat.pairing(listOf(ProjectFormat.parseWorklog(today, "- 1 session(s) · 10 prompt(s)\n")))
        assertNull(unrecorded.toolCalls)
        assertNull(unrecorded.topTools)
        assertNull(unrecorded.cacheReadShare)
        assertNull(ProjectFormat.pairing(emptyList()).promptsPerSession)
    }

    @Test
    fun `per-app files and the roll-up are summed without counting twice`() {
        val d = LocalDate.of(2026, 11, 3)
        val merged = ProjectFormat.mergeDays(
            listOf(ProjectFormat.parseWorklog(d, perAppFile), ProjectFormat.parseWorklog(d, rollUp))
        ).single()
        assertEquals(1, merged.commits)
        assertEquals(1, merged.sessions)
        assertEquals(1_000L, merged.tokens)
    }

    @Test
    fun `summary of a project`() {
        val root = "20_Projects/21_Demo"
        val notes = mapOf(
            "$root/README.md" to "---\ntitle: \"📌 21_Demo — Project Log (read me)\"\nstatus: 🏗️ wip\n---\n",
            "$root/TODO.md" to "---\ntitle: Next\ndate_updated: '2026-11-02'\n---\n- [ ] a line for Claude\n",
            "$root/Notes/Roadmap.md" to "---\ntitle: Roadmap\ndate_updated: '2026-10-01'\n---\n",
            "$root/Notes/Plan.md" to "---\ntitle: Plan\ndate_updated: '2026-11-01'\n---\n- [ ] write the doc\n- [x] done already\n- [ ] @moi ship it 📅 2026-11-10\n",
            "$root/Worklog/2026-11-04.md" to singleWorklog,
            "$root/Worklog/2026-10-01.md" to singleWorklog // outside the 14-day window
        )
        val links = listOf(
            "10_Journal/2026-11-03.md" to LinkExtractor.keyOfPath("$root/Notes/Plan.md"),
            "$root/Notes/Roadmap.md" to LinkExtractor.keyOfPath("$root/Notes/Plan.md"), // inside: ignored
            "10_Journal/2026-11-02.md" to "somewhere-else"
        )
        val s = ProjectFormat.summarize(root, notes, links, today)
        assertEquals("21_Demo", s.title)
        assertEquals("🏗️ wip", s.status)
        assertEquals(1, s.days.size)
        assertEquals(2, s.commits)
        assertEquals(today, s.lastActivity)
        assertEquals(listOf("ship it 📅 2026-11-10", "write the doc").map { it.substringBefore(" 📅") }, s.actions.map { it.text.substringBefore(" 📅") })
        assertTrue(s.actions.first().mine)
        assertEquals(listOf("a line for Claude"), s.claudeQueue.map { it.text })
        assertEquals(listOf("$root/Notes/Roadmap.md", "$root/TODO.md"), s.keyNotes.map { it.path })
        assertEquals(listOf("$root/Notes/Plan.md"), s.recentNotes.map { it.path })
        assertEquals(listOf("10_Journal/2026-11-03.md"), s.linkedFrom)
        assertEquals("$root/TODO.md", s.todoNote?.path)
        assertEquals("feat(api): add the thing", s.leftOff?.subjects?.first())
    }

    private val liveTodo = """
        ---
        title: "✅ Demo — TODO"
        date_updated: '2026-11-04'
        tags:
          - todo
        ---
        # ✅ Demo — TODO

        > [!INFO] The one live TODO list

        ## 👤 Yours

        - [ ] @moi Review and merge [the release PR](https://example.org/pr/12)
        - [ ] @moi Decide on the ignore rule 📅 2026-11-05
        - [x] @moi Done already

        ## 🧇 Next release (Claude, on your go)

        A due date in prose 📅 2026-12-03 is not a line.

        - [ ] Cut the **pre-release** branches
        - [ ] Ping @alice about the `RetryPolicy` ceiling
          - [ ] a nested step

        ```bash
        - [ ] not a line: code
        ```

        ## ✔️ Done recently

        - [x] Released on 5 November
    """.trimIndent()

    @Test
    fun `the live TODO's @moi lines are next actions, its other open lines are Claude's queue`() {
        val root = "20_Projects/21_Demo"
        val notes = mapOf(
            "$root/README.md" to "---\ntitle: Demo\n---\n",
            "$root/TODO.md" to liveTodo,
            "$root/Notes/Plan.md" to "---\ntitle: Plan\ndate_updated: '2026-11-01'\n---\n- [ ] write the doc\n- [ ] @moi ship it 📅 2026-11-10\n"
        )
        val s = ProjectFormat.summarize(root, notes, emptyList(), today)
        // Yours first by due date, then yours without one, then the rest: the existing order.
        assertEquals(
            listOf("Decide on the ignore rule", "ship it", "Review and merge the release PR", "write the doc"),
            s.actions.map { it.text }
        )
        assertEquals(LocalDate.of(2026, 11, 5), s.nextAction?.due)
        assertEquals("👤 Yours", s.actions.first().section)
        assertEquals("$root/TODO.md", s.actions.first().path)
        assertEquals(
            listOf("Cut the pre-release branches", "Ping @alice about the RetryPolicy ceiling", "a nested step"),
            s.claudeQueue.map { it.text }
        )
        assertEquals(setOf("🧇 Next release (Claude, on your go)"), s.claudeQueue.map { it.section }.toSet())
        assertTrue(s.claudeQueue.none { it.mine })
        assertEquals("$root/TODO.md", s.todoNote?.path)
    }

    @Test
    fun `a TODO mirrored from a repository stays a link`() {
        val root = "20_Projects/22_Demo"
        val mirrored = "---\ntitle: \"📋 Demo — Next Actions (TODO)\"\ndate_updated: '2026-11-05'\n---\n\n" +
            "> Mirrored automatically from `demo/TODO.md` — edit the source, not this copy.\n\n" +
            "# Demo workspace TODO\n\n### Open right now\n\n- [ ] a line of the repository's punch list\n- [ ] @moi even this one\n"
        val s = ProjectFormat.summarize(root, mapOf("$root/README.md" to "---\ntitle: Demo\n---\n", "$root/TODO.md" to mirrored), emptyList(), today)
        assertTrue(s.actions.isEmpty())
        assertTrue(s.claudeQueue.isEmpty())
        assertEquals("$root/TODO.md", s.todoNote?.path)
        assertTrue(s.keyNotes.isEmpty())
    }

    @Test
    fun `desktop mirrors stay out of actions, recent notes and key notes, except their index`() {
        val root = "20_Projects/21_Demo"
        fun mirror(source: String, body: String) =
            "---\ntitle: \"$source\"\ndate_updated: '2026-11-04'\nmirror_of: \"demo:$source\"\n---\n# $source\n\n$body"
        val notes = mapOf(
            "$root/README.md" to "---\ntitle: Demo\n---\n",
            "$root/Notes/Plan.md" to "---\ntitle: Plan\ndate_updated: '2026-11-01'\n---\n- [ ] write the doc\n",
            "$root/Repo/RFCs/RFC-001.md" to mirror("rfcs/RFC-001.md", "- [ ] an acceptance criterion\n"),
            "$root/Repo/Roadmaps/Roadmap_v2.md" to mirror("roadmaps/Roadmap_v2.md", "- [ ] @moi a roadmap line\n"),
            "$root/Repo/Docs/guide.md" to mirror("docs/guide.md", "Text.\n"),
            "$root/Claude/Memory/feedback.md" to mirror("memory/feedback.md", "- [ ] not yours either\n"),
            "$root/Repo/00_Index_demo.md" to "---\ntitle: \"Index\"\nmirror_of: \"demo:__index__\"\n---\n# Index\n"
        )
        val links = listOf("10_Journal/2026-11-03.md" to LinkExtractor.keyOfPath("$root/Repo/Docs/guide.md"))
        val s = ProjectFormat.summarize(root, notes, links, today)
        assertEquals(listOf("write the doc"), s.actions.map { it.text })
        assertEquals(listOf("$root/Notes/Plan.md"), s.recentNotes.map { it.path })
        assertEquals(listOf("$root/Repo/00_Index_demo.md"), s.keyNotes.map { it.path })
        // Still linkable: a link into a mirror is a link into the project.
        assertEquals(listOf("10_Journal/2026-11-03.md"), s.linkedFrom)
    }

    @Test
    fun `actions are shown without markdown`() {
        assertEquals("Fix the kernel: refactor AbstractKernel", ProjectFormat.plainText("**Fix the kernel**: refactor `AbstractKernel`"))
        assertEquals("see the plan and Roadmap", ProjectFormat.plainText("see [[Notes/Plan|the plan]] and [[20_Projects/x/Roadmap]]"))
        assertEquals("read the docs", ProjectFormat.plainText("read [the docs](https://example.org)"))
    }
}
