package cloud.wafflecommons.pixelbrainreader.data.utils

import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyDashboardEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.GratitudeEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ScratchNoteEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `burn then parse preserves google event and task identity`() {
        val dashboard = DailyDashboardEntity(date = date)
        val timeline = listOf(
            TimelineEntryEntity(date = date, time = LocalTime.of(9, 0), content = "[Work] Standup", googleEventId = "evt_1"),
            TimelineEntryEntity(date = date, time = LocalTime.of(11, 0), content = "Local meeting") // no key
        )
        val tasks = listOf(
            DailyTaskEntity(scheduledDate = "2026-07-17", label = "Google chore", googleTaskId = "gt_1", source = "GoogleTasks"),
            DailyTaskEntity(scheduledDate = "2026-07-17", label = "Local chore") // source defaults to Local
        )

        val md = MarkdownBurner.burn(dashboard, timeline, tasks)
        // The invisible marker must be present in the file but never leak into a rendered value.
        assertTrue(md.contains("<!--gcal:evt_1-->"))
        assertTrue(md.contains("<!--gtask:gt_1-->"))

        val parsed = DailyMarkdownParser.parse(date, md)

        val timelineByContent = parsed.timeline.associateBy { it.content }
        assertEquals("evt_1", timelineByContent["[Work] Standup"]!!.googleEventId)
        assertNull(timelineByContent["Local meeting"]!!.googleEventId)
        // Display content is clean — the marker is stripped out.
        assertFalse(parsed.timeline.any { it.content.contains("<!--") })

        val tasksByLabel = parsed.tasks.associateBy { it.label }
        assertEquals("gt_1", tasksByLabel["Google chore"]!!.googleTaskId)
        assertEquals("GoogleTasks", tasksByLabel["Google chore"]!!.source)
        assertNull(tasksByLabel["Local chore"]!!.googleTaskId)
        assertEquals("Local", tasksByLabel["Local chore"]!!.source)
        assertFalse(parsed.tasks.any { it.label.contains("<!--") })
    }

    @Test
    fun `parse collapses a tripled google timeline entry keeping the keyed copy`() {
        // Simulates a journal already polluted before the fix: two stripped orphans plus the
        // one surviving keyed line, all byte-identical after the marker is stripped.
        val md = """
            # 2026-07-17

            ## 🗓️ Timeline

            - 09:00 [Work] Standup
            - 09:00 [Work] Standup
            - 09:00 [Work] Standup <!--gcal:evt_1-->
        """.trimIndent()

        val parsed = DailyMarkdownParser.parse(date, md)

        assertEquals(1, parsed.timeline.size)
        assertEquals("[Work] Standup", parsed.timeline.single().content)
        assertEquals("evt_1", parsed.timeline.single().googleEventId)
    }

    @Test
    fun `parse collapses a tripled google task keeping the keyed copy`() {
        val md = """
            # 2026-07-17

            ## 📝 Journal

            - [ ] Water the plants
            - [ ] Water the plants
            - [ ] Water the plants <!--gtask:gt_1-->
        """.trimIndent()

        val parsed = DailyMarkdownParser.parse(date, md)

        assertEquals(1, parsed.tasks.size)
        assertEquals("Water the plants", parsed.tasks.single().label)
        assertEquals("gt_1", parsed.tasks.single().googleTaskId)
        assertEquals("GoogleTasks", parsed.tasks.single().source)
    }

    @Test
    fun `parse keeps distinct timeline entries that merely share a time`() {
        val md = """
            # 2026-07-17

            ## 🗓️ Timeline

            - 09:00 Water plants
            - 09:00 Feed the cat
        """.trimIndent()

        val parsed = DailyMarkdownParser.parse(date, md)

        assertEquals(2, parsed.timeline.size)
        assertEquals(setOf("Water plants", "Feed the cat"), parsed.timeline.map { it.content }.toSet())
    }

    @Test
    fun `dedup keeps two distinct calendar events sharing a minute and content`() {
        // Two genuinely-separate Google events (distinct ids) that render identically must NOT
        // be collapsed — regression guard for the review finding on dedupeTimeline.
        val md = """
            # 2026-07-17

            ## 🗓️ Timeline

            - 09:00 [Work] Standup <!--gcal:evt_a-->
            - 09:00 [Work] Standup <!--gcal:evt_b-->
        """.trimIndent()

        val parsed = DailyMarkdownParser.parse(date, md)

        assertEquals(2, parsed.timeline.size)
        assertEquals(setOf("evt_a", "evt_b"), parsed.timeline.map { it.googleEventId }.toSet())
    }

    @Test
    fun `dedup keeps two distinct google tasks sharing a label`() {
        val md = """
            # 2026-07-17

            ## 📝 Journal

            - [ ] Follow up <!--gtask:gt_a-->
            - [ ] Follow up <!--gtask:gt_b-->
        """.trimIndent()

        val parsed = DailyMarkdownParser.parse(date, md)

        assertEquals(2, parsed.tasks.size)
        assertEquals(setOf("gt_a", "gt_b"), parsed.tasks.map { it.googleTaskId }.toSet())
    }

    @Test
    fun `label containing an at-time substring is not mis-parsed as a scheduled time`() {
        val md = """
            # 2026-07-17

            ## 📝 Journal

            - [ ] Meet Bob at 10:00
        """.trimIndent()

        val parsed = DailyMarkdownParser.parse(date, md)

        val task = parsed.tasks.single()
        assertEquals("Meet Bob at 10:00", task.label)
        assertNull(task.scheduledTime)
    }

    @Test
    fun `leading at-time prefix is still parsed into scheduled time`() {
        val md = """
            # 2026-07-17

            ## 📝 Journal

            - [ ] at 10:15 Write report
        """.trimIndent()

        val parsed = DailyMarkdownParser.parse(date, md)

        val task = parsed.tasks.single()
        assertEquals("Write report", task.label)
        assertEquals("10:15", task.scheduledTime)
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
