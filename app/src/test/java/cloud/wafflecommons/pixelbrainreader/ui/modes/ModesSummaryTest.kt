package cloud.wafflecommons.pixelbrainreader.ui.modes

import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.CustodySchedule
import cloud.wafflecommons.pixelbrainreader.data.modes.KeyDate
import cloud.wafflecommons.pixelbrainreader.data.modes.LeavePeriod
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeAppearance
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeStyle
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** The Modes hub's rows (13.4, S2): each summary in the owner's own names, invented here. */
class ModesSummaryTest {

    private val renamed = ModeAppearance(
        styles = mapOf(ContextMode.WORK to ModeStyle("Office"), ContextMode.PERSONAL to ModeStyle("Garden"), ContextMode.OFF to ModeStyle("Rest")),
        toolkit = "Bench"
    )

    @Test
    fun `the names are the modes and the toolkit as the owner calls them`() {
        assertEquals("Work · Personal · Off · Toolkit", ModesSummary.names(ModeAppearance()))
        assertEquals("Office · Garden · Rest · Bench", ModesSummary.names(renamed))
    }

    @Test
    fun `work counts its folders`() {
        assertEquals("No folder yet", ModesSummary.work(ModesConfig()))
        assertEquals("1 folder", ModesSummary.work(ModesConfig(workFolders = listOf("30_Work"))))
        assertEquals("3 folders", ModesSummary.work(ModesConfig(workFolders = listOf("30_Work", "31_Client", "32_Ops"))))
    }

    @Test
    fun `the Personal mode's projects are every one, or the ticked ones counted`() {
        assertEquals("Every Personal project", ModesSummary.projects(ModesConfig(), ModeAppearance()))
        assertEquals("Every Garden project", ModesSummary.projects(ModesConfig(), renamed))
        assertEquals("1 project", ModesSummary.projects(ModesConfig(personalProjects = listOf("20_Projects/21_A")), renamed))
        assertEquals(
            "2 projects",
            ModesSummary.projects(ModesConfig(personalProjects = listOf("20_Projects/21_A", "20_Projects/22_B")), renamed)
        )
    }

    @Test
    fun `the schedule says its days, its hours and the mode the rest of the time`() {
        assertEquals("Off", ModesSummary.schedule(ModesConfig(), ModeAppearance()))
        assertEquals("Mon–Fri, 09:00–18:00, then Off", ModesSummary.schedule(ModesConfig(scheduleEnabled = true), ModeAppearance()))
        assertEquals(
            "Mon, Wed, Fri, 08:30–16:30, then Garden",
            ModesSummary.schedule(
                ModesConfig(scheduleEnabled = true, workDays = listOf(5, 1, 3), workStart = "08:30", workEnd = "16:30", outsideHoursMode = ContextMode.PERSONAL),
                renamed
            )
        )
    }

    @Test
    fun `with the custody calendar on, both off-hours modes are named`() {
        val config = ModesConfig(
            scheduleEnabled = true,
            outsideHoursMode = ContextMode.PERSONAL,
            withChildMode = ContextMode.OFF,
            custody = CustodySchedule(enabled = true, name = "Kiddo", arrival = "2026-11-06")
        )
        assertEquals("Mon–Fri, 09:00–18:00, then Rest with Kiddo, Garden without", ModesSummary.schedule(config, renamed))
        // The same mode either way: named once.
        assertEquals(
            "Mon–Fri, 09:00–18:00, then Rest",
            ModesSummary.schedule(config.copy(outsideHoursMode = ContextMode.OFF), renamed)
        )
    }

    @Test
    fun `days read as runs`() {
        assertEquals("Mon–Fri", ModesSummary.days(listOf(1, 2, 3, 4, 5)))
        assertEquals("Sat, Sun", ModesSummary.days(listOf(7, 6)))
        assertEquals("Mon–Wed, Fri", ModesSummary.days(listOf(1, 2, 3, 5)))
        assertEquals("No work day", ModesSummary.days(emptyList()))
    }

    @Test
    fun `custody says its rhythm and this week`() {
        assertEquals("Off", ModesSummary.custody(ModesConfig(), LocalDate.of(2026, 11, 10)))
        // On, without a week set yet: the handover day it keeps.
        assertEquals(
            "Every other Friday · say how this week goes",
            ModesSummary.custody(ModesConfig(custody = CustodySchedule(enabled = true)), LocalDate.of(2026, 11, 10))
        )
        // 6 November 2026 is a Friday the child arrives: home the week after it, away the next.
        val active = ModesConfig(custody = CustodySchedule(enabled = true, name = "Kiddo", arrival = "2026-11-06"))
        assertEquals("Every other Friday · with Kiddo this week", ModesSummary.custody(active, LocalDate.of(2026, 11, 10)))
        assertEquals("Every other Friday · without Kiddo this week", ModesSummary.custody(active, LocalDate.of(2026, 11, 17)))
    }

    @Test
    fun `dates count the leaves and the key dates`() {
        assertEquals("Nothing planned", ModesSummary.dates(ModesConfig()))
        val leaves = listOf(LeavePeriod("2026-12-21", "2026-12-31"), LeavePeriod("2027-02-15", "2027-02-19"))
        val keyDates = listOf(KeyDate("2026-12-01", "Launch"), KeyDate("2026-12-10", "Review"), KeyDate("2027-01-05", "Trip", work = false))
        assertEquals("2 leaves · 3 key dates", ModesSummary.dates(ModesConfig(leavePeriods = leaves, keyDates = keyDates)))
        assertEquals("1 leave", ModesSummary.dates(ModesConfig(leavePeriods = leaves.take(1))))
        assertEquals("1 key date", ModesSummary.dates(ModesConfig(keyDates = keyDates.take(1))))
    }
}
