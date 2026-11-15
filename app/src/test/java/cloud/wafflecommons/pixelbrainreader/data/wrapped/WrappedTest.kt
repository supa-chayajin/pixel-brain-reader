package cloud.wafflecommons.pixelbrainreader.data.wrapped

import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionRecord
import cloud.wafflecommons.pixelbrainreader.data.health.HealthInsights
import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.model.HabitLogEntry
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
import cloud.wafflecommons.pixelbrainreader.data.remote.FileHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class WrappedTest {

    private val zone = ZoneOffset.UTC
    private val from = LocalDate.of(2026, 1, 1)
    private val today = LocalDate.of(2026, 11, 15)

    private fun at(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000

    private fun facts(
        history: FileHistory = FileHistory(),
        hiddenWork: (String) -> Boolean = { false },
        tags: Map<String, List<String>> = emptyMap(),
        moods: Map<LocalDate, Double> = emptyMap(),
        habits: List<HabitConfig> = emptyList(),
        logs: Map<String, List<HabitLogEntry>> = emptyMap(),
        health: List<HealthInsights.Day> = emptyList(),
        games: List<GameSessionRecord> = emptyList(),
        level: Int = 0,
        xp: Int = 0
    ) = Wrapped.Facts(from, today, history, zone, hiddenWork, tags, moods, habits, logs, null, health, games, level, xp, "")

    private val history = FileHistory(
        firstSeen = mapOf(
            "20_Projects/App/Idea.md" to at(LocalDate.of(2026, 3, 1)),
            "20_Projects/App/Plan.md" to at(LocalDate.of(2026, 4, 1)),
            "30_Areas/Health.md" to at(LocalDate.of(2025, 6, 1)),
            "30_Areas/Old.md" to at(LocalDate.of(2024, 6, 1)),
            "README.md" to at(LocalDate.of(2026, 2, 1)),
            "10_Journal/2026-03-02.md" to at(LocalDate.of(2026, 3, 2)),
            "99_Private/Journal.md.enc" to at(LocalDate.of(2026, 3, 2)),
            "40_Work/Client.md" to at(LocalDate.of(2026, 5, 1))
        ),
        lastChanged = mapOf(
            "30_Areas/Health.md" to at(LocalDate.of(2026, 9, 1)),
            "30_Areas/Old.md" to at(LocalDate.of(2025, 1, 1))
        )
    )

    @Test
    fun `the notes are those created or changed this year, daily and private ones aside`() {
        assertEquals(
            listOf("20_Projects/App/Idea.md", "20_Projects/App/Plan.md", "30_Areas/Health.md", "40_Work/Client.md", "README.md"),
            Wrapped.countedNotes(history, from, today, zone)
        )
    }

    @Test
    fun `created and edited are told apart, the folders ranked`() {
        val notes = Wrapped.notes(facts(history = history))
        assertEquals(4, notes.created)
        assertEquals(1, notes.edited)
        assertEquals(
            listOf(Wrapped.Count("20_Projects", 2), Wrapped.Count("30_Areas", 1), Wrapped.Count("40_Work", 1)),
            notes.topFolders
        )
    }

    @Test
    fun `work notes are counted, never named, while work is hidden`() {
        val notes = Wrapped.notes(facts(history = history, hiddenWork = { it.startsWith("40_Work/") }))
        assertEquals(1, notes.workNotes)
        assertTrue(notes.topFolders.none { it.name == "40_Work" })
        assertEquals(3, notes.created)
    }

    @Test
    fun `tags count once a note, work ones aside while hidden`() {
        val tags = mapOf(
            "20_Projects/App/Idea.md" to listOf("#Idea", "app", "idea"),
            "20_Projects/App/Plan.md" to listOf("app"),
            "40_Work/Client.md" to listOf("client-name")
        )
        val card = Wrapped.tags(facts(history = history, hiddenWork = { it.startsWith("40_Work/") }, tags = tags))!!
        assertEquals(listOf(Wrapped.Count("app", 2), Wrapped.Count("idea", 1)), card.top)
    }

    @Test
    fun `the mood's best month needs enough days`() {
        val moods = (1..5).associate { LocalDate.of(2026, 3, it) to 4.0 } +
            (1..2).associate { LocalDate.of(2026, 4, it) to 5.0 } +
            (1..6).associate { LocalDate.of(2026, 5, it) to 3.0 }
        val card = Wrapped.mood(moods)!!
        assertEquals(13, card.days)
        assertEquals(YearMonth.of(2026, 3), card.bestMonth)
        assertEquals(4.0, card.bestMonthAverage!!, 1e-9)
        assertNull(Wrapped.mood(emptyMap()))
    }

    private fun log(habit: String, date: LocalDate, status: HabitStatus = HabitStatus.COMPLETED) =
        HabitLogEntry(habit, date.toString(), 1.0, status, 0L)

    @Test
    fun `a run carries over the days a habit isn't planned`() {
        val habit = HabitConfig(id = "run", title = "Run", frequency = listOf("MON", "WED", "FRI"))
        // 2026-11-02 is a Monday.
        val logs = listOf(2, 4, 6, 9).map { log("run", LocalDate.of(2026, 11, it)) }
        assertEquals(4, Wrapped.longestStreak(habit, logs, from, today, null))
    }

    @Test
    fun `a planned day missed ends the run, a protected skip carries it`() {
        val habit = HabitConfig(id = "read", title = "Read")
        val days = listOf(1, 2, 3, 5, 6).map { log("read", LocalDate.of(2026, 10, it)) } +
            log("read", LocalDate.of(2026, 10, 7), HabitStatus.PROTECTED) +
            log("read", LocalDate.of(2026, 10, 8))
        assertEquals(3, Wrapped.longestStreak(habit, days, from, today, null))
        val protectedRun = listOf(5, 6).map { log("read", LocalDate.of(2026, 10, it)) } +
            log("read", LocalDate.of(2026, 10, 7), HabitStatus.PROTECTED) +
            listOf(8, 9).map { log("read", LocalDate.of(2026, 10, it)) }
        assertEquals(4, Wrapped.longestStreak(habit, protectedRun, from, today, null))
    }

    @Test
    fun `today isn't over, so not done yet keeps the run`() {
        val habit = HabitConfig(id = "walk", title = "Walk")
        val logs = (12..14).map { log("walk", LocalDate.of(2026, 11, it)) }
        assertEquals(3, Wrapped.longestStreak(habit, logs, from, today, null))
    }

    @Test
    fun `the habits card counts this year's check-ins only`() {
        val habit = HabitConfig(id = "walk", title = "Walk")
        val logs = mapOf("walk" to listOf(log("walk", LocalDate.of(2025, 12, 31))) + (1..3).map { log("walk", LocalDate.of(2026, 2, it)) })
        val card = Wrapped.habits(facts(habits = listOf(habit), logs = logs))!!
        assertEquals(3, card.checkIns)
        assertEquals(1, card.habitsKept)
        assertEquals(Wrapped.Count("Walk", 3), card.bestStreak)
        assertNull(Wrapped.habits(facts(habits = listOf(habit))))
    }

    @Test
    fun `sleep and steps average the measured days only`() {
        val days = listOf(
            HealthInsights.Day(LocalDate.of(2026, 6, 1), sleepMinutes = 420.0, steps = 10_000.0),
            HealthInsights.Day(LocalDate.of(2026, 6, 2), sleepMinutes = 480.0),
            HealthInsights.Day(LocalDate.of(2026, 6, 3), steps = 20_000.0),
            HealthInsights.Day(LocalDate.of(2026, 6, 4))
        )
        val card = Wrapped.body(days)!!
        assertEquals(450.0, card.sleepMinutes!!, 1e-9)
        assertEquals(2, card.nights)
        assertEquals(15_000.0, card.steps!!, 1e-9)
        assertEquals(LocalDate.of(2026, 6, 3) to 20_000, card.bestSteps)
        assertNull(Wrapped.body(listOf(HealthInsights.Day(LocalDate.of(2026, 6, 4)))))
    }

    @Test
    fun `games add up, the most played named from its page`() {
        val sessions = listOf(
            GameSessionRecord("1", "2026-03-01", minutes = 90, source = GameSessionRecord.SOURCE_MANUAL, page = "50_Games/Hollow Knight.md"),
            GameSessionRecord("2", "2026-03-02", minutes = 60, source = GameSessionRecord.SOURCE_MANUAL, page = "50_Games/Hollow Knight.md"),
            GameSessionRecord("3", "2026-03-03", minutes = 120, source = GameSessionRecord.SOURCE_MANUAL, page = "50_Games/Tetris.md"),
            GameSessionRecord("4", "2026-03-04", minutes = 200, source = GameSessionRecord.SOURCE_PSN, titleId = "CUSA0000")
        )
        val card = Wrapped.games(sessions)!!
        assertEquals(470, card.minutes)
        assertEquals(4, card.sessions)
        assertEquals(Wrapped.Count("Hollow Knight", 150), card.top)
    }

    @Test
    fun `a card without data is left out, the opening first and the closing last`() {
        val cards = Wrapped.cards(facts())
        assertEquals(listOf(Wrapped.Card.Opening(2026, from, today), Wrapped.Card.Closing), cards)
        assertEquals(319, (cards.first() as Wrapped.Card.Opening).days)
        val withXp = Wrapped.cards(facts(level = 12, xp = 3_415))
        assertTrue(withXp.any { it is Wrapped.Card.Xp })
    }
}
