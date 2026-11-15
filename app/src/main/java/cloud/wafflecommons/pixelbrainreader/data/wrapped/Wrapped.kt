package cloud.wafflecommons.pixelbrainreader.data.wrapped

import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionRecord
import cloud.wafflecommons.pixelbrainreader.data.health.HealthInsights
import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.model.HabitLogEntry
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
import cloud.wafflecommons.pixelbrainreader.data.modes.CustodySchedule
import cloud.wafflecommons.pixelbrainreader.data.remote.FileHistory
import cloud.wafflecommons.pixelbrainreader.data.review.WeeklyActivity
import cloud.wafflecommons.pixelbrainreader.data.vault.NoteLocation
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import cloud.wafflecommons.pixelbrainreader.domain.lifeos.HabitScheduler
import cloud.wafflecommons.pixelbrainreader.domain.lifeos.HabitStatsCalculator
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * PixelBrain Wrapped (13.0, X5, the owner's D4): the year so far as cards, from what the phone
 * already knows. Nothing of it is written anywhere. A card without data is left out, and every
 * number is a count or an average of measured days, never an estimate.
 */
object Wrapped {

    /** A name and how many: a folder's notes, a tag's uses, a game's minutes. */
    data class Count(val name: String, val count: Int)

    sealed interface Card {
        data class Opening(val year: Int, val from: LocalDate, val to: LocalDate) : Card {
            val days: Int get() = ChronoUnit.DAYS.between(from, to).toInt() + 1
        }

        /** [workNotes]: notes in work folders while work is hidden, counted and never named. */
        data class Notes(val created: Int, val edited: Int, val topFolders: List<Count>, val workNotes: Int) : Card

        data class Tags(val top: List<Count>) : Card

        data class Mood(val average: Double, val days: Int, val bestMonth: YearMonth?, val bestMonthAverage: Double?) : Card

        data class Habits(val checkIns: Int, val habitsKept: Int, val bestStreak: Count?) : Card

        data class Body(val sleepMinutes: Double?, val nights: Int, val steps: Double?, val stepDays: Int, val bestSteps: Pair<LocalDate, Int>?) : Card

        /** [top]'s count is minutes. */
        data class Games(val minutes: Int, val sessions: Int, val top: Count?) : Card

        data class Xp(val level: Int, val total: Int, val className: String) : Card

        data object Closing : Card
    }

    /** A month needs this many measured days to be the year's best. */
    const val BEST_MONTH_MIN_DAYS = 5

    private const val TOP_FOLDERS = 3
    private const val TOP_TAGS = 5

    /** Everything the cards are made of, read by WrappedRepository. */
    data class Facts(
        val from: LocalDate,
        val to: LocalDate,
        val history: FileHistory,
        val zone: ZoneId,
        /** True for a path in a work folder while work is hidden (Visibility.hideWork). */
        val hiddenWork: (String) -> Boolean,
        /** Each counted note's tags, read from its text. */
        val tags: Map<String, List<String>>,
        val moodByDay: Map<LocalDate, Double>,
        val habits: List<HabitConfig>,
        val habitLogs: Map<String, List<HabitLogEntry>>,
        val custody: CustodySchedule?,
        val healthDays: List<HealthInsights.Day>,
        val gameSessions: List<GameSessionRecord>,
        val level: Int,
        val totalXp: Int,
        val className: String
    )

    fun cards(facts: Facts): List<Card> {
        val notes = notes(facts)
        return listOfNotNull(
            Card.Opening(facts.from.year, facts.from, facts.to),
            notes.takeIf { it.created + it.edited + it.workNotes > 0 },
            tags(facts),
            mood(facts.moodByDay),
            habits(facts),
            body(facts.healthDays),
            games(facts.gameSessions),
            Card.Xp(facts.level, facts.totalXp, facts.className).takeIf { it.total > 0 },
            Card.Closing
        )
    }

    /** The notes that count: created or changed from [from] to [to], daily notes, data and generated notes aside. */
    fun countedNotes(history: FileHistory, from: LocalDate, to: LocalDate, zone: ZoneId): List<String> {
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        fun within(at: Long?) = at != null && at >= start && at < end
        return (history.firstSeen.keys + history.lastChanged.keys).distinct()
            .filter { it.endsWith(".md") && !WeeklyActivity.excluded(it) && (within(history.firstSeen[it]) || within(history.lastChanged[it])) }
            .sorted()
    }

    fun notes(facts: Facts): Card.Notes {
        val start = facts.from.atStartOfDay(facts.zone).toInstant().toEpochMilli()
        val counted = countedNotes(facts.history, facts.from, facts.to, facts.zone)
        val (work, shown) = counted.partition(facts.hiddenWork)
        val created = shown.count { (facts.history.firstSeen[it] ?: Long.MIN_VALUE) >= start }
        val folders = shown
            .groupingBy { path -> NoteLocation.folderOf(path).substringBefore('/').ifEmpty { NoteLocation.ROOT } }
            .eachCount()
            .map { (name, count) -> Count(name, count) }
            .sortedWith(compareByDescending<Count> { it.count }.thenBy { it.name })
            .take(TOP_FOLDERS)
        return Card.Notes(created = created, edited = shown.size - created, topFolders = folders, workNotes = work.size)
    }

    /** The tags of the counted notes, work ones aside while work is hidden: a client's name can be a tag. */
    fun tags(facts: Facts): Card.Tags? {
        val counts = countedNotes(facts.history, facts.from, facts.to, facts.zone)
            .filterNot(facts.hiddenWork)
            .flatMap { path -> facts.tags[path].orEmpty().map(VaultSchema::normalizeTag).filter { it.isNotEmpty() }.distinct() }
            .groupingBy { it }
            .eachCount()
        if (counts.isEmpty()) return null
        return Card.Tags(
            counts.map { (tag, count) -> Count(tag, count) }
                .sortedWith(compareByDescending<Count> { it.count }.thenBy { it.name })
                .take(TOP_TAGS)
        )
    }

    fun mood(byDay: Map<LocalDate, Double>): Card.Mood? {
        if (byDay.isEmpty()) return null
        val best = byDay.entries
            .groupBy { YearMonth.from(it.key) }
            .filterValues { it.size >= BEST_MONTH_MIN_DAYS }
            .mapValues { (_, days) -> days.map { it.value }.average() }
            .maxWithOrNull(compareBy<Map.Entry<YearMonth, Double>> { it.value }.thenBy { it.key })
        return Card.Mood(byDay.values.average(), byDay.size, best?.key, best?.value)
    }

    fun habits(facts: Facts): Card.Habits? {
        val from = facts.from.toString()
        val to = facts.to.toString()
        var checkIns = 0
        var kept = 0
        var best: Count? = null
        facts.habits.forEach { habit ->
            val logs = facts.habitLogs[habit.id].orEmpty().filter { it.date in from..to }
            val done = logs.count { HabitStatsCalculator.isComplete(habit, it) }
            if (done == 0) return@forEach
            checkIns += done
            kept++
            val streak = longestStreak(habit, logs, facts.from, facts.to, facts.custody)
            if (streak > (best?.count ?: 0)) best = Count(habit.title, streak)
        }
        return if (checkIns == 0) null else Card.Habits(checkIns, kept, best)
    }

    /**
     * The longest run of planned days done, the Habits screen's rules: a day it isn't planned and a
     * protected skip carry the run, a planned day missed ends it, and the last day isn't over yet.
     * Interval habits have no run (the Habits screen counts their completions): 0.
     */
    fun longestStreak(habit: HabitConfig, logs: List<HabitLogEntry>, from: LocalDate, to: LocalDate, custody: CustodySchedule?): Int {
        if (habit.scheduleMode.equals("INTERVAL", ignoreCase = true)) return 0
        val byDate = HashMap<String, HabitLogEntry>()
        logs.forEach { byDate.putIfAbsent(it.date, it) }
        var best = 0
        var run = 0
        var day = from
        while (!day.isAfter(to)) {
            val log = byDate[day.toString()]
            when {
                HabitStatsCalculator.isComplete(habit, log) -> {
                    run++
                    if (run > best) best = run
                }
                !HabitScheduler.isScheduledOn(habit, day, null, custody) -> Unit
                log?.status == HabitStatus.PROTECTED -> Unit
                day == to -> Unit
                else -> run = 0
            }
            day = day.plusDays(1)
        }
        return best
    }

    fun body(days: List<HealthInsights.Day>): Card.Body? {
        val nights = days.mapNotNull { it.sleepMinutes }
        val steps = days.mapNotNull { day -> day.steps?.let { day.date to it } }
        if (nights.isEmpty() && steps.isEmpty()) return null
        val bestSteps = steps.maxWithOrNull(compareBy<Pair<LocalDate, Double>> { it.second }.thenBy { it.first })
        return Card.Body(
            sleepMinutes = nights.takeIf { it.isNotEmpty() }?.average(),
            nights = nights.size,
            steps = steps.takeIf { it.isNotEmpty() }?.map { it.second }?.average(),
            stepDays = steps.size,
            bestSteps = bestSteps?.let { it.first to it.second.toInt() }
        )
    }

    fun games(sessions: List<GameSessionRecord>): Card.Games? {
        if (sessions.isEmpty()) return null
        // A PSN session finds its page through the links: the repository fills it in when it can.
        val top = sessions
            .mapNotNull { session -> session.page?.let { page -> page to session.minutes } }
            .groupBy({ it.first }, { it.second })
            .map { (page, minutes) -> Count(gameName(page), minutes.sum()) }
            .maxWithOrNull(compareBy<Count> { it.count }.thenBy { it.name })
        return Card.Games(sessions.sumOf { it.minutes }, sessions.size, top)
    }

    /** A game page's name: its file name, without the folder or `.md`. */
    fun gameName(page: String): String = page.substringAfterLast('/').removeSuffix(".md")
}
