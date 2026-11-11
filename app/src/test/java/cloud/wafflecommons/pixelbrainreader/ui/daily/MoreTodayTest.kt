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
    fun `what is due counts in the fold it arrives in`() {
        val withDue = moreTodaySummary(
            fold = setOf(DailySection.DUE, DailySection.MANTRA), moments = 0, tasks = 0, gratitude = 0,
            echoes = 0, scraps = 0, hasIdeas = false, countdowns = 0, due = 3
        )
        assertEquals("3 due", withDue)
    }

    @Test
    fun `the tech watch's headlines count in the fold it arrives in`() {
        val withWatch = moreTodaySummary(
            fold = setOf(DailySection.WATCH), moments = 0, tasks = 0, gratitude = 0,
            echoes = 0, scraps = 0, hasIdeas = false, countdowns = 0, headlines = 7
        )
        assertEquals("7 headlines", withWatch)
        assertEquals(DailySection.WATCH, DailySection.entries.last()) // added by an update: it arrives last, in the fold
    }

    @Test
    fun `an empty fold still says what it holds`() {
        assertEquals("Gratitude, ideas, mantra and more", summary(setOf(DailySection.MANTRA, DailySection.SCRATCHPAD)))
    }

    @Test
    fun `timeline and tasks pair only when they follow each other`() {
        val (weather, vitals, tasks, timeline) = listOf(
            DailySection.WEATHER, DailySection.VITALS, DailySection.TASKS, DailySection.TIMELINE
        )
        assertEquals(2, timelineTasksPairAt(listOf(weather, vitals, tasks, timeline)))
        assertEquals(0, timelineTasksPairAt(listOf(timeline, tasks, weather)))
        assertEquals(null, timelineTasksPairAt(listOf(tasks, DailySection.GRATITUDE, timeline)))
        assertEquals(null, timelineTasksPairAt(listOf(weather, timeline)))
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
