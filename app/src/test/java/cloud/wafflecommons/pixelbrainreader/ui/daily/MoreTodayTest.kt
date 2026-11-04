package cloud.wafflecommons.pixelbrainreader.ui.daily

import cloud.wafflecommons.pixelbrainreader.data.model.DailySection
import org.junit.Assert.assertEquals
import org.junit.Test

class MoreTodayTest {

    private fun summary(fold: Set<DailySection>) = moreTodaySummary(
        fold = fold, moments = 3, tasks = 1, gratitude = 2, echoes = 1,
        scraps = 0, hasIdeas = true, countdowns = 0
    )

    @Test
    fun `counts only what is folded and not empty`() {
        val fold = DailySection.entries.toSet() - DailySection.DEFAULT_SHOWN
        assertEquals("2 gratitude · ideas · 1 echo", summary(fold))
    }

    @Test
    fun `plurals and folded timeline and tasks`() {
        assertEquals("1 task · 3 moments", summary(setOf(DailySection.TASKS, DailySection.TIMELINE)))
    }

    @Test
    fun `an empty fold still says what it holds`() {
        assertEquals("Gratitude, ideas, mantra and more", summary(setOf(DailySection.MANTRA, DailySection.SCRATCHPAD)))
    }

    @Test
    fun `stored keys round-trip, unknown keys are dropped, nothing stored means defaults`() {
        assertEquals(DailySection.DEFAULT_SHOWN, DailySection.fromKeys(null))
        assertEquals(emptySet<DailySection>(), DailySection.fromKeys(emptySet()))
        assertEquals(setOf(DailySection.MANTRA), DailySection.fromKeys(setOf("mantra", "gone")))
        val all = DailySection.entries.toSet()
        assertEquals(all, DailySection.fromKeys(all.map { it.key }.toSet()))
    }
}
