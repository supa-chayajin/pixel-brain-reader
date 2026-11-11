package cloud.wafflecommons.pixelbrainreader.data.review

import cloud.wafflecommons.pixelbrainreader.data.gamification.XpGainEntry
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChoreEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.MoodEntity
import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.model.HabitLogEntry
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
import cloud.wafflecommons.pixelbrainreader.data.remote.FileHistory
import cloud.wafflecommons.pixelbrainreader.data.vault.MirrorNotes
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import cloud.wafflecommons.pixelbrainreader.data.work.DeskHandoffFormat
import cloud.wafflecommons.pixelbrainreader.data.work.LogEntry
import cloud.wafflecommons.pixelbrainreader.data.work.LogKind
import cloud.wafflecommons.pixelbrainreader.data.work.OverloadSignal
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectFormat
import cloud.wafflecommons.pixelbrainreader.domain.lifeos.HabitScheduler
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.IsoFields
import kotlin.math.roundToInt

/** An ISO week, Monday to Sunday: the weekly review's period (D6). */
data class ReviewWeek(val weekBasedYear: Int, val week: Int) {
    val monday: LocalDate
        get() = LocalDate.of(weekBasedYear, 1, 4)
            .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week.toLong())
            .with(DayOfWeek.MONDAY)
    val sunday: LocalDate get() = monday.plusDays(6)
    val days: List<LocalDate> get() = (0L..6L).map { monday.plusDays(it) }
    val key: String get() = "$weekBasedYear-W${week.toString().padStart(2, '0')}"
    val path: String get() = "$FOLDER/$key.md"

    fun previous(): ReviewWeek = of(monday.minusDays(7))
    fun next(): ReviewWeek = of(monday.plusDays(7))

    companion object {
        const val FOLDER = "10_Journal/Weekly"

        /** A week is due once its Sunday reaches the nightly burn (23:50); the margin covers an early start. */
        val DUE_AFTER: LocalTime = LocalTime.of(23, 0)

        private val PATH = Regex("""^10_Journal/Weekly/(\d{4})-W(\d{2})\.md$""")

        fun of(date: LocalDate) = ReviewWeek(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

        /** The most recent week whose review is due at [now]: the current one only from its Sunday night. */
        fun lastDue(now: LocalDateTime): ReviewWeek {
            val current = of(now.toLocalDate())
            return if (now.isBefore(current.sunday.atTime(DUE_AFTER))) current.previous() else current
        }

        /** The week of a review's path; null for any other path, and for a week the year doesn't have. */
        fun fromPath(path: String): ReviewWeek? {
            val match = PATH.matchEntire(path) ?: return null
            return try {
                ReviewWeek(match.groupValues[1].toInt(), match.groupValues[2].toInt()).also { it.monday }
            } catch (e: Exception) {
                null
            }
        }

        fun isReview(path: String): Boolean = fromPath(path) != null
    }
}

/** A habit's week: the days it was done, out of the days it was planned (null: an interval habit). */
data class HabitWeek(val title: String, val done: Int, val planned: Int?)

data class MoodWeek(val average: Double, val days: Int, val previousAverage: Double?)

/** One day's measured health; zero means not measured. */
data class DayHealth(val sleepMinutes: Long, val steps: Long)

data class HealthWeek(val sleepMinutesAverage: Int?, val nights: Int, val stepsAverage: Int?, val stepDays: Int)

/** The notes of the week: personal ones as path-style links, work ones only counted. */
data class NoteActivity(val created: List<String>, val edited: List<String>, val workCreated: Int, val workEdited: Int) {
    val isEmpty: Boolean get() = created.isEmpty() && edited.isEmpty() && workCreated == 0 && workEdited == 0
}

/** The week at work as counts and links only: the journal note shows in every mode (owner, 2026-11-11). */
data class WorkWeek(
    val entries: Map<LogKind, Int>,
    val decisionsWithoutRecord: Int,
    val objectivesMoved: Int,
    val objectivesStalled: Int,
    val meetings: Int,
    val overload: OverloadSignal?,
    val logLinks: List<String>
) {
    val isEmpty: Boolean
        get() = entries.isEmpty() && objectivesMoved == 0 && objectivesStalled == 0 && meetings == 0 && overload == null
}

data class WeeklyFacts(
    val habits: List<HabitWeek> = emptyList(),
    val mood: MoodWeek? = null,
    val health: HealthWeek? = null,
    val tasksDone: Int = 0,
    val tasksTotal: Int = 0,
    val choresDone: List<String> = emptyList(),
    val xpGained: Int? = null,
    val level: Int? = null,
    val notes: NoteActivity? = null,
    val work: WorkWeek? = null,
    val lastWeekPlan: List<String> = emptyList()
)

/** The week's measured facts, from what the vault and the phone already hold (pure). */
object WeeklyActivity {

    private const val ADR_LINK = "[[ADR "
    private val EXCLUDED_PREFIXES = listOf("10_Journal/data/", "10_Journal/system/", "99_System/", "99_Private/")

    fun habits(configs: List<HabitConfig>, logs: List<HabitLogEntry>, week: ReviewWeek): List<HabitWeek> {
        val inWeek = week.days.map { it.toString() }.toSet()
        val doneDays = logs.filter { it.status == HabitStatus.COMPLETED && it.date in inWeek }
            .groupBy { it.habitId }
            .mapValues { (_, entries) -> entries.map { it.date }.toSet().size }
        return configs.filter { !it.archived }.sortedBy { it.sortOrder }.mapNotNull { habit ->
            val created = try {
                LocalDate.parse(habit.createdDate.take(10))
            } catch (e: Exception) {
                null
            }
            val days = week.days.filter { created == null || !it.isBefore(created) }
            val planned = if (habit.scheduleMode.equals("INTERVAL", ignoreCase = true)) null
            else days.count { HabitScheduler.isScheduledOn(habit, it, null) }
            val done = doneDays[habit.id] ?: 0
            if (done == 0 && (planned == null || planned == 0)) null else HabitWeek(habit.title, done, planned)
        }
    }

    /** Each day's average first, so a day logged five times weighs as much as a day logged once. */
    fun mood(week: List<MoodEntity>, previous: List<MoodEntity>): MoodWeek? {
        val average = dailyAverage(week) ?: return null
        return MoodWeek(average, week.map { it.date }.distinct().size, dailyAverage(previous))
    }

    private fun dailyAverage(entries: List<MoodEntity>): Double? =
        if (entries.isEmpty()) null
        else entries.groupBy { it.date }.values.map { day -> day.map { it.score }.average() }.average()

    fun health(days: List<DayHealth>): HealthWeek? {
        val nights = days.map { it.sleepMinutes }.filter { it > 0 }
        val steps = days.map { it.steps }.filter { it > 0 }
        if (nights.isEmpty() && steps.isEmpty()) return null
        return HealthWeek(
            sleepMinutesAverage = nights.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            nights = nights.size,
            stepsAverage = steps.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            stepDays = steps.size
        )
    }

    /** Done and total tasks over the week's boards. */
    fun tasks(days: List<List<DailyTaskEntity>>): Pair<Int, Int> = days.flatten().let { all -> all.count { it.isDone } to all.size }

    /** The chores last done inside the week, in that order (a chore keeps only its last-done date). */
    fun chores(chores: List<ChoreEntity>, week: ReviewWeek): List<String> {
        val range = week.monday..week.sunday
        return chores.filter { !it.archived }
            .mapNotNull { chore ->
                val last = try {
                    LocalDate.parse(chore.lastDoneDate.take(10))
                } catch (e: Exception) {
                    null
                }
                last?.takeIf { it in range }?.let { chore to it }
            }
            .sortedBy { it.second }
            .map { it.first.name }
    }

    /** XP gained inside the week; null when the history holds nothing at all. */
    fun xp(history: List<XpGainEntry>, week: ReviewWeek, zone: ZoneId): Int? {
        if (history.isEmpty()) return null
        val from = week.monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = week.monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        return history.filter { it.timestamp in from until to }.sumOf { it.amount }.roundToInt()
    }

    /**
     * The notes created and edited inside the week, newest first, by git's author time. [keep] drops
     * what the caller knows is gone or generated (a deleted note, a mirrored TODO).
     */
    fun notes(
        history: FileHistory,
        week: ReviewWeek,
        zone: ZoneId,
        isWork: (String) -> Boolean,
        keep: (String) -> Boolean = { true }
    ): NoteActivity {
        val from = week.monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = week.monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        fun inWeek(at: Long?) = at != null && at >= from && at < to
        val paths = candidates(history, week, zone).filter(keep)
        val created = paths.filter { inWeek(history.firstSeen[it]) }.sortedByDescending { history.firstSeen[it] }
        val createdSet = created.toSet()
        val edited = paths.filter { it !in createdSet && inWeek(history.lastChanged[it]) }
            .sortedByDescending { history.lastChanged[it] }
        val (workCreated, personalCreated) = created.partition(isWork)
        val (workEdited, personalEdited) = edited.partition(isWork)
        return NoteActivity(personalCreated.map(::link), personalEdited.map(::link), workCreated.size, workEdited.size)
    }

    /** The notes first seen or last changed inside the week, before the caller's own checks. */
    fun candidates(history: FileHistory, week: ReviewWeek, zone: ZoneId): List<String> {
        val from = week.monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = week.monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        fun inWeek(at: Long?) = at != null && at >= from && at < to
        return (history.firstSeen.keys + history.lastChanged.keys).distinct()
            .filter { it.endsWith(".md") && !excluded(it) && (inWeek(history.firstSeen[it]) || inWeek(history.lastChanged[it])) }
    }

    /** Daily and weekly notes, data, system, private and People notes, mirrors, worklogs and hand-offs. */
    fun excluded(path: String): Boolean =
        VaultSchema.isDailyNote(path) ||
            ReviewWeek.isReview(path) ||
            EXCLUDED_PREFIXES.any { path.startsWith(it) } ||
            MirrorNotes.isWorklog(path) ||
            MirrorNotes.isClaudeMemory(path) ||
            (path.startsWith(ProjectFormat.PROJECTS_FOLDER + "/") && path.contains("/Repo/")) ||
            DeskHandoffFormat.isHandoff(path)

    fun link(path: String): String = "[[" + path.removeSuffix(".md") + "]]"

    fun work(
        entries: List<LogEntry>,
        objectivesMoved: Int,
        objectivesStalled: Int,
        meetings: Int,
        overload: OverloadSignal?,
        logLinks: List<String>
    ): WorkWeek = WorkWeek(
        entries = entries.groupingBy { it.kind }.eachCount(),
        decisionsWithoutRecord = entries.count { it.kind == LogKind.DECISION && it.reference?.contains(ADR_LINK) != true },
        objectivesMoved = objectivesMoved,
        objectivesStalled = objectivesStalled,
        meetings = meetings,
        overload = overload,
        logLinks = logLinks
    )
}

/**
 * The weekly review note (D6), in French like the vault's other generated notes: the measured week,
 * last week's plan, three prompts and an empty "Semaine prochaine" the owner fills in on Monday. It
 * is written once and never rewritten; [planLines] reads the owner's plan back.
 */
object WeeklyReviewFormat {

    const val PLAN_HEADING = "## 🧭 Semaine prochaine"
    const val LAST_PLAN_HEADING = "## ↩️ Le plan de la semaine dernière"
    const val TAG = "revue-hebdo"
    private const val NBSP = ' '
    private const val LISTED = 10
    private val PLACEHOLDERS = setOf("-", "*", "+", "- [ ]", "* [ ]", "-  [ ]")
    private val MONTHS = listOf(
        "janvier", "février", "mars", "avril", "mai", "juin",
        "juillet", "août", "septembre", "octobre", "novembre", "décembre"
    )

    fun title(week: ReviewWeek): String = "Semaine ${week.week} — " + range(week.monday, week.sunday)

    fun render(week: ReviewWeek, facts: WeeklyFacts): String = buildString {
        val title = title(week)
        append(VaultSchema.frontmatter(title = title, path = week.path, date = week.sunday, tags = listOf(TAG), extra = listOf("week" to week.key)))
        append('\n').append("# ").append(title).append("\n\n")

        section("🔁 Habitudes", facts.habits.map { habit ->
            "- ${habit.title} : " + if (habit.planned != null && habit.planned > 0) {
                "${habit.done}/${habit.planned} " + if (habit.planned == 1) "jour" else "jours"
            } else {
                "${habit.done} fois"
            }
        })
        section("🙂 Humeur", listOfNotNull(facts.mood?.let { mood ->
            "- Moyenne : ${decimal(mood.average)}/5 sur ${count(mood.days, "jour", "jours")}" +
                (mood.previousAverage?.let { " (semaine précédente : ${decimal(it)})" } ?: "")
        }))
        section("🛌 Sommeil et pas", facts.health?.let { health ->
            listOfNotNull(
                health.sleepMinutesAverage?.let { "- Sommeil moyen : ${duration(it)} (${count(health.nights, "nuit", "nuits")})" },
                health.stepsAverage?.let { "- Pas : ${number(it)} par jour (${count(health.stepDays, "jour", "jours")})" }
            )
        }.orEmpty())
        section("✅ Tâches et corvées", listOfNotNull(
            facts.tasksTotal.takeIf { it > 0 }?.let { "- Tâches faites : ${facts.tasksDone} sur $it" },
            facts.choresDone.takeIf { it.isNotEmpty() }?.let { chores ->
                "- Corvées faites : ${chores.size} (" + chores.joinToString(", ") { it.replace('\n', ' ').trim() } + ")"
            }
        ))
        section("⭐ XP", listOfNotNull(facts.xpGained?.takeIf { it > 0 }?.let { xp ->
            "- +${number(xp)} XP" + (facts.level?.let { " · niveau $it" } ?: "")
        }))
        section("📝 Notes", facts.notes?.let { notes ->
            listOfNotNull(
                notes.created.takeIf { it.isNotEmpty() }?.let { "- Créées (${it.size}) : " + listed(it) },
                notes.edited.takeIf { it.isNotEmpty() }?.let { "- Modifiées (${it.size}) : " + listed(it) },
                listOfNotNull(
                    notes.workCreated.takeIf { it > 0 }?.let { count(it, "créée", "créées") },
                    notes.workEdited.takeIf { it > 0 }?.let { count(it, "modifiée", "modifiées") }
                ).takeIf { it.isNotEmpty() }?.let { "- Dans les dossiers de travail : " + it.joinToString(", ") }
            )
        }.orEmpty())
        section("💼 Travail", facts.work?.takeIf { !it.isEmpty }?.let(::workLines).orEmpty())
        if (facts.lastWeekPlan.isNotEmpty()) {
            append(LAST_PLAN_HEADING).append("\n\n")
            facts.lastWeekPlan.forEach { append(it).append('\n') }
            append('\n')
        }
        section("🪞 Réflexion", listOf("- Ce qui a marché :", "- Ce qui a coincé :", "- Ce que je change :"))
        append(PLAN_HEADING).append("\n\n").append("-\n")
    }

    /** The owner's lines under "Semaine prochaine", as written; the empty placeholder isn't a plan. */
    fun planLines(content: String): List<String> {
        val lines = content.replace("\r\n", "\n").split('\n')
        val start = lines.indexOfFirst { it.trimEnd() == PLAN_HEADING }
        if (start < 0) return emptyList()
        return lines.drop(start + 1)
            .takeWhile { !it.startsWith("## ") && !it.startsWith("# ") }
            .map { it.trimEnd() }
            .filter { it.isNotBlank() && it.trim() !in PLACEHOLDERS }
    }

    private fun workLines(work: WorkWeek): List<String> = listOfNotNull(
        work.entries.takeIf { it.isNotEmpty() }?.let { entries ->
            val total = entries.values.sum()
            val parts = LogKind.entries.mapNotNull { kind -> entries[kind]?.let { kindCount(kind, it) } }
            "- Journal de mission : " + count(total, "entrée", "entrées") + " (" + parts.joinToString(", ") + ")" +
                work.logLinks.joinToString("") { " · $it" }
        },
        work.decisionsWithoutRecord.takeIf { it > 0 }?.let { "- Décisions sans ADR : $it" },
        if (work.objectivesMoved + work.objectivesStalled > 0) {
            "- Objectifs : " + listOfNotNull(
                work.objectivesMoved.takeIf { it > 0 }?.let { "$it " + if (it == 1) "a avancé" else "ont avancé" },
                work.objectivesStalled.takeIf { it > 0 }?.let { "$it au point mort" }
            ).joinToString(", ")
        } else {
            null
        },
        work.meetings.takeIf { it > 0 }?.let { "- Réunions : $it" },
        work.overload?.let { signal ->
            "- Surcharge : charge +${signal.loadIncreasePercent}$NBSP% (${hours(signal.weekMinutes)} contre " +
                "${hours(signal.usualWeekMinutes)} habituellement)"
        }
    )

    private fun kindCount(kind: LogKind, n: Int): String {
        val word = kind.word
        val plural = if (n > 1 && !word.endsWith("s") && kind != LogKind.OFF_TICKET) word + "s" else word
        return "$n $plural"
    }

    private fun StringBuilder.section(heading: String, lines: List<String>) {
        if (lines.isEmpty()) return
        append("## ").append(heading).append("\n\n")
        lines.forEach { append(it).append('\n') }
        append('\n')
    }

    private fun listed(links: List<String>): String {
        val shown = links.take(LISTED).joinToString(", ")
        val more = links.size - LISTED
        return if (more > 0) "$shown et $more " + if (more == 1) "autre" else "autres" else shown
    }

    private fun range(from: LocalDate, to: LocalDate): String = when {
        from.year != to.year -> "du ${day(from)} ${month(from)} ${from.year} au ${day(to)} ${month(to)} ${to.year}"
        from.month != to.month -> "du ${day(from)} ${month(from)} au ${day(to)} ${month(to)} ${to.year}"
        else -> "du ${day(from)} au ${day(to)} ${month(to)} ${to.year}"
    }

    private fun day(date: LocalDate) = if (date.dayOfMonth == 1) "1er" else date.dayOfMonth.toString()
    private fun month(date: LocalDate) = MONTHS[date.monthValue - 1]
    /** French takes the plural from 2 on: "0 jour", "1 jour", "2 jours". */
    private fun count(n: Int, singular: String, plural: String) = "$n " + if (n < 2) singular else plural
    private fun hours(minutes: Int) = "${(minutes / 60.0).roundToInt()} h"

    /** "7 h 05". */
    fun duration(minutes: Int): String = "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}"

    /** "8 412", grouped by hand with a no-break space: the JVM and Android's ICU disagree on French grouping. */
    fun number(value: Int): String {
        val digits = kotlin.math.abs(value).toString()
        val grouped = digits.reversed().chunked(3).joinToString(NBSP.toString()).reversed()
        return if (value < 0) "-$grouped" else grouped
    }

    /** "3,8": one decimal, a comma. */
    fun decimal(value: Double): String {
        val tenths = (value * 10).roundToInt()
        return "${tenths / 10},${kotlin.math.abs(tenths % 10)}"
    }
}
