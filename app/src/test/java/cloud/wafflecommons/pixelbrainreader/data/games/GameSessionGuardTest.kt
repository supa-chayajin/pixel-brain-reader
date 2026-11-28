package cloud.wafflecommons.pixelbrainreader.data.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** 13.1: "Still playing?", sessions typed or fixed by hand, and the hours a card shows. */
class GameSessionGuardTest {

    private val zone: ZoneId = ZoneId.of("Europe/Paris")
    private val hour = 3_600_000L
    private val start = ZonedDateTime.of(2026, 11, 15, 21, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val session = RunningGame(start, "30_Areas/Games/Ring/Ring.md")

    @Test
    fun `the check round-trips on the phone and junk reads as none`() {
        val asked = SessionCheck(start, start + 3 * hour, start + 3 * hour + 120_000)
        assertEquals(asked, SessionCheck.decode(asked.encode()))
        val first = SessionCheck.first(session)
        assertEquals(first, SessionCheck.decode(first.encode()))
        assertNull(first.askedAt)
        assertNull(SessionCheck.decode(null))
        assertNull(SessionCheck.decode("garbage"))
        assertNull(SessionCheck.decode("1|2"))
    }

    @Test
    fun `nothing happens for 3 hours, then the question, then an hour to answer`() {
        val first = SessionCheck.first(session)
        assertEquals(GameSessionGuard.Step.Wait(start + 3 * hour), GameSessionGuard.step(first, start + hour))
        assertEquals(GameSessionGuard.Step.Ask(start + 4 * hour), GameSessionGuard.step(first, start + 3 * hour))
        val asked = first.copy(askedAt = start + 3 * hour)
        assertEquals(GameSessionGuard.Step.Wait(start + 4 * hour), GameSessionGuard.step(asked, start + 3 * hour + 30 * 60_000))
    }

    @Test
    fun `no answer ends the session when the question was due, even when the phone asked late`() {
        val first = SessionCheck.first(session)
        assertEquals(GameSessionGuard.Step.End(start + 3 * hour), GameSessionGuard.step(first.copy(askedAt = start + 3 * hour), start + 4 * hour))
        // Doze held the question back to 5 h: the session still ends at the 3-hour mark.
        val late = first.copy(askedAt = start + 5 * hour)
        assertEquals(GameSessionGuard.Step.Wait(start + 6 * hour), GameSessionGuard.step(late, start + 5 * hour))
        assertEquals(GameSessionGuard.Step.End(start + 3 * hour), GameSessionGuard.step(late, start + 6 * hour + 1))
    }

    @Test
    fun `keep playing asks again an hour later`() {
        val asked = SessionCheck.first(session).copy(askedAt = start + 3 * hour)
        val kept = GameSessionGuard.keep(asked, start + 3 * hour + 10 * 60_000)
        assertNull(kept.askedAt)
        assertEquals(start + 4 * hour + 10 * 60_000, kept.nextAskAt)
        assertEquals(GameSessionGuard.Step.Wait(kept.nextAskAt), GameSessionGuard.step(kept, start + 4 * hour))
        // Unanswered after Keep playing: the end is the second question's time.
        val askedAgain = kept.copy(askedAt = kept.nextAskAt)
        assertEquals(GameSessionGuard.Step.End(kept.nextAskAt), GameSessionGuard.step(askedAgain, kept.nextAskAt + hour))
    }

    @Test
    fun `a session typed by hand is keyed like a started one, so typing it twice replaces it`() {
        val record = ManualSessions.record(session.page, LocalDate.of(2026, 11, 15), LocalTime.of(21, 0), 95, zone)!!
        // 13.4: the page in the key, so another game's session of the same minute never shares it.
        assertEquals("manual:$start:${ManualSessions.pageTag(session.page)}", record.id)
        assertEquals("2026-11-15", record.date)
        assertEquals("21:00", record.start)
        assertEquals(95, record.minutes)
        assertEquals(GameSessionRecord.SOURCE_MANUAL, record.source)
        assertEquals(session.page, record.page)
        assertEquals(session.record(start + 95 * 60_000L, zone), record)
        assertNull(ManualSessions.record(session.page, LocalDate.of(2026, 11, 15), LocalTime.of(21, 0), 0, zone))
        assertNull(ManualSessions.record(session.page, LocalDate.of(2026, 11, 15), LocalTime.of(21, 0), 24 * 60 + 1, zone))
    }

    @Test
    fun `a fixed session keeps its day and page and takes the new start's key`() {
        val old = ManualSessions.record(session.page, LocalDate.of(2026, 11, 15), LocalTime.of(21, 0), 600, zone)!!
        val fixed = ManualSessions.edited(old, LocalTime.of(21, 30), 150, zone)!!
        assertEquals("2026-11-15", fixed.date)
        assertEquals("21:30", fixed.start)
        assertEquals(150, fixed.minutes)
        assertEquals(session.page, fixed.page)
        assertEquals(ManualSessions.idFor(session.page, start + 30 * 60_000L), fixed.id)
        assertNull(ManualSessions.edited(old.copy(page = null), LocalTime.of(21, 30), 150, zone))
    }

    @Test
    fun `the page tag is the path's sha-256, 8 hex`() {
        val tag = ManualSessions.pageTag(session.page)
        val digest = MessageDigest.getInstance("SHA-256").digest(session.page.toByteArray(Charsets.UTF_8))
        assertEquals(digest.joinToString("") { "%02x".format(it) }.take(8), tag)
        assertEquals(8, tag.length)
        assertTrue(tag.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(tag, ManualSessions.pageTag("30_Areas/Games/Glass_Tower/Glass_Tower.md"))
        assertEquals("manual:$start:$tag", ManualSessions.idFor(session.page, start))
        // A session started on the phone keeps its milliseconds, under the same key.
        assertEquals("manual:${start + 123}:$tag", RunningGame(start + 123, session.page).record(start + 2 * hour, zone)!!.id)
    }

    @Test
    fun `hours are PSN's once it measures the game, else the console's figure plus the sessions`() {
        assertEquals(10.0, GameHours.of(36_000, playedBefore = 228.0, manualMinutes = 90)!!, 0.0)
        assertEquals(229.5, GameHours.of(0, playedBefore = 228.0, manualMinutes = 90)!!, 0.0)
        assertEquals(228.0, GameHours.of(0, playedBefore = 228.0, manualMinutes = 0)!!, 0.0)
        assertEquals(1.5, GameHours.of(0, playedBefore = null, manualMinutes = 90)!!, 0.0)
        assertNull(GameHours.of(0, playedBefore = null, manualMinutes = 0))
    }
}
