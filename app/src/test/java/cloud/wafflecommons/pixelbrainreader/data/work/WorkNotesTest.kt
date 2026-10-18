package cloud.wafflecommons.pixelbrainreader.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class WorkNotesTest {

    @Test
    fun `statuses found in the vault map to board columns`() {
        val expected = mapOf(
            "📥 inbox" to StatusColumn.TODO,
            "💡 idées" to StatusColumn.TODO,
            "draft" to StatusColumn.TODO,
            "🏗️ wip" to StatusColumn.WIP,
            "🏗️ en cours" to StatusColumn.WIP,
            "active" to StatusColumn.WIP,
            "🟡 à relire puis envoyer" to StatusColumn.WIP,
            "⏸️ en pause" to StatusColumn.PAUSED,
            "🟢 done" to StatusColumn.DONE,
            "🟢 réalisé" to StatusColumn.DONE,
            "evergreen" to StatusColumn.DONE,
            "📦 archive" to StatusColumn.ARCHIVE,
            "archived" to StatusColumn.ARCHIVE
        )
        expected.forEach { (status, column) -> assertEquals(status, column, StatusColumn.of(status)) }
        assertNull(StatusColumn.of("confidential"))
        assertNull(StatusColumn.of(null))
    }

    @Test
    fun `a note's title comes from its frontmatter, then its H1, then its file name`() {
        val withFrontmatter = WorkNote.from(
            "20_Projects/x.md",
            "---\ntitle: \"Plan\"\ndate_created: '2026-10-01'\ndate_updated: '2026-10-18'\ntype: projet\nstatus: 🏗️ wip\ntags:\n  - api\naliases: []\n---\n# Other\n"
        )
        assertEquals("Plan", withFrontmatter.title)
        assertEquals(StatusColumn.WIP, withFrontmatter.column)
        assertEquals(LocalDate.of(2026, 10, 18), withFrontmatter.updated)
        assertEquals(listOf("api"), withFrontmatter.tags)
        assertEquals("x", withFrontmatter.linkName)

        assertEquals("Heading", WorkNote.from("a/b.md", "# Heading\ntext").title)
        assertEquals("b", WorkNote.from("a/b.md", "text").title)
    }
}
