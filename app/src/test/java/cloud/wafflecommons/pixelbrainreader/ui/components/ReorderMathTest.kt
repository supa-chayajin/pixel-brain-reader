package cloud.wafflecommons.pixelbrainreader.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The drag's arithmetic (13.4, S5): one row moved at a time, one step each time it passes a neighbour. */
class ReorderMathTest {

    private val rows = listOf("a", "b", "c", "d")

    @Test
    fun `moved moves an item down and up`() {
        assertEquals(listOf("b", "c", "a", "d"), ReorderMath.moved(rows, 0, 2))
        assertEquals(listOf("a", "d", "b", "c"), ReorderMath.moved(rows, 3, 1))
        assertEquals(listOf("a", "c", "b", "d"), ReorderMath.moved(rows, 1, 2))
        // The list given is left as it was.
        assertEquals(listOf("a", "b", "c", "d"), rows)
    }

    @Test
    fun `an out-of-range move changes nothing`() {
        assertSame(rows, ReorderMath.moved(rows, -1, 2))
        assertSame(rows, ReorderMath.moved(rows, 1, 4))
        assertSame(rows, ReorderMath.moved(rows, 2, 2))
        val none = emptyList<String>()
        assertSame(none, ReorderMath.moved(none, 0, 1))
    }

    @Test
    fun `target steps once the dragged middle passes a neighbour's middle`() {
        // Rows 100 px tall, their middles at 50, 150, 250, 350.
        val middles = listOf(50f, 150f, 250f, 350f)

        // Dragged down from row 1: not yet past row 2's middle, then past it.
        assertEquals(1, ReorderMath.target(1, 250f, middles))
        assertEquals(2, ReorderMath.target(1, 251f, middles))
        // Dragged up from row 1: past row 0's middle.
        assertEquals(1, ReorderMath.target(1, 50f, middles))
        assertEquals(0, ReorderMath.target(1, 49f, middles))
        // One step only, however far the finger went.
        assertEquals(2, ReorderMath.target(1, 900f, middles))
        assertEquals(0, ReorderMath.target(1, -900f, middles))
        // Nothing before the first row nor after the last.
        assertEquals(0, ReorderMath.target(0, -10f, middles))
        assertEquals(3, ReorderMath.target(3, 900f, middles))
    }

    @Test
    fun `a neighbour not on screen is never passed`() {
        // Row 0 scrolled out of a lazy list: its place is unknown.
        val middles = listOf(Float.NaN, 150f, 250f)
        assertEquals(1, ReorderMath.target(1, -500f, middles))
        assertEquals(2, ReorderMath.target(1, 260f, middles))
        // A row the list doesn't know: no step.
        assertEquals(5, ReorderMath.target(5, 0f, middles))
    }
}
