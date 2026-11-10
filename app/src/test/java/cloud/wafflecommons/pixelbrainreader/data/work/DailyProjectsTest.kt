package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.model.DailySection
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** The Daily screen's Projects block: the pinned projects in their order, else the most active few. */
class DailyProjectsTest {

    private val today = LocalDate.of(2026, 11, 10)

    @Test
    fun `the pinned projects show, in the pins' order, hidden ones never`() {
        val arranged = ProjectArrangement(pinned = listOf(root("b"), root("a"), root("h")), hidden = listOf(root("h")))
            .arrange(listOf(project("a", 1), project("b", 5), project("c", 0), project("h", 0)))
        assertEquals(listOf("b", "a"), DailyProjects.pick(arranged).map { it.title })
    }

    @Test
    fun `with nothing pinned, the three most recently active show`() {
        val arranged = ProjectArrangement().arrange(
            listOf(project("old", 30), project("new", 0), project("mid", 4), project("quiet", null), project("week", 7))
        )
        assertEquals(listOf("new", "mid", "week"), DailyProjects.pick(arranged).map { it.title })
    }

    @Test
    fun `at most six cards`() {
        val names = (1..9).map { "p$it" }
        val arranged = ProjectArrangement(pinned = names.map(::root)).arrange(names.map { project(it, 1) })
        assertEquals(DailyProjects.MAX_CARDS, DailyProjects.pick(arranged).size)
    }

    @Test
    fun `the Projects block joins a saved order at its end, outside the shown set`() {
        // An install that ordered and chose its blocks before the Projects block existed.
        val order = DailySection.orderFromKeys("tasks,weather,vitals,timeline,gratitude,countdowns,mantra,echoes,second_brain,scratchpad")
        assertEquals(DailySection.PROJECTS, order.last())
        assertEquals(DailySection.TASKS, order.first())
        assertEquals(false, DailySection.PROJECTS in DailySection.fromKeys(setOf("tasks", "weather")))
    }

    private fun root(name: String) = "20_Projects/$name"

    /** A project last active [daysAgo] days ago; null for one without activity. */
    private fun project(name: String, daysAgo: Long?) = ProjectSummary(
        root = root(name),
        title = name,
        status = null,
        lastActivity = daysAgo?.let { today.minusDays(it) },
        days = emptyList(),
        actions = emptyList(),
        claudeQueue = emptyList(),
        keyNotes = emptyList(),
        recentNotes = emptyList(),
        linkedFrom = emptyList(),
        todoNote = null
    )
}
