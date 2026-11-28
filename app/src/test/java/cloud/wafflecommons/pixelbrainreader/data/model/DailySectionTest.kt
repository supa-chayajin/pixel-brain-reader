package cloud.wafflecommons.pixelbrainreader.data.model

import cloud.wafflecommons.pixelbrainreader.data.model.DailySection.COUNTDOWNS
import cloud.wafflecommons.pixelbrainreader.data.model.DailySection.DUE
import cloud.wafflecommons.pixelbrainreader.data.model.DailySection.WATCH
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
        assertEquals(listOf(PROJECTS, GRATITUDE, COUNTDOWNS, ECHOES, SECOND_BRAIN, SCRATCHPAD, DUE, WATCH), moved.filter { it !in shown })
    }

    @Test
    fun `folded blocks move among themselves`() {
        val shown = DailySection.DEFAULT_SHOWN
        val moved = DailySection.moved(default, shown, COUNTDOWNS, up = true)
        assertEquals(listOf(PROJECTS, COUNTDOWNS, GRATITUDE, MANTRA, ECHOES, SECOND_BRAIN, SCRATCHPAD, DUE, WATCH), moved.filter { it !in shown })
        assertEquals(listOf(WEATHER, VITALS, TASKS, TIMELINE), moved.filter { it in shown })
    }

    @Test
    fun `nothing moves past the edge of a group`() {
        val shown = DailySection.DEFAULT_SHOWN
        assertEquals(default, DailySection.moved(default, shown, WEATHER, up = true))
        // Timeline is the last block on screen: the folded blocks after it don't count.
        assertEquals(default, DailySection.moved(default, shown, TIMELINE, up = false))
        // The tech watch (12.12.0) is the last block of all by default.
        assertEquals(default, DailySection.moved(default, shown, WATCH, up = false))
        // Projects (12.8.0) is the first folded block by default.
        assertEquals(default, DailySection.moved(default, shown, PROJECTS, up = true))
    }

    @Test
    fun `Due today joins a saved order at its end and starts in the fold`() {
        val saved = DailySection.toKeys(DailySection.entries - DUE)
        assertEquals(DUE, DailySection.orderFromKeys(saved).last())
        assertEquals(false, DUE in DailySection.fromKeys(setOf("weather", "tasks")))
        assertEquals(false, DUE in DailySection.DEFAULT_SHOWN)
        // The key is persisted: never renamed.
        assertEquals("due", DUE.key)
    }

    @Test
    fun `the tech watch joins a saved order at its end and starts in the fold`() {
        val saved = DailySection.toKeys(DailySection.entries - WATCH)
        assertEquals(WATCH, DailySection.orderFromKeys(saved).last())
        assertEquals(false, WATCH in DailySection.fromKeys(setOf("weather", "tasks")))
        assertEquals(false, WATCH in DailySection.DEFAULT_SHOWN)
        // The key is persisted: never renamed.
        assertEquals("watch", WATCH.key)
    }

    @Test
    fun `the watch block is called Feeds and keeps its key`() {
        assertEquals("Feeds", WATCH.label)
        assertEquals("watch", WATCH.key)
        assertEquals("The five newest unread headlines of the mode's feeds", WATCH.description)
        // Still read back from a saved order and a saved set under its old key.
        assertEquals(WATCH, DailySection.orderFromKeys("watch,weather").first())
        assertEquals(setOf(WATCH), DailySection.fromKeys(setOf("watch")))
    }

    @Test
    fun `movedTo puts a block at a place in its group, the other group untouched`() {
        val shown = DailySection.DEFAULT_SHOWN
        val moved = DailySection.movedTo(default, shown, TIMELINE, 0)
        assertEquals(listOf(TIMELINE, WEATHER, VITALS, TASKS), moved.filter { it in shown })
        // The folded blocks keep their order and their places in the whole order.
        assertEquals(default.filter { it !in shown }, moved.filter { it !in shown })
        assertEquals(default.indices.filter { default[it] !in shown }, moved.indices.filter { moved[it] !in shown })

        // A folded block moves among the folded ones only.
        val folded = DailySection.movedTo(default, shown, WATCH, 1)
        assertEquals(listOf(PROJECTS, WATCH, GRATITUDE, COUNTDOWNS, MANTRA, ECHOES, SECOND_BRAIN, SCRATCHPAD, DUE), folded.filter { it !in shown })
        assertEquals(listOf(WEATHER, VITALS, TASKS, TIMELINE), folded.filter { it in shown })

        // Groups interleaved in the order: each group's slots are refilled in its new order.
        val mixed = DailySection.movedTo(default, shown + MANTRA, MANTRA, 1)
        assertEquals(
            listOf(WEATHER, MANTRA, VITALS, TASKS, PROJECTS, GRATITUDE, COUNTDOWNS, TIMELINE, ECHOES, SECOND_BRAIN, SCRATCHPAD, DUE, WATCH),
            mixed
        )
        // Where it already is: the same order.
        assertEquals(default, DailySection.movedTo(default, shown, VITALS, 1))
    }

    @Test
    fun `movedTo past the end lands last in the group`() {
        val shown = DailySection.DEFAULT_SHOWN
        val moved = DailySection.movedTo(default, shown, WEATHER, 99)
        assertEquals(listOf(VITALS, TASKS, TIMELINE, WEATHER), moved.filter { it in shown })
        assertEquals(default.filter { it !in shown }, moved.filter { it !in shown })
        // Before the start: first.
        assertEquals(listOf(TIMELINE, WEATHER, VITALS, TASKS), DailySection.movedTo(default, shown, TIMELINE, -3).filter { it in shown })
    }

    @Test
    fun `movedTo of a block missing from the order changes nothing`() {
        val order = default - WATCH
        assertEquals(order, DailySection.movedTo(order, DailySection.DEFAULT_SHOWN, WATCH, 0))
    }
}
