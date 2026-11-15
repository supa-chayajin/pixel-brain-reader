package cloud.wafflecommons.pixelbrainreader.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HealthInsightsTest {

    private val start = LocalDate.of(2026, 8, 1)

    @Test
    fun `ranks share the average rank on ties`() {
        assertEquals(listOf(1.0, 2.5, 2.5, 4.0), HealthInsights.ranks(listOf(1.0, 5.0, 5.0, 9.0)))
    }

    @Test
    fun `spearman is 1 for any rising relation, -1 for a falling one, null without variation`() {
        val x = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(1.0, HealthInsights.spearman(x, x.map { it * it })!!, 1e-9)
        assertEquals(-1.0, HealthInsights.spearman(x, x.map { -it })!!, 1e-9)
        assertNull(HealthInsights.spearman(x, listOf(3.0, 3.0, 3.0, 3.0, 3.0)))
    }

    @Test
    fun `sleep pairs with the same day's mood, steps with the next night's sleep`() {
        // Sleep rises with the day; mood rises with the sleep; steps fall as the next night rises.
        val days = (0 until 20).map { i ->
            HealthInsights.Day(
                date = start.plusDays(i.toLong()),
                sleepMinutes = 400.0 + i,
                mood = 2.0 + i * 0.1,
                steps = 10_000.0 - i * 100,
                hrvMs = 50.0 + i
            )
        }
        val insights = HealthInsights.of(days).associateBy { it.link }
        assertEquals(1.0, insights.getValue(HealthInsights.Link.SLEEP_MOOD).rho!!, 1e-9)
        assertEquals(20, insights.getValue(HealthInsights.Link.SLEEP_MOOD).n)
        // Steps of day d against the night ending on d + 1: 19 pairs.
        assertEquals(19, insights.getValue(HealthInsights.Link.STEPS_SLEEP).n)
        assertEquals(-1.0, insights.getValue(HealthInsights.Link.STEPS_SLEEP).rho!!, 1e-9)
        assertEquals(19, insights.getValue(HealthInsights.Link.MOOD_HRV).n)
    }

    @Test
    fun `under fourteen paired days there is no rho, only N`() {
        val days = (0 until 10).map { i ->
            HealthInsights.Day(date = start.plusDays(i.toLong()), sleepMinutes = 400.0 + i, mood = 3.0 + (i % 3))
        }
        val sleepMood = HealthInsights.of(days).first { it.link == HealthInsights.Link.SLEEP_MOOD }
        assertEquals(10, sleepMood.n)
        assertNull(sleepMood.rho)
        assertTrue(!sleepMood.enoughDays)
    }

    @Test
    fun `a day missing one side is left out, never guessed`() {
        val days = (0 until 16).map { i ->
            HealthInsights.Day(date = start.plusDays(i.toLong()), sleepMinutes = if (i % 4 == 0) null else 400.0 + i, mood = 3.0 + i)
        }
        assertEquals(12, HealthInsights.of(days).first { it.link == HealthInsights.Link.SLEEP_MOOD }.n)
    }

    @Test
    fun `rho reads in plain words`() {
        assertEquals("No clear link", HealthInsights.words(0.05))
        assertEquals("Moderate · they rise together", HealthInsights.words(0.42))
        assertEquals("Strong · they move opposite", HealthInsights.words(-0.7))
    }
}
