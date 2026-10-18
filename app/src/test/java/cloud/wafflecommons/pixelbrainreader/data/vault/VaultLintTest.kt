package cloud.wafflecommons.pixelbrainreader.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class VaultLintTest {

    private val today = LocalDate.of(2026, 10, 18)
    private val resolves: (String) -> Boolean = { it in setOf("plan", "image.png") }

    private val clean = """
        ---
        title: "Plan"
        date_created: '2026-10-01'
        date_updated: '2026-10-18'
        type: projet
        status: 🏗️ wip
        tags:
          - api
        aliases: []
        ---
        # Plan

        See [[Plan]] and ![[image.png]].
    """.trimIndent() + "\n"

    @Test
    fun `a note that follows the contract has nothing to report`() {
        assertEquals(emptyList<LintIssue>(), VaultLint.check("20_Projects/Plan.md", clean, resolves))
    }

    @Test
    fun `each rule is reported, and only some are fixable`() {
        val messy = "---\r\ntitle: Plan\r\ndate_created: 2026-10-01\r\ntype: inbox\r\nstatus: active\r\ntags:\r\n  - API\r\n---\r\n# Plan \r\n# Second\r\n[[Missing]]"
        val issues = VaultLint.check("20_Projects/Plan.md", messy, resolves)
        val messages = issues.map { it.message }
        assertTrue(messages.contains("Missing date_updated, aliases"))
        assertTrue(messages.contains("title is not double-quoted"))
        assertTrue(messages.contains("date_created is not 'YYYY-MM-DD'"))
        assertTrue(messages.contains("The folder implies type projet (found inbox)"))
        assertTrue(messages.contains("status \"active\" is not emoji-prefixed"))
        assertTrue(messages.contains("Tags must be lowercase"))
        assertTrue(messages.contains("2 H1 headings (exactly one expected)"))
        assertTrue(messages.contains("Links that don't resolve: [[Missing]]"))
        assertTrue(messages.contains("CRLF line endings"))
        assertTrue(messages.contains("Trailing whitespace"))
        assertTrue(messages.contains("No newline at the end of the file"))
        assertFalse(issues.first { it.message.startsWith("The folder implies") }.fixable)
    }

    @Test
    fun `the safe fixes touch only their lines`() {
        val messy = "---\r\ntitle: Plan\r\ndate_created: 2026-10-01\r\ntype: projet\r\nstatus: 🏗️ wip\r\ntags: []\r\n---\r\n# Plan  \r\n```\r\nkeep  \r\n```"
        val fixed = VaultLint.fix("20_Projects/Plan.md", messy, today)
        assertEquals(
            "---\ntitle: \"Plan\"\ndate_created: '2026-10-01'\ndate_updated: '2026-10-18'\ntype: projet\nstatus: 🏗️ wip\ntags: []\naliases: []\n---\n# Plan\n```\nkeep  \n```\n",
            fixed
        )
        assertEquals(VaultLint.check("20_Projects/Plan.md", fixed, resolves).filter { it.fixable }, emptyList<LintIssue>())
    }

    @Test
    fun `daily notes carry only date, generated and excluded files are left alone`() {
        assertEquals("R2", VaultLint.check("10_Journal/2026-10-18.md", "---\ndate: 2026-10-18\ntype: log\n---\n# 2026-10-18\n", resolves).single().rule)
        assertTrue(VaultLint.check("10_Journal/2026-10-18.md", "---\ndate: 2026-10-18\n---\n# 2026-10-18\n", resolves).isEmpty())
        assertTrue(VaultLint.check("20_Projects/X/Worklog/2026-10-18.md", "no frontmatter", resolves).isEmpty())
        assertTrue(VaultLint.check("00_Dashboard.md", "anything", resolves).isEmpty())
        assertEquals("anything", VaultLint.fix("99_Private/a.md.enc", "anything", today))
    }

    @Test
    fun `inbox filing weighs linked notes twice and never offers the inbox, the journal or system folders`() {
        val folders = InboxFiling.folders(
            similar = listOf("20_Projects/API/a.md", "20_Projects/API/b.md", "40_Resources/Dev/c.md", "00_Inbox/d.md", "10_Journal/2026-10-01.md"),
            linked = listOf("40_Resources/Dev/e.md", "99_System/Templates/T.md")
        )
        assertEquals(listOf("40_Resources/Dev", "20_Projects/API"), folders)
        assertEquals(listOf("api", "kotlin"), InboxFiling.tags(listOf(listOf("api", "kotlin"), listOf("API", "inbox")), current = emptyList(), limit = 2))
    }

    @Test
    fun `a tag list is written block style`() {
        val content = "---\ntitle: \"x\"\ntags: []\naliases: []\n---\n"
        assertEquals("---\ntitle: \"x\"\ntags:\n  - a\n  - b\naliases: []\n---\n", FrontmatterLines.setList(content, "tags", listOf("a", "b")))
        assertEquals(content, FrontmatterLines.setList(FrontmatterLines.setList(content, "tags", listOf("a")), "tags", emptyList()))
    }
}
