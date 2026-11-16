package cloud.wafflecommons.pixelbrainreader.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The open tab's emblem (13.2.1): a shape drawn at each opening, never the one just shown, in the
 * tone of the tab's place.
 */
class TabEmblemsTest {

    private val count = 14

    @Test
    fun `the places go through primary, secondary and tertiary in turn`() {
        assertEquals(listOf(0, 1, 2, 0, 1, 2, 0, 1), (0..7).map { TabEmblems.toneIndex(it) })
    }

    @Test
    fun `a draw never repeats the shape just shown`() {
        val random = Random(42)
        repeat(count) { last ->
            repeat(200) {
                val next = TabEmblems.nextShape(last, count, random)
                assertNotEquals(last, next)
                assertTrue(next in 0 until count)
            }
        }
    }

    @Test
    fun `every other shape can come up`() {
        val random = Random(7)
        val seen = (1..2_000).map { TabEmblems.nextShape(last = 3, count = count, random = random) }.toSet()
        assertEquals((0 until count).toSet() - 3, seen)
    }

    @Test
    fun `the first draw can be any shape`() {
        val random = Random(1)
        val seen = (1..2_000).map { TabEmblems.nextShape(last = null, count = count, random = random) }.toSet()
        assertEquals((0 until count).toSet(), seen)
    }

    @Test
    fun `a last shape out of range is ignored and one shape is always that one`() {
        assertTrue(TabEmblems.nextShape(last = 99, count = count, random = Random(3)) in 0 until count)
        assertEquals(0, TabEmblems.nextShape(last = 0, count = 1, random = Random(3)))
    }

    @Test
    fun `back on the same tab, the shape changes`() {
        val draws = TabShapeDraws(Random(11))
        var previous = draws.next(count)
        repeat(500) {
            val next = draws.next(count)
            assertNotEquals(previous, next)
            assertEquals(next, draws.last)
            previous = next
        }
    }
}
