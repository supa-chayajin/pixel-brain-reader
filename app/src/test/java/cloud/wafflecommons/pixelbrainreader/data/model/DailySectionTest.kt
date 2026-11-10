package cloud.wafflecommons.pixelbrainreader.data.model

import cloud.wafflecommons.pixelbrainreader.data.model.DailySection.COUNTDOWNS
import cloud.wafflecommons.pixelbrainreader.data.model.DailySection.ECHOES
import cloud.wafflecommons.pixelbrainreader.data.model.DailySection.GRATITUDE
import cloud.wafflecommons.pixelbrainreader.data.model.DailySection.MANTRA
import cloud.wafflecommons.pixelbrainreader.data.model.DailySection.PROJECTS
import cloud.wafflecommons.pixelbrainreader.data.model.DailySection.SCRATCHPAD
import cloud.wafflecommons.pixelbrainreader.data.model.DailySection.SECOND_BRAIN
import cloud.wafflecommons.pixelbrainreader.data.model.DailySection.TASKS
import cloud.wafflecommons.pixelbrainreader.data.model.DailySection.TIMELINE
import cloud.wafflecommons.pixelbrainreader.data.model.DailySection.VITALS
import cloud.wafflecommons.pixelbrainreader.data.model.DailySection.WEATHER
import org.junit.Assert.assertEquals
import org.junit.Test

class DailySectionTest {

    private val default = DailySection.entries.toList()

    @Test
    fun `nothing stored means the default order`() {
        assertEquals(default, DailySection.orderFromKeys(null))
        assertEquals(default, DailySection.orderFromKeys(""))
    }

    @Test
    fun `the stored order comes first, unknown and repeated keys are dropped, the rest follows`() {
        val order = DailySection.orderFromKeys("mantra, gone,weather,mantra")
        assertEquals(listOf(MANTRA, WEATHER), order.take(2))
        assertEquals(default - MANTRA - WEATHER, order.drop(2))
    }

    @Test
    fun `an order round-trips through its keys`() {
        val order = default.reversed()
        assertEquals(order, DailySection.orderFromKeys(DailySection.toKeys(order)))
    }

    @Test
    fun `a move skips the blocks of the other group`() {
        val shown = DailySection.DEFAULT_SHOWN + MANTRA
        // Mantra is after Projects, Gratitude and Countdowns (folded): up swaps it with Timeline, the last shown block.
        val moved = DailySection.moved(default, shown, MANTRA, up = true)
        assertEquals(listOf(WEATHER, VITALS, TASKS, MANTRA, TIMELINE), moved.filter { it in shown })
        assertEquals(listOf(PROJECTS, GRATITUDE, COUNTDOWNS, ECHOES, SECOND_BRAIN, SCRATCHPAD), moved.filter { it !in shown })
    }

    @Test
    fun `folded blocks move among themselves`() {
        val shown = DailySection.DEFAULT_SHOWN
        val moved = DailySection.moved(default, shown, COUNTDOWNS, up = true)
        assertEquals(listOf(PROJECTS, COUNTDOWNS, GRATITUDE, MANTRA, ECHOES, SECOND_BRAIN, SCRATCHPAD), moved.filter { it !in shown })
        assertEquals(listOf(WEATHER, VITALS, TASKS, TIMELINE), moved.filter { it in shown })
    }

    @Test
    fun `nothing moves past the edge of a group`() {
        val shown = DailySection.DEFAULT_SHOWN
        assertEquals(default, DailySection.moved(default, shown, WEATHER, up = true))
        // Timeline is the last block on screen: the folded blocks after it don't count.
        assertEquals(default, DailySection.moved(default, shown, TIMELINE, up = false))
        assertEquals(default, DailySection.moved(default, shown, SCRATCHPAD, up = false))
        // Projects (12.8.0) is the first folded block by default.
        assertEquals(default, DailySection.moved(default, shown, PROJECTS, up = true))
    }
}
