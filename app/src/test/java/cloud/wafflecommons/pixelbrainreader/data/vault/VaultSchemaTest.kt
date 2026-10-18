package cloud.wafflecommons.pixelbrainreader.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class VaultSchemaTest {

    @Test
    fun `the folder decides the type`() {
        assertEquals("inbox", VaultSchema.typeFor("00_Inbox/Idea.md"))
        assertEquals("log", VaultSchema.typeFor("10_Journal/2026-10-18.md"))
        assertEquals("projet", VaultSchema.typeFor("20_Projects/21_App/RFCs/RFC_001.md"))
        assertEquals("area", VaultSchema.typeFor("30_Areas/Health.md"))
        assertEquals("ressource", VaultSchema.typeFor("40_Resources/42_Languages/Kotlin.md"))
        assertEquals("archive", VaultSchema.typeFor("90_Archives/Old.md"))
        assertEquals("private", VaultSchema.typeFor("99_Private/Diary.md.enc"))
        assertEquals("system", VaultSchema.typeFor("99_System/Templates/T_Note.md"))
        assertEquals("note", VaultSchema.typeFor("Loose note.md"))
    }

    @Test
    fun `each type has the vault's default status`() {
        assertEquals("📥 inbox", VaultSchema.defaultStatus("inbox"))
        assertEquals("🏗️ wip", VaultSchema.defaultStatus("projet"))
        assertEquals("📦 archive", VaultSchema.defaultStatus("archive"))
        assertEquals("🟢 done", VaultSchema.defaultStatus("area"))
        assertEquals("🟢 done", VaultSchema.defaultStatus("note"))
    }

    @Test
    fun `frontmatter has the standard keys in order then the extras`() {
        val fm = VaultSchema.frontmatter(
            title = "Plan \"B\" — draft",
            path = "20_Projects/21_App/Plan.md",
            date = LocalDate.of(2026, 10, 18),
            tags = listOf("#Work", "Road Map", "work"),
            extra = listOf("source" to VaultSchema.doubleQuoted("https://example.com/a?b=c"))
        )
        assertEquals(
            "---\n" +
                "title: \"Plan \\\"B\\\" — draft\"\n" +
                "date_created: '2026-10-18'\n" +
                "date_updated: '2026-10-18'\n" +
                "type: projet\n" +
                "status: 🏗️ wip\n" +
                "tags:\n  - work\n  - road-map\n" +
                "aliases: []\n" +
                "source: \"https://example.com/a?b=c\"\n" +
                "---\n",
            fm
        )
    }

    @Test
    fun `no tags is an empty list and an explicit status wins`() {
        val fm = VaultSchema.frontmatter("T", "00_Inbox/T.md", LocalDate.of(2026, 1, 2), status = "🟡 draft")
        assertTrue(fm.contains("tags: []\n"))
        assertTrue(fm.contains("status: 🟡 draft\n"))
        assertTrue(fm.contains("type: inbox\n"))
    }

    @Test
    fun `scalars stay on one line`() {
        assertEquals("\"a b\"", VaultSchema.doubleQuoted(" a\nb "))
        assertEquals("'it''s'", VaultSchema.singleQuoted("it's"))
    }

    @Test
    fun `daily notes are recognised by path`() {
        assertTrue(VaultSchema.isDailyNote("10_Journal/2026-10-18.md"))
        assertFalse(VaultSchema.isDailyNote("10_Journal/system/memory_echoes.json"))
        assertFalse(VaultSchema.isDailyNote("20_Projects/2026-10-18.md"))
    }
}
