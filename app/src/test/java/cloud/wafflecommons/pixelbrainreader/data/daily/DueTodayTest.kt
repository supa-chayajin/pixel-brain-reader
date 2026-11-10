package cloud.wafflecommons.pixelbrainreader.data.daily

import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChoreEntity
import cloud.wafflecommons.pixelbrainreader.data.work.WorkNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DueTodayTest {

    private val today = LocalDate.of(2026, 11, 11)

    private fun note(path: String, body: String, tags: String? = null): WorkNote {
        val frontmatter = if (tags != null) "---\ntitle: ${path.substringAfterLast('/').removeSuffix(".md")}\ntags: [$tags]\n---\n" else ""
        return WorkNote.from(path, frontmatter + body)
    }

    @Test
    fun `open milestones due today or earlier are listed, not later, done or undated ones`() {
        val objective = note(
            "30_Career/Lead the migration.md",
            """
            - [ ] Draft the plan 📅 2026-11-09
            - [ ] Present it [due:: 2026-11-11]
            - [ ] Ship it 📅 2026-12-01
            - [x] Kick-off 📅 2026-11-01
            - [ ] Someday
            """.trimIndent(),
            tags = "objectif"
        )
        val items = DueToday.fromNotes(listOf(objective), today)
        assertEquals(listOf("Draft the plan", "Present it"), items.map { it.text })
        assertTrue(items.all { it.kind == DueKind.MILESTONE && it.context == "Lead the migration" })
        assertEquals(listOf(2L, 0L), items.map { it.overdueDays(today) })
    }

    @Test
    fun `only your own actions count, from any note but daily notes`() {
        val meeting = note(
            "20_Projects/21_App/Meetings/Sync.md",
            "- [ ] @moi Send the notes 📅 2026-11-10\n- [ ] @alex Book the room 📅 2026-11-10\n- [x] @moi Done already 📅 2026-11-10\n- [ ] @me Call back 📅 2026-11-11"
        )
        val daily = note("10_Journal/2026-11-10.md", "- [ ] @moi A board task 📅 2026-11-10")
        val items = DueToday.fromNotes(listOf(meeting, daily), today)
        assertEquals(listOf("Send the notes", "Call back"), items.map { it.text })
        assertTrue(items.all { it.kind == DueKind.ACTION && it.path == meeting.path })
    }

    @Test
    fun `desktop mirrors and worklogs are never parsed`() {
        val mirror = note(
            "20_Projects/21_App/Repo/TODO.md",
            "---\nmirror_of: \"app:TODO.md\"\n---\n- [ ] @moi From the repository 📅 2026-11-01"
        )
        val worklog = note("20_Projects/21_App/Worklog/2026-11-10.md", "- [ ] @moi From a session 📅 2026-11-01")
        assertTrue(DueToday.fromNotes(listOf(mirror, worklog), today).isEmpty())
    }

    @Test
    fun `an @moi milestone shows once, as a milestone, without the handle`() {
        val objective = note("30_Career/Talk.md", "- [ ] @moi Write the abstract 📅 2026-11-11", tags = "objective")
        val items = DueToday.fromNotes(listOf(objective), today)
        assertEquals(1, items.size)
        assertEquals(DueKind.MILESTONE, items.single().kind)
        assertEquals("Write the abstract", items.single().text)
    }

    @Test
    fun `a chore is due once its frequency has passed, and a chore never done is due today`() {
        fun chore(id: String, last: String, every: Int, archived: Boolean = false) =
            ChoreEntity(id = id, name = id, roomId = "kitchen", baseEffort = 10, frequencyDays = every, lastDoneDate = last, archived = archived)
        val items = DueToday.fromChores(
            listOf(
                chore("Dishes", "2026-11-10", 1),
                chore("Floors", "2026-11-01", 7),
                chore("Windows", "2026-11-05", 30),
                chore("Oven", "", 14),
                chore("Old", "2026-01-01", 7, archived = true)
            ),
            mapOf("kitchen" to "Kitchen"),
            today
        )
        assertEquals(mapOf("Dishes" to today, "Floors" to LocalDate.of(2026, 11, 8), "Oven" to today), items.associate { it.text to it.due })
        assertTrue(items.all { it.kind == DueKind.CHORE && it.context == "Kitchen" && it.path == null })
    }

    @Test
    fun `the most overdue comes first, then today's`() {
        val items = listOf(
            DueItem(DueKind.CHORE, "b chore", today, "Home"),
            DueItem(DueKind.ACTION, "late action", today.minusDays(3), "Note", "n.md"),
            DueItem(DueKind.MILESTONE, "a milestone", today, "Goal", "g.md")
        )
        assertEquals(listOf("late action", "a milestone", "b chore"), DueToday.sorted(items).map { it.text })
    }

    @Test
    fun `the chores sum up in one line, rooms by urgency, the oldest due date`() {
        val items = listOf(
            DueItem(DueKind.CHORE, "Vaisselle", today, "Cuisine"),
            DueItem(DueKind.CHORE, "Aspirateur", today.minusDays(1), "Salle de séjour"),
            DueItem(DueKind.CHORE, "Aspirateur", today.minusDays(1), "Cuisine"),
            DueItem(DueKind.ACTION, "Send the notes", today.minusDays(5), "Sync", "n.md")
        )
        assertEquals(DueToday.ChoreSummary(3, listOf("Salle de séjour", "Cuisine"), today.minusDays(1)), DueToday.choreSummary(items))
        assertEquals(null, DueToday.choreSummary(items.filter { it.kind != DueKind.CHORE }))
    }
}
