package cloud.wafflecommons.pixelbrainreader.ui.work

import cloud.wafflecommons.pixelbrainreader.data.work.WorklogDay
import cloud.wafflecommons.pixelbrainreader.data.work.projectBadgeText
import cloud.wafflecommons.pixelbrainreader.data.work.projectDisplayName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The Projects list's cards (12.7.0): names, badges and the 14-day bars. */
class ProjectsListTest {

    @Test
    fun `a folder name reads as a name`() {
        assertEquals("Garden Planner", projectDisplayName("21_Garden_Planner"))
        assertEquals("Recipe Box", projectDisplayName("Recipe_Box"))
        assertEquals("Garden Planner", projectDisplayName("21-Garden Planner"))
        // A README title keeps its spelling; a number that is the name is kept.
        assertEquals("Garden Planner v2", projectDisplayName("Garden Planner v2"))
        assertEquals("2048", projectDisplayName("2048"))
        assertEquals("21_", projectDisplayName("21_"))
    }

    @Test
    fun `the badge shows the folder's number, else the name's initials`() {
        assertEquals("21", projectBadgeText("21_Garden_Planner", "21_Garden_Planner"))
        assertEquals("21", projectBadgeText("21_Garden_Planner", "A README title"))
        assertEquals("RB", projectBadgeText("Recipe_Box", "Recipe_Box"))
        assertEquals("G", projectBadgeText("garden", "garden"))
        assertEquals("GP", projectBadgeText("sub", "Garden Planner Mobile App"))
    }

    @Test
    fun `fourteen days of commits, oldest first, a quiet day at zero`() {
        val today = LocalDate.of(2026, 11, 9)
        val days = listOf(
            day(today, 3),
            day(today.minusDays(1), 5),
            day(today.minusDays(1), 2), // two worklogs on one date add up
            day(today.minusDays(13), 1),
            day(today.minusDays(14), 9) // outside the window
        )

        val perDay = commitsPerDay(days, today)

        assertEquals(14, perDay.size)
        assertEquals(1, perDay.first())
        assertEquals(listOf(7, 3), perDay.takeLast(2))
        assertEquals(11, perDay.sum())
    }

    @Test
    fun `how long ago a project moved`() {
        val today = LocalDate.of(2026, 11, 9)
        assertNull(activityAgo(null, today))
        assertEquals("active today", activityAgo(today, today))
        assertEquals("active yesterday", activityAgo(today.minusDays(1), today))
        assertEquals("active 12 days ago", activityAgo(today.minusDays(12), today))
        // The month in the device's language (English on the owner's devices; the JVM's may differ).
        val march = LocalDate.of(2026, 3, 2)
        assertEquals("last active " + march.format(DateTimeFormatter.ofPattern("MMM yyyy")), activityAgo(march, today))
    }

    private fun day(date: LocalDate, commits: Int) =
        WorklogDay(date = date, commits = commits, sessions = 0, prompts = 0, tokens = 0, subjects = emptyList())
}
