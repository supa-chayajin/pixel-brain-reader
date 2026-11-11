package cloud.wafflecommons.pixelbrainreader.data.review

import cloud.wafflecommons.pixelbrainreader.data.work.CoachSeeds
import cloud.wafflecommons.pixelbrainreader.data.work.LogEntry
import cloud.wafflecommons.pixelbrainreader.data.work.LogKind
import cloud.wafflecommons.pixelbrainreader.data.work.OverloadSignal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class WeeklyCoachTest {

    private val week = ReviewWeek.of(LocalDate.of(2026, 11, 11))

    @Test
    fun `the brief carries the week's measured facts, then its question`() {
        val facts = WeeklyFacts(
            habits = listOf(HabitWeek("Course", 2, 3)),
            mood = MoodWeek(3.84, 6, 3.4),
            health = HealthWeek(sleepMinutesAverage = 425, nights = 6, stepsAverage = 8412, stepDays = 7),
            tasksDone = 18, tasksTotal = 24,
            lastWeekPlan = listOf("- [ ] Finir la refonte")
        )
        val brief = WeeklyCoach.brief(week, facts)
        assertTrue(brief.startsWith("Ma semaine 46 — du 9 au 15 novembre 2026, d'après mes données."))
        assertTrue(brief.contains("\n- Humeur moyenne : 3,8/5 sur 6 jours, contre 3,4 la semaine précédente."))
        assertTrue(brief.contains("\n- Sommeil moyen : 7 h 05 sur 6 nuits."))
        assertTrue(brief.contains("\n- Tâches faites : 18 sur 24."))
        assertTrue(brief.contains("\n- Habitude « Course » : 2 jours sur 3 prévus."))
        assertTrue(brief.contains("\n- Prévu la semaine dernière : Finir la refonte"))
        assertTrue(brief.endsWith("\n\n" + WeeklyCoach.QUESTION))
    }

    @Test
    fun `work reaches the coach as counts, never as the log's text`() {
        val entries = listOf(
            LogEntry(LocalDateTime.of(2026, 11, 10, 9, 0), LogKind.DECISION, "Choix confidentiel"),
            LogEntry(LocalDateTime.of(2026, 11, 10, 10, 0), LogKind.FACT, "Un fait précis")
        )
        val work = WeeklyActivity.work(entries, 1, 0, 3, OverloadSignal(35, 1860, 1380, listOf("sommeil")), listOf("[[Journal/2026-11]]"))
        val brief = WeeklyCoach.brief(week, WeeklyFacts(work = work))
        assertTrue(brief.contains("\n- Journal de mission : 2 entrées, dont 1 décision (1 sans ADR)."))
        assertTrue(brief.contains("\n- Charge : +35 % par rapport à d'habitude."))
        assertFalse(brief.contains("Choix confidentiel"))
        assertFalse(brief.contains("Un fait précis"))
        assertFalse(brief.contains("[[Journal"))
    }

    @Test
    fun `an empty week still asks, and says nothing was measured`() {
        val brief = WeeklyCoach.brief(week, WeeklyFacts())
        assertTrue(brief.contains("\n- Rien n'a été mesuré cette semaine."))
        assertTrue(brief.endsWith(WeeklyCoach.QUESTION))
    }

    @Test
    fun `a crowded week is cut to the budget and keeps its question`() {
        val habits = (1..60).map { HabitWeek("Habitude numéro $it avec un nom assez long", it % 7, 7) }
        val brief = WeeklyCoach.brief(week, WeeklyFacts(habits = habits))
        assertTrue(brief.length <= CoachSeeds.MAX_CHARS)
        assertTrue(brief.endsWith("\n\n" + WeeklyCoach.QUESTION))
    }
}
