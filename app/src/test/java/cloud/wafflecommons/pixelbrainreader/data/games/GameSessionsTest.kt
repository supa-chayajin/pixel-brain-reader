package cloud.wafflecommons.pixelbrainreader.data.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class GameSessionsTest {

    private val zone: ZoneId = ZoneId.of("Europe/Paris")

    private fun at(day: Int, h: Int, m: Int, month: Int = 11): String =
        ZonedDateTime.of(2026, month, day, h, m, 0, 0, zone).toInstant().toString()

    private fun title(id: String, seconds: Long, last: String?, first: String? = at(1, 10, 0, month = 1)) =
        PsnTitleState(titleId = id, name = id, playSeconds = seconds, lastPlayed = last, firstPlayed = first)

    @Test
    fun `ISO play times read to the second`() {
        assertEquals(228L * 3600 + 56 * 60 + 33, PsnDuration.seconds("PT228H56M33S"))
        assertEquals(45L * 60, PsnDuration.seconds("PT45M"))
        assertEquals(3600L, PsnDuration.seconds("PT1H"))
        assertEquals(0L, PsnDuration.seconds("PT0S"))
        assertEquals(7203L, PsnDuration.seconds("PT2H3.5S"))
        assertEquals(26L * 3600, PsnDuration.seconds("P1DT2H"))
        assertNull(PsnDuration.seconds("PT"))
        assertNull(PsnDuration.seconds("2 hours"))
        assertNull(PsnDuration.seconds(null))
    }

    @Test
    fun `the first sync is a baseline, not a session`() {
        val now = PsnSnapshot(at(14, 23, 40), listOf(title("A", 36_000, at(14, 21, 0))))
        assertTrue(SessionDiff.sessions(null, now, zone, lastPlayedIsStart = false).isEmpty())
    }

    @Test
    fun `play added since the last sync is a session ending at the last played time`() {
        val before = PsnSnapshot(at(13, 23, 40), listOf(title("A", 36_000, at(13, 20, 0))))
        val now = PsnSnapshot(at(14, 23, 40), listOf(title("A", 36_000 + 9_300, at(14, 23, 40))))
        val s = SessionDiff.sessions(before, now, zone, lastPlayedIsStart = false).single()
        assertEquals("2026-11-14", s.date)
        assertEquals("21:05", s.start)
        assertEquals(155, s.minutes)
        assertEquals(GameSessionRecord.SOURCE_PSN, s.source)
        assertEquals("A", s.titleId)
        assertNull(s.page)
    }

    @Test
    fun `when PSN's last played time is the start, the session starts there`() {
        val before = PsnSnapshot(at(13, 23, 40), listOf(title("A", 36_000, at(13, 20, 0))))
        val now = PsnSnapshot(at(14, 23, 40), listOf(title("A", 36_000 + 9_300, at(14, 21, 5))))
        assertEquals("21:05", SessionDiff.sessions(before, now, zone, lastPlayedIsStart = true).single().start)
    }

    @Test
    fun `a session past midnight belongs to the day it started`() {
        // Found by the 06:00 run: two hours ending at 00:30.
        val before = PsnSnapshot(at(14, 23, 40), listOf(title("A", 36_000, at(14, 20, 0))))
        val now = PsnSnapshot(at(15, 6, 0), listOf(title("A", 36_000 + 7_200, at(15, 0, 30))))
        val s = SessionDiff.sessions(before, now, zone, lastPlayedIsStart = false).single()
        assertEquals("2026-11-14", s.date)
        assertEquals("22:30", s.start)
    }

    @Test
    fun `a title first played since the last sync counts whole, an older newcomer does not`() {
        val before = PsnSnapshot(at(13, 23, 40), listOf(title("A", 1_000, at(13, 20, 0))))
        val fresh = title("B", 3_600, at(14, 22, 0), first = at(14, 21, 0))
        val old = title("C", 90_000, at(14, 22, 0), first = at(1, 9, 0, month = 3))
        val sessions = SessionDiff.sessions(before, PsnSnapshot(at(14, 23, 40), listOf(before.titles[0], fresh, old)), zone, false)
        assertEquals(listOf("B"), sessions.map { it.titleId })
        assertEquals(60, sessions.single().minutes)
    }

    @Test
    fun `a lowered or tiny play time is no session`() {
        val before = PsnSnapshot(at(13, 23, 40), listOf(title("A", 5_000, at(13, 20, 0)), title("B", 5_000, at(13, 20, 0))))
        val now = PsnSnapshot(at(14, 23, 40), listOf(title("A", 4_000, at(14, 20, 0)), title("B", 5_030, at(14, 20, 0))))
        assertTrue(SessionDiff.sessions(before, now, zone, false).isEmpty())
    }

    private val pageA = GamePage("A.md", "Game A", emptyList(), GameTemplate.OTHER, GameState.PLAYING, null, null)
    private val pageB = GamePage("B.md", "Game B", emptyList(), GameTemplate.OTHER, GameState.PLAYING, null, null)
    private val pages = mapOf("A" to pageA, "B" to pageB)
    private fun pageOf(r: GameSessionRecord): GamePage? = r.page?.let { p -> pages.values.firstOrNull { it.path == p } } ?: pages[r.titleId]

    @Test
    fun `one line per game and day, the manual start with PSN's measured length`() {
        val records = listOf(
            GameSessionRecord("m1", "2026-11-14", "21:00", 150, GameSessionRecord.SOURCE_MANUAL, page = "A.md"),
            GameSessionRecord("p1", "2026-11-14", "21:04", 155, GameSessionRecord.SOURCE_PSN, titleId = "A"),
            GameSessionRecord("p2", "2026-11-14", "14:00", 30, GameSessionRecord.SOURCE_PSN, titleId = "B"),
            GameSessionRecord("p3", "2026-11-13", "20:00", 60, GameSessionRecord.SOURCE_PSN, titleId = "A")
        )
        val lines = GameDays.lines(LocalDate.of(2026, 11, 14), records, ::pageOf)
        assertEquals(listOf(pageB, pageA), lines.map { it.page })
        assertEquals(listOf(LocalTime.of(14, 0), LocalTime.of(21, 0)), lines.map { it.start })
        assertEquals(listOf(30, 155), lines.map { it.minutes })
        assertEquals(mapOf("A.md" to 215, "B.md" to 30), GameDays.totals(records, ::pageOf))
    }

    @Test
    fun `manual sessions alone count their own length, and an unknown page is left out`() {
        val records = listOf(
            GameSessionRecord("m1", "2026-11-14", "21:00", 40, GameSessionRecord.SOURCE_MANUAL, page = "A.md"),
            GameSessionRecord("m2", "2026-11-14", "22:00", 20, GameSessionRecord.SOURCE_MANUAL, page = "A.md"),
            GameSessionRecord("p9", "2026-11-14", "10:00", 20, GameSessionRecord.SOURCE_PSN, titleId = "unlinked")
        )
        val line = GameDays.lines(LocalDate.of(2026, 11, 14), records, ::pageOf).single()
        assertEquals(LocalTime.of(21, 0), line.start)
        assertEquals(60, line.minutes)
    }

    @Test
    fun `a running session survives encoding and stops as a dated record`() {
        val start = ZonedDateTime.of(2026, 11, 14, 23, 30, 0, 0, zone).toInstant().toEpochMilli()
        val running = RunningGame(start, "30_Areas/Games/A/A.md")
        assertEquals(running, RunningGame.decode(running.encode()))
        assertNull(RunningGame.decode("garbage"))
        assertNull(RunningGame.decode("123|not-a-note"))
        val record = running.record(start + 95 * 60_000L, zone)!!
        assertEquals("2026-11-14", record.date)
        assertEquals("23:30", record.start)
        assertEquals(95, record.minutes)
        assertEquals(GameSessionRecord.SOURCE_MANUAL, record.source)
        assertNull(running.record(start + 30_000L, zone))
    }
}
