package cloud.wafflecommons.pixelbrainreader.domain.homeos

import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChoreEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ChoreResetTest {

    private val today = LocalDate.of(2026, 11, 15)

    private fun chore(name: String, minutes: Int, daysAgo: Long?, every: Int = 7, archived: Boolean = false) = ChoreEntity(
        id = name, name = name, roomId = "r", baseEffort = 10, frequencyDays = every,
        lastDoneDate = daysAgo?.let { today.minusDays(it).toString() } ?: "", archived = archived, minutes = minutes
    )

    @Test
    fun `the most overdue chores that fit fifteen minutes`() {
        val plan = ChoreReset.plan(
            listOf(
                chore("dishes", 10, daysAgo = 14),   // 2× overdue
                chore("plants", 5, daysAgo = 21),    // 3× overdue
                chore("windows", 10, daysAgo = 10),  // 1.4×: no room left after plants and dishes
                chore("bins", 5, daysAgo = 2)        // not due
            ),
            today
        )
        assertEquals(listOf("plants", "dishes"), plan.picks.map { it.name })
        assertEquals(15, plan.minutes)
    }

    @Test
    fun `a smaller chore further down still fills the room left`() {
        val plan = ChoreReset.plan(listOf(chore("big", 20, 30), chore("small", 5, 8)), today)
        assertEquals(listOf("small"), plan.picks.map { it.name })
    }

    @Test
    fun `due chores without minutes are listed apart, archived ones never come`() {
        val plan = ChoreReset.plan(listOf(chore("unknown", 0, null), chore("old", 5, 30, archived = true)), today)
        assertEquals(emptyList<String>(), plan.picks.map { it.name })
        assertEquals(listOf("unknown"), plan.withoutMinutes.map { it.name })
    }
}
