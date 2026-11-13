package cloud.wafflecommons.pixelbrainreader.data.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** The modes with the custody calendar on: Off with the child home, Perso without, Work in work hours. */
class ModeResolverCustodyTest {

    private val zone = ZoneOffset.UTC
    private val config = ModesConfig(
        scheduleEnabled = true,
        workDays = listOf(1, 2, 3, 4, 5),
        workStart = "08:00",
        workEnd = "18:00",
        outsideHoursMode = ContextMode.PERSONAL,
        // The child arrives on Friday 2026-11-06 and leaves on Friday 2026-11-13.
        custody = CustodySchedule(enabled = true, name = "Kid", arrival = "2026-11-06")
    )
    private fun at(month: Int, day: Int, hour: Int) = LocalDateTime.of(2026, month, day, hour, 0)
    private fun mode(config: ModesConfig, moment: LocalDateTime) = ModeResolver.resolve(config, null, moment, zone).mode

    @Test
    fun `work hours stay Work whoever is home`() {
        assertEquals(ContextMode.WORK, mode(config, at(11, 9, 10)))
        assertEquals(ContextMode.WORK, mode(config, at(11, 16, 10)))
    }

    @Test
    fun `outside work hours the mode follows who is home`() {
        assertEquals(ContextMode.OFF, mode(config, at(11, 9, 19)))
        assertTrue(ModeResolver.resolve(config, null, at(11, 9, 19), zone).withChild!!)
        assertEquals(ContextMode.PERSONAL, mode(config, at(11, 16, 19)))
        assertEquals(ContextMode.OFF, mode(config, at(11, 13, 7)))
        assertEquals(ContextMode.PERSONAL, mode(config, at(11, 13, 19)))
        assertEquals(ContextMode.PERSONAL, mode(config, at(11, 14, 10)))
        assertEquals(ContextMode.OFF, mode(config, at(11, 20, 19)))
        assertEquals(ContextMode.OFF, mode(config, at(11, 21, 10)))
    }

    @Test
    fun `the modes for each side are the owner's`() {
        val swapped = config.copy(withChildMode = ContextMode.PERSONAL, outsideHoursMode = ContextMode.OFF)
        assertEquals(ContextMode.PERSONAL, mode(swapped, at(11, 9, 19)))
        assertEquals(ContextMode.OFF, mode(swapped, at(11, 16, 19)))
    }

    @Test
    fun `a leave only takes Work away`() {
        val leave = config.copy(leavePeriods = listOf(LeavePeriod("2026-12-21", "2027-01-03", "Christmas")))
        val withChild = ModeResolver.resolve(leave, null, at(12, 22, 10), zone)
        assertEquals(ContextMode.OFF, withChild.mode)
        assertEquals(ModeSource.LEAVE, withChild.source)
        assertEquals(ContextMode.PERSONAL, mode(leave, at(12, 28, 10)))
        // Without the calendar a leave is Off, as before.
        assertEquals(ContextMode.OFF, mode(leave.copy(custody = CustodySchedule()), at(12, 28, 10)))
    }

    @Test
    fun `the calendar off changes nothing`() {
        val off = config.copy(custody = config.custody.copy(enabled = false))
        assertEquals(ContextMode.PERSONAL, mode(off, at(11, 9, 19)))
        assertNull(ModeResolver.resolve(off, null, at(11, 9, 19), zone).withChild)
        assertEquals(ContextMode.WORK, mode(config.copy(scheduleEnabled = false), at(11, 9, 19)))
    }

    @Test
    fun `the handover is a schedule change even on a day off`() {
        val fourDays = config.copy(workDays = listOf(1, 2, 3, 4))
        assertEquals(at(11, 13, 18), ModeResolver.nextBoundary(fourDays, at(11, 13, 10)))
        assertEquals(at(11, 13, 18), ModeResolver.overrideUntil(fourDays, at(11, 13, 10)))
        val noCustody = fourDays.copy(custody = CustodySchedule())
        assertEquals(LocalDate.of(2026, 11, 16).atTime(8, 0), ModeResolver.nextBoundary(noCustody, at(11, 13, 10)))
    }

    @Test
    fun `the evening the shutdown offers is the custody's`() {
        assertEquals(ContextMode.PERSONAL, ModeResolver.outsideHoursModeAt(config, at(11, 13, 18)))
        assertEquals(ContextMode.OFF, ModeResolver.outsideHoursModeAt(config, at(11, 20, 18)))
        assertEquals(ContextMode.PERSONAL, ModeResolver.outsideHoursModeAt(config.copy(custody = CustodySchedule()), at(11, 20, 18)))
    }

    @Test
    fun `Off shows no project, Perso the ticked ones or all, Work every one`() {
        val job = "20_Projects/28_Job"
        val mine = "20_Projects/21_Mine"
        val other = "20_Projects/23_Other"
        val folders = listOf(job)
        assertTrue(Visibility(mode = ContextMode.WORK, workFolders = folders).showsProject(job))
        assertTrue(Visibility(mode = ContextMode.WORK, workFolders = folders, personalProjects = listOf(mine)).showsProject(other))
        assertTrue(Visibility(mode = ContextMode.PERSONAL, workFolders = folders).showsProject(other))
        assertFalse(Visibility(mode = ContextMode.PERSONAL, workFolders = folders).showsProject(job))
        assertTrue(Visibility(mode = ContextMode.PERSONAL, workFolders = folders, personalProjects = listOf(mine)).showsProject(mine))
        assertFalse(Visibility(mode = ContextMode.PERSONAL, workFolders = folders, personalProjects = listOf(mine)).showsProject(other))
        listOf(job, mine, other).forEach { assertFalse(Visibility(mode = ContextMode.OFF, workFolders = folders).showsProject(it)) }
    }
}
