package cloud.wafflecommons.pixelbrainreader.data.utils

import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyDashboardEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.GratitudeEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ScratchNoteEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Round-trips [MarkdownBurner.burn] → [DailyMarkdownParser.parse]. This is the safety net
 * behind the P0.1 cold-start recovery: after a destructive Room migration we rebuild today's
 * board from the burned markdown, so burn/parse MUST agree on the format.
 */
class DailyMarkdownParserTest {

    private val date = LocalDate.of(2026, 7, 17)

    @Test
    fun `burn then parse restores timeline tasks ideas notes scraps and gratitude`() {
        val dashboard = DailyDashboardEntity(
            date = date,
            dailyMantra = "Carpe diem",
            ideasContent = "An idea worth keeping",
            notesContent = "A self-care note"
        )
        val timeline = listOf(
            TimelineEntryEntity(date = date, time = LocalTime.of(9, 0), content = "Standup"),
            TimelineEntryEntity(date = date, time = LocalTime.of(14, 30), content = "Deep work")
        )
        val tasks = listOf(
            DailyTaskEntity(scheduledDate = "2026-07-17", label = "Write report", isDone = false, priority = 2, scheduledTime = "10:15"),
            DailyTaskEntity(scheduledDate = "2026-07-17", label = "Buy milk", isDone = true, priority = 1, scheduledTime = null)
        )
        val scraps = listOf(ScratchNoteEntity(content = "random thought"), ScratchNoteEntity(content = "another scrap"))
        val gratitudes = listOf(GratitudeEntity(date = "2026-07-17", content = "Sunny weather"))

        val md = MarkdownBurner.burn(dashboard, timeline, tasks, scraps, gratitudes, existingFrontmatter = "")
        val parsed = DailyMarkdownParser.parse(date, md)

        // Timeline
        assertEquals(2, parsed.timeline.size)
        assertEquals(setOf("Standup", "Deep work"), parsed.timeline.map { it.content }.toSet())
        assertEquals(setOf(LocalTime.of(9, 0), LocalTime.of(14, 30)), parsed.timeline.map { it.time }.toSet())

        // Tasks (order may change due to burn sorting → compare by label)
        val byLabel = parsed.tasks.associateBy { it.label }
        assertEquals(2, parsed.tasks.size)
        assertEquals(false, byLabel["Write report"]!!.isDone)
        assertEquals(2, byLabel["Write report"]!!.priority)
        assertEquals("10:15", byLabel["Write report"]!!.scheduledTime)
        assertEquals(true, byLabel["Buy milk"]!!.isDone)
        assertEquals(1, byLabel["Buy milk"]!!.priority)
        assertEquals(null, byLabel["Buy milk"]!!.scheduledTime)

        // Second brain
        assertEquals("An idea worth keeping", parsed.ideas)
        assertEquals("A self-care note", parsed.notes)

        // Scraps + gratitude (the sections the old ingest silently dropped)
        assertEquals(setOf("random thought", "another scrap"), parsed.scraps.toSet())
        assertEquals(listOf("Sunny weather"), parsed.gratitude)
    }

    @Test
    fun `parse tolerates an empty board`() {
        val dashboard = DailyDashboardEntity(date = date)
        val md = MarkdownBurner.burn(dashboard, emptyList(), emptyList())
        val parsed = DailyMarkdownParser.parse(date, md)

        assertTrue(parsed.timeline.isEmpty())
        assertTrue(parsed.tasks.isEmpty())
        assertTrue(parsed.scraps.isEmpty())
        assertTrue(parsed.gratitude.isEmpty())
    }

    @Test
    fun `gratitude checkbox prefix is stripped`() {
        val md = """
            # 2026-07-17

            ## 🌟 Gratitude

            - [x] Grateful for coffee
        """.trimIndent()
        val parsed = DailyMarkdownParser.parse(date, md)
        assertEquals(listOf("Grateful for coffee"), parsed.gratitude)
    }
}
