package cloud.wafflecommons.pixelbrainreader.data.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TemplateEngineTest {

    private val template = """
        ---
        title: "Goal: {{goal_name}}"
        date_created: '2026-04-13'
        date_updated: '2026-04-13'
        type: area
        status: active
        tags: []
        aliases: []
        ---
        # {{ goal_name }} — {{title}}
        Start: {{start_date}} · Deadline: {{deadline}} · {{date}} {{time}}
        Area: {{area_name}}
    """.trimIndent()

    @Test
    fun `every variable but date, time and title is a field, once, in order`() {
        assertEquals(listOf("goal_name", "start_date", "deadline", "area_name"), TemplateEngine.variables(template))
        assertTrue(TemplateEngine.isDateVariable("start_date"))
        assertTrue(TemplateEngine.isDateVariable("deadline"))
        assertFalse(TemplateEngine.isDateVariable("area_name"))
    }

    @Test
    fun `fields are filled, blanks stay as placeholders and the frontmatter dates become today`() {
        val note = TemplateEngine.apply(
            template,
            title = "Sécurité",
            values = mapOf("goal_name" to "Durcir la CI", "deadline" to "2026-12-31", "area_name" to "  "),
            now = LocalDateTime.of(2026, 10, 18, 9, 5)
        )
        assertTrue(note.contains("title: \"Goal: Durcir la CI\""))
        assertTrue(note.contains("date_created: '2026-10-18'\ndate_updated: '2026-10-18'"))
        assertTrue(note.contains("# Durcir la CI — Sécurité"))
        assertTrue(note.contains("Start: {{start_date}} · Deadline: 2026-12-31 · 2026-10-18 09:05"))
        assertTrue(note.contains("Area: {{area_name}}"))
    }

    @Test
    fun `a daily template with only date keeps its shape`() {
        assertEquals("---\ndate: 2026-10-18\n---\n# 2026-10-18", TemplateEngine.apply("---\ndate: {{date}}\n---\n# {{title}}", "2026-10-18", now = LocalDateTime.of(2026, 10, 18, 9, 0)))
    }
}
