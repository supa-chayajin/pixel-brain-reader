package cloud.wafflecommons.pixelbrainreader.data.games.xbox

import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionRecord
import cloud.wafflecommons.pixelbrainreader.data.games.XboxSnapshot
import cloud.wafflecommons.pixelbrainreader.data.games.XboxTitleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Xbox's times and the sessions read from two syncs' minutes (XB-D). */
class XboxSessionsTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val ring = "1915865634"
    private val bricks = "1234567890"

    private fun title(id: String, minutes: Long?, lastPlayed: String? = null, name: String = "Ring of Ash") =
        XboxTitleState(titleId = id, name = name, achievementSource = 2, minutesPlayed = minutes, lastPlayed = lastPlayed)

    private fun snapshot(syncedAt: String, vararg titles: XboxTitleState) = XboxSnapshot(syncedAt, complete = true, titles = titles.toList())

    @Test
    fun `Xbox times read with 0 to 7 decimals, with or without Z`() {
        assertEquals(Instant.parse("2020-10-08T01:55:12.441193600Z"), XboxTimes.instant("2020-10-08T01:55:12.4411936Z"))
        assertEquals(Instant.parse("2025-10-30T20:30:20.610Z"), XboxTimes.instant("2025-10-30T20:30:20.61Z"))
        assertEquals(Instant.parse("2026-11-27T21:13:45Z"), XboxTimes.instant("2026-11-27T21:13:45Z"))
        // Without a zone the time is UTC.
        assertEquals(Instant.parse("2026-11-27T21:13:45.441Z"), XboxTimes.instant(" 2026-11-27T21:13:45.441 "))
        assertEquals(Instant.parse("2026-11-27T21:13:45Z"), XboxTimes.instant("2026-11-27T21:13:45"))
        assertEquals(Instant.parse("2026-11-27T20:13:45Z"), XboxTimes.instant("2026-11-27T21:13:45+01:00"))
        assertNull(XboxTimes.instant(null))
        assertNull(XboxTimes.instant(""))
        assertNull(XboxTimes.instant("yesterday"))
        assertNull(XboxTimes.instant("2026-11-27"))
    }

    @Test
    fun `placeholders are no unlock time`() {
        assertNull(XboxTimes.realUnlock("0001-01-01T00:00:00.0000000Z"))
        assertNull(XboxTimes.realUnlock("1753-01-01T00:00:00Z"))
        assertNull(XboxTimes.realUnlock("2002-11-15T00:00:00Z"))
        assertNull(XboxTimes.realUnlock(null))
        assertNull(XboxTimes.realUnlock("not a date"))
        assertEquals(Instant.parse("2026-11-02T20:10:00Z"), XboxTimes.realUnlock("2026-11-02T20:10:00.0000000Z"))
        assertEquals(Instant.parse("2005-11-22T00:00:00Z"), XboxTimes.realUnlock("2005-11-22T00:00:00Z"))
    }

    @Test
    fun `the first sync is a baseline`() {
        val current = snapshot("2026-11-27T22:40:00Z", title(ring, 1220, "2026-11-27T21:13:45Z"))
        assertTrue(XboxSessionDiff.sessions(null, current, zone, lastPlayedIsStart = false).isEmpty())
        assertTrue(XboxSessionDiff.sessions(XboxSnapshot(), current, zone, lastPlayedIsStart = false).isEmpty())
        // An unreadable sync time, on either side, is a baseline too.
        assertTrue(XboxSessionDiff.sessions(snapshot("not a time", title(ring, 1100)), current, zone, false).isEmpty())
        val later = current.copy(syncedAt = "soon")
        assertTrue(XboxSessionDiff.sessions(snapshot("2026-11-26T22:40:00Z", title(ring, 1100)), later, zone, false).isEmpty())
    }

    @Test
    fun `minutes added since the last sync are a session ending at the last played time`() {
        val previous = snapshot("2026-11-26T22:40:00Z", title(ring, 1100, "2026-11-25T20:00:00Z"))
        val current = snapshot("2026-11-27T22:40:00Z", title(ring, 1220, "2026-11-27T21:13:45.4411936Z"))

        val session = XboxSessionDiff.sessions(previous, current, zone, lastPlayedIsStart = false).single()

        assertEquals(120, session.minutes)
        assertEquals("2026-11-27", session.date)
        // Last played at 22:13 in Paris (21:13 UTC); two hours before it.
        assertEquals("20:13", session.start)
        assertEquals(GameSessionRecord.SOURCE_XBOX, session.source)
        assertEquals(ring, session.titleId)
        assertNull(session.page)
        assertTrue(session.measured)
    }

    @Test
    fun `when last played is the start, the session starts there`() {
        val previous = snapshot("2026-11-26T22:40:00Z", title(ring, 1100))
        val current = snapshot("2026-11-27T22:40:00Z", title(ring, 1220, "2026-11-27T21:13:45Z"))

        val session = XboxSessionDiff.sessions(previous, current, zone, lastPlayedIsStart = true).single()

        assertEquals("22:13", session.start)
        assertEquals(120, session.minutes)
    }

    @Test
    fun `past midnight it belongs to the day it started`() {
        val previous = snapshot("2026-11-27T05:00:00Z", title(ring, 1000))
        // Last played at 00:30 in Paris on the 28th, after 90 minutes: it started at 23:00 on the 27th.
        val current = snapshot("2026-11-28T05:00:00Z", title(ring, 1090, "2026-11-27T23:30:00Z"))

        val session = XboxSessionDiff.sessions(previous, current, zone, lastPlayedIsStart = false).single()

        assertEquals("2026-11-27", session.date)
        assertEquals("23:00", session.start)
        assertEquals(90, session.minutes)
    }

    @Test
    fun `a newcomer counts whole only when the last list was complete, it was played since, and its minutes fit`() {
        val previous = snapshot("2026-11-26T22:40:00Z", title(ring, 1100))
        val newcomer = title(bricks, 95, "2026-11-27T21:00:00Z", name = "Brick Wars")
        val current = snapshot("2026-11-27T22:40:00Z", title(ring, 1100), newcomer)

        val session = XboxSessionDiff.sessions(previous, current, zone, false).single()
        assertEquals(bricks, session.titleId)
        assertEquals(95, session.minutes)
        assertEquals("20:25", session.start)

        // The last list was full: Brick Wars may only have been cut off it.
        assertTrue(XboxSessionDiff.sessions(previous.copy(complete = false), current, zone, false).isEmpty())
        // Last played before the previous sync: older play.
        val older = current.copy(titles = listOf(title(ring, 1100), newcomer.copy(lastPlayed = "2026-11-20T21:00:00Z")))
        assertTrue(XboxSessionDiff.sessions(previous, older, zone, false).isEmpty())
        // No last played time: nothing says it is new.
        val undated = current.copy(titles = listOf(title(ring, 1100), newcomer.copy(lastPlayed = null)))
        assertTrue(XboxSessionDiff.sessions(previous, undated, zone, false).isEmpty())
        // More minutes than the day between the two syncs: older play is in them.
        val long = current.copy(titles = listOf(title(ring, 1100), newcomer.copy(minutesPlayed = 3_000)))
        assertTrue(XboxSessionDiff.sessions(previous, long, zone, false).isEmpty())
        // The whole day fits.
        val day = current.copy(titles = listOf(title(ring, 1100), newcomer.copy(minutesPlayed = 1_440)))
        assertEquals(1_440, XboxSessionDiff.sessions(previous, day, zone, false).single().minutes)
    }

    @Test
    fun `unknown minutes give no session, and a first known value is a baseline`() {
        val previous = snapshot("2026-11-26T22:40:00Z", title(ring, null))
        // Still unknown: nothing.
        val unknown = snapshot("2026-11-27T22:40:00Z", title(ring, null, "2026-11-27T21:00:00Z"))
        assertTrue(XboxSessionDiff.sessions(previous, unknown, zone, false).isEmpty())
        // Known now: a baseline, never 1,220 minutes in one evening.
        val known = snapshot("2026-11-27T22:40:00Z", title(ring, 1220, "2026-11-27T21:00:00Z"))
        assertTrue(XboxSessionDiff.sessions(previous, known, zone, false).isEmpty())
    }

    @Test
    fun `a lowered value is no session`() {
        val previous = snapshot("2026-11-26T22:40:00Z", title(ring, 500))
        val lowered = snapshot("2026-11-27T22:40:00Z", title(ring, 400, "2026-11-27T21:00:00Z"))
        assertTrue(XboxSessionDiff.sessions(previous, lowered, zone, false).isEmpty())
        // Nor is an unchanged one.
        val same = snapshot("2026-11-27T22:40:00Z", title(ring, 500, "2026-11-27T21:00:00Z"))
        assertTrue(XboxSessionDiff.sessions(previous, same, zone, false).isEmpty())
    }

    @Test
    fun `the id is keyed on the previous sync`() {
        val previous = snapshot("2026-11-26T22:40:00Z", title(ring, 1100))
        val first = snapshot("2026-11-27T22:40:00Z", title(ring, 1220, "2026-11-27T21:00:00Z"))
        // The sync run again later from the same baseline (its snapshot wasn't saved): the same id, so it replaces.
        val replay = snapshot("2026-11-27T23:10:00Z", title(ring, 1230, "2026-11-27T21:10:00Z"))

        val a = XboxSessionDiff.sessions(previous, first, zone, false).single()
        val b = XboxSessionDiff.sessions(previous, replay, zone, false).single()

        assertEquals("xbox:$ring:2026-11-26T22:40:00Z", a.id)
        assertEquals(a.id, b.id)
        assertEquals(130, b.minutes)
    }

    // --- 13.4: a long gap, and a newcomer read one sync late -------------------------------------

    @Test
    fun `a 10-day gap gives one undated session`() {
        val previous = snapshot("2026-11-17T22:40:00Z", title(ring, 1100, "2026-11-17T20:00:00Z"))
        val current = snapshot("2026-11-27T22:40:00Z", title(ring, 1100 + 600, "2026-11-27T21:13:45Z"))
        val session = XboxSessionDiff.sessions(previous, current, zone, lastPlayedIsStart = false).single()
        assertEquals("xbox:$ring:2026-11-17T22:40:00Z", session.id)
        assertEquals(600, session.minutes)
        // The last played day, no start, and since when the play may have run.
        assertEquals("2026-11-27", session.date)
        assertNull(session.start)
        assertEquals("2026-11-17T22:40:00Z", session.since)
        assertEquals(GameSessionRecord.SOURCE_XBOX, session.source)
        // A day apart, the same kind of play is dated.
        val dayLater = snapshot("2026-11-18T22:40:00Z", title(ring, 1100 + 120, "2026-11-18T21:00:00Z"))
        val dated = XboxSessionDiff.sessions(previous, dayLater, zone, false).single()
        assertNull(dated.since)
        assertEquals("20:00", dated.start)
    }

    /** Seen first at 23:40 on the 26th, its minutes unread (a 429 on its batch), the list before it complete. */
    private val seenWithout = snapshot(
        "2026-11-26T22:40:00Z",
        title(ring, 1100),
        title(bricks, null, "2026-11-26T21:00:00Z", name = "Brick Wars").copy(newcomerSince = "2026-11-26T05:00:00Z")
    )

    @Test
    fun `a newcomer read without minutes counts at the next sync when it fits`() {
        val next = snapshot("2026-11-27T05:00:00Z", title(ring, 1100), title(bricks, 180, "2026-11-26T23:30:00Z", name = "Brick Wars"))
        val session = XboxSessionDiff.sessions(seenWithout, next, zone, false).single()
        assertEquals(bricks, session.titleId)
        assertEquals(180, session.minutes)
        // Three hours ending at 00:30 in Paris.
        assertEquals("2026-11-26", session.date)
        assertEquals("21:30", session.start)
        assertEquals("xbox:$bricks:2026-11-26T22:40:00Z", session.id)
        assertNull(session.since)
        // Without the time kept (an older snapshot), its minutes are a baseline, as before.
        val older = seenWithout.copy(titles = seenWithout.titles.map { it.copy(newcomerSince = null) })
        assertTrue(XboxSessionDiff.sessions(older, next, zone, false).isEmpty())
        // First seen two days before: it counts, undated (G1).
        val longAgo = seenWithout.copy(
            titles = listOf(title(ring, 1100), title(bricks, null, name = "Brick Wars").copy(newcomerSince = "2026-11-25T05:00:00Z"))
        )
        val undated = XboxSessionDiff.sessions(longAgo, next, zone, false).single()
        assertNull(undated.start)
        assertEquals("2026-11-25T05:00:00Z", undated.since)
        assertEquals("2026-11-27", undated.date)
        assertEquals(180, undated.minutes)
    }

    @Test
    fun `a newcomer whose minutes don't fit is a baseline`() {
        // Thirty hours of play in the 24 since it was first seen: older play is in them.
        val tooMany = snapshot("2026-11-27T05:00:00Z", title(ring, 1100), title(bricks, 1800, "2026-11-26T23:30:00Z", name = "Brick Wars"))
        assertTrue(XboxSessionDiff.sessions(seenWithout, tooMany, zone, false).isEmpty())
        // Last played before it was first seen: older play too.
        val older = snapshot("2026-11-27T05:00:00Z", title(ring, 1100), title(bricks, 180, "2026-11-25T21:00:00Z", name = "Brick Wars"))
        assertTrue(XboxSessionDiff.sessions(seenWithout, older, zone, false).isEmpty())
        // A time that doesn't read: a baseline.
        val garbled = seenWithout.copy(titles = listOf(title(ring, 1100), title(bricks, null, name = "Brick Wars").copy(newcomerSince = "not a time")))
        val next = snapshot("2026-11-27T05:00:00Z", title(ring, 1100), title(bricks, 180, "2026-11-26T23:30:00Z", name = "Brick Wars"))
        assertTrue(XboxSessionDiff.sessions(garbled, next, zone, false).isEmpty())
    }
}
