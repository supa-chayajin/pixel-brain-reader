package cloud.wafflecommons.pixelbrainreader.data.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class ModeResolverTest {

    private val zone = ZoneOffset.UTC
    private val scheduled = ModesConfig(scheduleEnabled = true, workStart = "09:00", workEnd = "18:00", outsideHoursMode = ContextMode.OFF)
    // 2026-10-19 is a Monday.
    private val mondayNoon = LocalDateTime.of(2026, 10, 19, 12, 0)
    private fun millis(t: LocalDateTime) = t.toInstant(zone).toEpochMilli()

    @Test
    fun `without a schedule everything is work`() {
        val r = ModeResolver.resolve(ModesConfig(), null, mondayNoon, zone)
        assertEquals(ContextMode.WORK, r.mode)
        assertEquals(ModeSource.DEFAULT, r.source)
    }

    @Test
    fun `the schedule decides inside and outside work hours`() {
        assertEquals(ContextMode.WORK, ModeResolver.resolve(scheduled, null, mondayNoon, zone).mode)
        assertEquals(ContextMode.OFF, ModeResolver.resolve(scheduled, null, mondayNoon.withHour(19), zone).mode)
        assertEquals(ContextMode.OFF, ModeResolver.resolve(scheduled, null, mondayNoon.withHour(8).withMinute(59), zone).mode)
        // Saturday
        assertEquals(ContextMode.OFF, ModeResolver.resolve(scheduled, null, mondayNoon.plusDays(5), zone).mode)
        // End time is exclusive.
        assertEquals(ContextMode.OFF, ModeResolver.resolve(scheduled, null, mondayNoon.withHour(18), zone).mode)
    }

    @Test
    fun `a leave turns the day off`() {
        val config = scheduled.copy(leavePeriods = listOf(LeavePeriod("2026-10-26", "2026-10-30", "Holidays")))
        val r = ModeResolver.resolve(config, null, LocalDateTime.of(2026, 10, 27, 10, 0), zone)
        assertEquals(ContextMode.OFF, r.mode)
        assertEquals(ModeSource.LEAVE, r.source)
        assertEquals("Holidays", r.leave?.label)
        assertEquals(ContextMode.WORK, ModeResolver.resolve(config, null, LocalDateTime.of(2026, 11, 2, 10, 0), zone).mode)
    }

    @Test
    fun `a manual choice wins until it expires`() {
        val override = ModeOverride(ContextMode.PERSONAL, millis(mondayNoon.plusHours(1)))
        assertEquals(ContextMode.PERSONAL, ModeResolver.resolve(scheduled, override, mondayNoon, zone).mode)
        assertEquals(ModeSource.MANUAL, ModeResolver.resolve(scheduled, override, mondayNoon, zone).source)
        assertEquals(ContextMode.WORK, ModeResolver.resolve(scheduled, override, mondayNoon.plusHours(2), zone).mode)
    }

    @Test
    fun `a manual choice ends at the next schedule change or midnight`() {
        assertEquals(mondayNoon.withHour(18), ModeResolver.overrideUntil(scheduled, mondayNoon))
        assertEquals(LocalDate.of(2026, 10, 20).atStartOfDay(), ModeResolver.overrideUntil(scheduled, mondayNoon.withHour(20)))
        assertEquals(LocalDate.of(2026, 10, 20).atStartOfDay(), ModeResolver.overrideUntil(ModesConfig(), mondayNoon))
    }

    @Test
    fun `the next boundary skips non-working days`() {
        val friday = LocalDateTime.of(2026, 10, 23, 19, 0)
        assertEquals(LocalDateTime.of(2026, 10, 26, 9, 0), ModeResolver.nextBoundary(scheduled, friday))
        assertNull(ModeResolver.nextBoundary(ModesConfig(), friday))
    }

    @Test
    fun `visibility hides work folders outside work and private while sharing`() {
        val folders = listOf("20_Projects/22_Client", "30_Areas/Mission_Log")
        val work = Visibility(ContextMode.WORK, workFolders = folders)
        assertTrue(work.isVisible("20_Projects/22_Client/Board.md"))
        val personal = Visibility(ContextMode.PERSONAL, workFolders = folders)
        assertFalse(personal.isVisible("20_Projects/22_Client/Board.md"))
        assertFalse(personal.isVisible("20_Projects/22_Client"))
        assertTrue(personal.isVisible("20_Projects/22_ClientOther/x.md"))
        assertTrue(personal.isVisible("99_Private/diary.md.enc"))
        assertTrue(personal.muteWorkNotifications)
        val sharing = Visibility(ContextMode.WORK, screenShareSafe = true, workFolders = folders)
        assertFalse(sharing.isVisible("20_Projects/22_Client/Board.md"))
        assertFalse(sharing.isVisible("99_Private/diary.md.enc"))
        assertTrue(sharing.hideHealth)
        assertFalse(sharing.muteWorkNotifications)
    }

    @Test
    fun `work key dates follow the work context`() {
        val config = ModesConfig(
            keyDates = listOf(
                KeyDate("2026-10-23", "Deadline", work = true),
                KeyDate("2026-11-01", "Race", work = false),
                KeyDate("2026-10-01", "Past", work = false)
            )
        )
        val today = LocalDate.of(2026, 10, 19)
        val atWork = ModeResolver.upcomingKeyDates(config, today, Visibility(ContextMode.WORK))
        assertEquals(listOf("Deadline" to 4L, "Race" to 13L), atWork.map { it.first.label to it.second })
        val off = ModeResolver.upcomingKeyDates(config, today, Visibility(ContextMode.OFF))
        assertEquals(listOf("Race"), off.map { it.first.label })
    }
}
