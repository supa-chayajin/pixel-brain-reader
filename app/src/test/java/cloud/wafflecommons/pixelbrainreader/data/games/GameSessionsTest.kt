package cloud.wafflecommons.pixelbrainreader.data.games

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
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

    // --- 13.3: both consoles (XB3, XB5) ---------------------------------------------------------

    private fun psn(id: String, start: String, minutes: Int, title: String = "A") =
        GameSessionRecord(id, "2026-11-27", start, minutes, GameSessionRecord.SOURCE_PSN, titleId = title)

    private fun xbox(id: String, start: String, minutes: Int, title: String = "A") =
        GameSessionRecord(id, "2026-11-27", start, minutes, GameSessionRecord.SOURCE_XBOX, titleId = title)

    @Test
    fun `both consoles on one day make one line with the total`() {
        val records = listOf(psn("p1", "20:00", 60), xbox("x1", "22:10", 45))
        val line = GameDays.lines(LocalDate.of(2026, 11, 27), records, ::pageOf).single()
        assertEquals(pageA, line.page)
        assertEquals(LocalTime.of(20, 0), line.start)
        assertEquals(105, line.minutes)
        assertEquals(mapOf("A.md" to 105), GameDays.totals(records, ::pageOf))
    }

    @Test
    fun `a manual start gives the line its time, the consoles their length`() {
        val records = listOf(
            GameSessionRecord("m1", "2026-11-27", "19:55", 200, GameSessionRecord.SOURCE_MANUAL, page = "A.md"),
            psn("p1", "20:00", 60),
            xbox("x1", "22:10", 45)
        )
        val line = GameDays.lines(LocalDate.of(2026, 11, 27), records, ::pageOf).single()
        assertEquals(LocalTime.of(19, 55), line.start)
        assertEquals(105, line.minutes)
    }

    @Test
    fun `an Xbox session alone counts`() {
        val records = listOf(xbox("x1", "21:30", 95, title = "B"), xbox("x2", "23:00", 20, title = "B"))
        val line = GameDays.lines(LocalDate.of(2026, 11, 27), records, ::pageOf).single()
        assertEquals(pageB, line.page)
        assertEquals(LocalTime.of(21, 30), line.start)
        assertEquals(115, line.minutes)
        assertTrue(records.first().measured)
        assertTrue(psn("p", "20:00", 1).measured)
        assertFalse(GameSessionRecord("m", "2026-11-27", "20:00", 1, GameSessionRecord.SOURCE_MANUAL, page = "A.md").measured)
        assertEquals("xbox", GameSessionRecord.SOURCE_XBOX)
    }

    @Test
    fun `hours add both consoles once either measures`() {
        assertEquals(12.0 + 20.0, GameHours.of(12 * 3600L, playedBefore = 228.0, manualMinutes = 90, xboxMinutes = 1200)!!, 1e-9)
        assertEquals(20.0, GameHours.of(0, playedBefore = 228.0, manualMinutes = 90, xboxMinutes = 1200)!!, 1e-9)
        assertEquals(12.0, GameHours.of(12 * 3600L, playedBefore = null, manualMinutes = 0, xboxMinutes = 0)!!, 1e-9)
    }

    @Test
    fun `played_before shows until a console measures`() {
        assertEquals(229.5, GameHours.of(0, playedBefore = 228.0, manualMinutes = 90, xboxMinutes = 0)!!, 1e-9)
        // A value below zero measures nothing.
        assertEquals(229.5, GameHours.of(-5, playedBefore = 228.0, manualMinutes = 90, xboxMinutes = -1)!!, 1e-9)
        assertEquals(1.5, GameHours.of(0, playedBefore = null, manualMinutes = 90, xboxMinutes = 0)!!, 1e-9)
    }

    @Test
    fun `nothing measured nor typed is no hours`() {
        assertNull(GameHours.of(0, playedBefore = null, manualMinutes = 0, xboxMinutes = 0))
        assertNull(GameHours.of(0, null, 0))
    }

    @Test
    fun `the old three-argument calls are unchanged`() {
        assertEquals(10.0, GameHours.of(36_000, 228.0, 90)!!, 0.0)
        assertEquals(229.5, GameHours.of(0, 228.0, 90)!!, 0.0)
        assertEquals(1.5, GameHours.of(0, null, 90)!!, 0.0)
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

    // --- 13.4, G1: a gap of more than 30 hours ------------------------------------------------

    @Test
    fun `a gap of more than 30 hours gives one undated record`() {
        // PSN paused a week (an expired sign-in), then six hours of play since.
        val before = PsnSnapshot(at(20, 23, 40), listOf(title("A", 36_000, at(20, 21, 0))))
        val now = PsnSnapshot(at(27, 23, 40), listOf(title("A", 36_000 + 6 * 3600L, at(27, 22, 0))))
        val s = SessionDiff.sessions(before, now, zone, lastPlayedIsStart = false).single()
        assertEquals("psn:A:2026-11-20T22:40:00Z", s.id)
        // The last played day, no start, and since when the play may have run.
        assertEquals("2026-11-27", s.date)
        assertNull(s.start)
        assertEquals(360, s.minutes)
        assertEquals(GameSessionRecord.SOURCE_PSN, s.source)
        assertEquals("A", s.titleId)
        assertEquals("2026-11-20T22:40:00Z", s.since)
        assertEquals(30 * 3600L, SessionDiff.UNDATED_GAP_SECONDS)
        // As the month's file holds it.
        assertEquals(
            """{"id":"psn:A:2026-11-20T22:40:00Z","date":"2026-11-27","minutes":360,"source":"psn","titleId":"A","since":"2026-11-20T22:40:00Z"}""",
            Json.encodeToString(GameSessionRecord.serializer(), s)
        )
    }

    @Test
    fun `a gap of exactly 30 hours stays dated`() {
        val before = PsnSnapshot(at(14, 0, 0), listOf(title("A", 36_000, at(13, 20, 0))))
        val now = PsnSnapshot(at(15, 6, 0), listOf(title("A", 36_000 + 3_600, at(15, 5, 0))))
        val s = SessionDiff.sessions(before, now, zone, lastPlayedIsStart = false).single()
        assertEquals("2026-11-15", s.date)
        assertEquals("04:00", s.start)
        assertNull(s.since)
        // One minute more and it is undated.
        val later = PsnSnapshot(at(15, 6, 1), now.titles)
        assertNull(SessionDiff.sessions(before, later, zone, false).single().start)
    }

    @Test
    fun `undated records write no day line but count in the totals' sources`() {
        val undated = GameSessionRecord("psn:A:x", "2026-11-27", null, 360, GameSessionRecord.SOURCE_PSN, titleId = "A", since = "2026-11-20T22:40:00Z")
        val dated = GameSessionRecord("p1", "2026-11-27", "20:00", 60, GameSessionRecord.SOURCE_PSN, titleId = "B")
        val records = listOf(undated, dated)
        assertEquals(listOf(pageB), GameDays.lines(LocalDate.of(2026, 11, 27), records, ::pageOf).map { it.page })
        assertTrue(GameDays.lines(LocalDate.of(2026, 11, 27), listOf(undated), ::pageOf).isEmpty())
        assertEquals(mapOf("B.md" to 60), GameDays.totals(records, ::pageOf))
        // The record itself stays, minutes and all: the consoles' own totals give the hours, Wrapped sums the records.
        assertEquals(420, records.sumOf { it.minutes })
        assertEquals(pageA, pageOf(undated))
    }

    // --- 13.4: typed sessions keyed per page (GA1) ---------------------------------------------

    private val glass = "30_Areas/Games/Glass_Tower/Glass_Tower.md"
    private val orchard = "30_Areas/Games/Tin_Orchard/Tin_Orchard.md"
    private val day = LocalDate.of(2026, 11, 27)

    @Test
    fun `a typed session replaces the same page's session of the same minute, a phone-started one too`() {
        // Started on the phone at 21:00:30 and stopped an hour later.
        val startMillis = ZonedDateTime.of(2026, 11, 27, 21, 0, 30, 0, zone).toInstant().toEpochMilli()
        val started = RunningGame(startMillis, glass).record(startMillis + 60 * 60_000L, zone)!!
        val typed = ManualSessions.record(glass, day, LocalTime.of(21, 0), 75, zone)!!
        assertNotEquals(started.id, typed.id)
        assertTrue(GameSessionMerge.replaces(typed, started))
        val merged = GameSessionMerge.merge(listOf(started), listOf(typed))
        assertEquals(listOf(typed), merged.sessions)
        assertTrue(merged.replacedDates.isEmpty())
        // Typing it again replaces it again.
        val again = typed.copy(minutes = 80)
        assertEquals(listOf(again), GameSessionMerge.merge(merged.sessions, listOf(again)).sessions)
    }

    @Test
    fun `two games at the same minute keep both sessions`() {
        val a = ManualSessions.record(glass, day, LocalTime.of(21, 0), 60, zone)!!
        val b = ManualSessions.record(orchard, day, LocalTime.of(21, 0), 45, zone)!!
        assertNotEquals(a.id, b.id)
        assertEquals(listOf(a, b).sortedWith(GameSessionMerge.ORDER), GameSessionMerge.merge(listOf(a), listOf(b)).sessions)
        // Ids from before 13.4 (no page in them) never replace another game's either.
        val oldA = a.copy(id = "manual:1764273600000")
        val oldB = b.copy(id = "manual:1764273600000")
        assertFalse(GameSessionMerge.replaces(oldB, oldA))
        assertEquals(2, GameSessionMerge.merge(listOf(oldA), listOf(oldB)).sessions.size)
        // A console record replaces its own id, and says which day it left.
        val first = GameSessionRecord("psn:X:2026-11-25T22:40:00Z", "2026-11-26", "20:00", 60, GameSessionRecord.SOURCE_PSN, titleId = "X")
        val replay = first.copy(date = "2026-11-27", minutes = 90)
        val merged = GameSessionMerge.merge(listOf(first, a), listOf(replay))
        assertEquals(listOf(replay, a).sortedWith(GameSessionMerge.ORDER), merged.sessions)
        assertEquals(setOf("2026-11-26"), merged.replacedDates)
    }

    @Test
    fun `an old id is kept as it is`() {
        val old = GameSessionRecord("manual:1763236800000", "2026-11-15", "21:00", 95, GameSessionRecord.SOURCE_MANUAL, page = glass)
        val other = ManualSessions.record(glass, LocalDate.of(2026, 11, 15), LocalTime.of(22, 0), 30, zone)!!
        assertEquals(listOf(old, other), GameSessionMerge.merge(listOf(old), listOf(other)).sessions)
        assertNull(GameSessionMerge.baselineOf(old.id))
        // Its fix takes the new key; the repository finds the old record by the id read.
        val fixed = ManualSessions.edited(old, LocalTime.of(21, 30), 60, zone)!!
        val fixedStart = ZonedDateTime.of(2026, 11, 15, 21, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(ManualSessions.idFor(glass, fixedStart), fixed.id)
    }

    @Test
    fun `the baseline of a console id reads its previous sync`() {
        assertEquals(Instant.parse("2026-11-20T22:40:00Z"), GameSessionMerge.baselineOf("psn:PPSA00001_00:2026-11-20T22:40:00Z"))
        assertEquals(Instant.parse("2026-11-26T22:40:00.123Z"), GameSessionMerge.baselineOf("xbox:1915865634:2026-11-26T22:40:00.123Z"))
        assertNull(GameSessionMerge.baselineOf("manual:1763236800000:1a2b3c4d"))
        assertNull(GameSessionMerge.baselineOf("psn:PPSA00001_00"))
        assertNull(GameSessionMerge.baselineOf("psn:PPSA00001_00:not a time"))
        assertNull(GameSessionMerge.baselineOf("other:x:2026-11-20T22:40:00Z"))
    }

    // --- 13.4: sessions of a page that's gone, and the game played last ---------------------------

    @Test
    fun `orphaned typed sessions are listed, a moved page's are not, none while the mirror is empty`() {
        val tower = GamePage(glass, "Glass Tower", emptyList(), GameTemplate.OTHER, GameState.PLAYING, null, null)
        val moved = GamePage("30_Areas/Done/Tin_Orchard/Tin_Orchard.md", "Tin Orchard", emptyList(), GameTemplate.OTHER, GameState.DONE, null, null)
        fun manual(page: String, date: String, minutes: Int) =
            GameSessionRecord("manual:$date", date, "20:00", minutes, GameSessionRecord.SOURCE_MANUAL, page = page)
        val sessions = listOf(
            manual(glass, "2026-11-01", 30),
            // Moved on the desktop: the same file name, another folder.
            manual(orchard, "2026-11-02", 40),
            // Renamed or deleted.
            manual("30_Areas/Games/Lantern_Bay/Lantern_Bay.md", "2026-11-03", 50),
            manual("30_Areas/Games/Lantern_Bay/Lantern_Bay.md", "2026-11-10", 20),
            manual("30_Areas/Games/Old_Name/Old_Name.md", "2026-10-01", 15),
            GameSessionRecord("psn:X:t", "2026-11-04", "20:00", 60, GameSessionRecord.SOURCE_PSN, titleId = "X")
        )
        assertEquals(
            listOf(
                OrphanedPage("30_Areas/Games/Lantern_Bay/Lantern_Bay.md", "Lantern Bay", 70, 2, LocalDate.of(2026, 11, 10)),
                OrphanedPage("30_Areas/Games/Old_Name/Old_Name.md", "Old Name", 15, 1, LocalDate.of(2026, 10, 1))
            ),
            OrphanedSessions.of(sessions, listOf(tower, moved))
        )
        // The notes' mirror still filling: nothing is called orphaned.
        assertTrue(OrphanedSessions.of(sessions, emptyList()).isEmpty())
        // Two pages with the moved one's file name: that move can't be told, its sessions are listed.
        val twin = moved.copy(path = "30_Areas/Other/Tin_Orchard/Tin_Orchard.md")
        assertTrue(OrphanedSessions.of(sessions, listOf(tower, moved, twin)).any { it.oldPath == orchard })
    }

    @Test
    fun `last played picks the latest of sessions and both consoles`() {
        val a = GamePage("A.md", "Game A", emptyList(), GameTemplate.OTHER, GameState.BACKLOG, null, null)
        val b = GamePage("B.md", "Game B", emptyList(), GameTemplate.OTHER, GameState.PLAYING, null, null)
        val c = GamePage("C.md", "Game C", emptyList(), GameTemplate.OTHER, GameState.BACKLOG, null, null)
        val pages = listOf(a, b, c)
        val links = GameLinks(links = mapOf("PPSA1" to "B.md")).linkConsole(GameConsole.XBOX, "1915865634", "C.md")
        val sessions = listOf(GameSessionRecord("m", "2026-11-26", "21:00", 60, GameSessionRecord.SOURCE_MANUAL, page = "A.md"))
        val psn = PsnSnapshot("x", listOf(PsnTitleState("PPSA1", "B", lastPlayed = at(27, 20, 0))))
        val xbox = XboxSnapshot("x", titles = listOf(XboxTitleState("1915865634", "C", lastPlayed = at(27, 22, 0))))
        val resolve: (GameSessionRecord) -> GamePage? = { r -> pages.firstOrNull { it.path == r.page } }
        assertEquals(c, LastPlayed.page(pages, links, sessions, psn, xbox, zone, resolve))
        assertEquals(b, LastPlayed.page(pages, links, sessions, psn, null, zone, resolve))
        // A later typed session wins.
        val later = sessions + GameSessionRecord("m2", "2026-11-28", "09:00", 30, GameSessionRecord.SOURCE_MANUAL, page = "A.md")
        assertEquals(a, LastPlayed.page(pages, links, later, psn, xbox, zone, resolve))
        // An undated record counts at midnight of its day: before PlayStation's evening here.
        val undated = sessions + GameSessionRecord("psn:Q:t", "2026-11-27", null, 300, GameSessionRecord.SOURCE_PSN, titleId = "Q", since = "x")
        val resolveQ: (GameSessionRecord) -> GamePage? = { r -> if (r.titleId == "Q") a else resolve(r) }
        assertEquals(b, LastPlayed.page(pages, links, undated, psn, null, zone, resolveQ))
        // Nothing played: the game being played, else the first page; none without pages.
        assertEquals(b, LastPlayed.page(pages, GameLinks(), emptyList(), null, null, zone) { null })
        assertEquals(a, LastPlayed.page(listOf(a, c), GameLinks(), emptyList(), null, null, zone) { null })
        assertNull(LastPlayed.page(emptyList(), links, sessions, psn, xbox, zone, resolve))
    }
}
