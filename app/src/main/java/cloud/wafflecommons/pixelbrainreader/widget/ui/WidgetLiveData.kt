package cloud.wafflecommons.pixelbrainreader.widget.ui

import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
import cloud.wafflecommons.pixelbrainreader.data.mood.MoodEmojiSets
import cloud.wafflecommons.pixelbrainreader.di.WidgetEntryPoint
import cloud.wafflecommons.pixelbrainreader.domain.lifeos.HabitScheduler
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import java.time.LocalDate

/**
 * Live reads for the INTERACTIVE widgets (Habits / Today / Chores / Mood). Unlike the glanceable
 * widgets these do not go through the snapshot file — they hit the repositories directly in
 * `provideGlance` so a quick-action tap is reflected the instant the widget re-renders.
 *
 * All functions are `suspend` and safe to call from a Glance coroutine; each swallows failures into
 * an empty result so a widget never crashes its host.
 */
object WidgetLiveData {

    fun entryPoint(context: Context): WidgetEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

    data class WidgetHabit(val id: String, val title: String, val done: Boolean, val isAutomatic: Boolean = false)
    data class WidgetTask(val id: String, val label: String, val time: String?, val done: Boolean)
    data class WidgetChore(val id: String, val name: String, val effort: Int)
    /** [faces]: the device's mood emoji set, score 1 to 5 (the buttons). */
    data class WidgetMood(
        val emoji: String,
        val entryCount: Int,
        val hidden: Boolean = false,
        val faces: List<String> = MoodEmojiSets.DEFAULT.faces
    )

    /** Today's SCHEDULED habits (via [HabitScheduler]) with their done state, ordered by sortOrder. */
    suspend fun habitsForToday(context: Context): List<WidgetHabit> {
        return try {
            val ep = entryPoint(context)
            val repo = ep.habitRepository()
            val today = LocalDate.now()
            val configs = repo.getHabitConfigs().filter { !it.archived }.sortedBy { it.sortOrder }
            val logsByHabit = repo.getLogsForYear(today.year)
            // INTERVAL habits need the last completion even if it fell in December — include last year
            // so a habit completed on Dec 31 isn't wrongly shown as "due" on Jan 2 (matches the app).
            val prevYearLogs = repo.getLogsForYear(today.year - 1)
            val todayIso = today.toString()
            // The custody calendar from disk: a process the widget started still holds the default.
            val custody = ep.modesRepository().currentConfig().custody
            configs.mapNotNull { c ->
                val logs = logsByHabit[c.id].orEmpty()
                val lastCompleted = (logs + prevYearLogs[c.id].orEmpty()).asSequence()
                    .filter { it.status == HabitStatus.COMPLETED }
                    .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
                    .maxOrNull()
                if (!HabitScheduler.isScheduledOn(c, today, lastCompleted, custody)) return@mapNotNull null
                val todayLog = logs.find { it.date == todayIso }
                val done = todayLog != null && repo.calculateCompletion(todayLog.value, c.targetValue, c.type)
                // Automatic (Health-Connect) habits are read-only in the widget too.
                WidgetHabit(c.id, c.title, done, isAutomatic = !c.autoSource.isNullOrBlank())
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }

    /** Today's dashboard tasks (open first, then done), as exposed by the daily dashboard. */
    suspend fun tasksForToday(context: Context): List<WidgetTask> {
        return try {
            val ep = entryPoint(context)
            val tasks = ep.dailyDashboardRepository().getLiveTasks(LocalDate.now()).firstOrNull().orEmpty()
            tasks.map { WidgetTask(it.id, it.label, it.scheduledTime, it.isDone) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }

    data class WidgetProject(val title: String, val line: String, val next: String?)

    /** The widget's rows, and its line when there are none (Off shows no project). */
    data class WidgetProjectList(val projects: List<WidgetProject>, val emptyLine: String)

    /**
     * The Projects widget's list (C11): the Projects page's — the pinned first, then the most
     * recently active, hidden ones left out — under the page's names, at most [limit]. The widget
     * shows as many as its height allows (`ProjectsWidgetLayout`).
     */
    /**
     * Today's first echo for the Echo widget (K6): a spaced one due first, else a forgotten one;
     * null when there is none, or on any failure.
     */
    suspend fun echoOfTheDay(context: Context): cloud.wafflecommons.pixelbrainreader.data.model.MemoryEcho? = try {
        val ep = entryPoint(context)
        // The mode from disk: a process the widget started still holds the default (every folder).
        ep.modesRepository().reload()
        ep.memoryEchoRepository().getEchoesForToday(forgottenLimit = 1, spacedLimit = 1).firstOrNull()
    } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    /**
     * The Toolkit widget's day (E17). [shown] false outside Work mode (or while sharing the screen),
     * when only [workLabel], the user's name for that mode, is shown.
     */
    data class ToolkitDay(
        val workLabel: String,
        val shown: Boolean,
        val logToday: Int? = null,
        val logLocked: Boolean = false,
        val nextMeeting: String? = null,
        val overload: String? = null
    )

    suspend fun toolkitDay(context: Context): ToolkitDay = try {
        val ep = entryPoint(context)
        // The mode from disk: a process the widget started still holds the default.
        val visibility = ep.modesRepository().currentVisibility()
        val workLabel = visibility.label(ContextMode.WORK)
        if (visibility.workContextHidden) {
            ToolkitDay(workLabel, shown = false)
        } else {
            val today = java.time.LocalDate.now()
            var locked = false
            val logToday = try {
                ep.missionLogRepository().entriesBetween(today, today).size
            } catch (e: cloud.wafflecommons.pixelbrainreader.data.privatevault.PrivateVaultLockedException) {
                locked = true
                null
            }
            val now = java.time.LocalTime.now()
            val meeting = ep.dailyDashboardDao().getTimelineSnapshot(today)
                .filter { it.googleEventId != null && it.time.isAfter(now) }
                .minByOrNull { it.time }
                ?.let { entry ->
                    entry.time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) + " · " +
                        cloud.wafflecommons.pixelbrainreader.data.meetings.MeetingPrep.topic(entry.content)
                }
            val overload = ep.loadRecoveryRepository().report().overload?.let { "+${it.loadIncreasePercent} % load this week" }
            ToolkitDay(workLabel, shown = true, logToday = logToday, logLocked = locked, nextMeeting = meeting, overload = overload)
        }
    } catch (e: CancellationException) { throw e } catch (e: Exception) { ToolkitDay("Work", shown = false) }

    suspend fun activeProjects(context: Context, limit: Int): WidgetProjectList = try {
        val ep = entryPoint(context)
        // The mode from disk: a process the widget started still holds the default (every project).
        val visibility = ep.modesRepository().currentVisibility()
        val projects = ep.projectArrangementRepository().read().arrange(ep.projectRepository().shownProjects(visibility)).shown.take(limit).map { p ->
            val activity = buildList {
                if (p.commits > 0) add("${p.commits} commit${if (p.commits == 1) "" else "s"}")
                if (p.sessions > 0) add("${p.sessions} AI")
            }.joinToString(" · ").ifEmpty { "quiet" } + " · 14 d"
            WidgetProject(cloud.wafflecommons.pixelbrainreader.data.work.projectDisplayName(p.title), activity, p.nextAction?.text)
        }
        val empty = if (visibility.mode == ContextMode.OFF) "No projects in ${visibility.label()}" else "No project to show"
        WidgetProjectList(projects, empty)
    } catch (e: CancellationException) { throw e } catch (e: Exception) { WidgetProjectList(emptyList(), "No project to show") }

    /** Chores that are due today (lastDone + frequency ≤ today), non-archived, by sortOrder. */
    suspend fun choresDue(context: Context): List<WidgetChore> {
        return try {
            val ep = entryPoint(context)
            val today = LocalDate.now()
            ep.choreRepository().getAllChoresStream().firstOrNull().orEmpty()
                .filter { !it.archived }
                .filter { chore ->
                    val last = runCatching { LocalDate.parse(chore.lastDoneDate) }.getOrNull()
                    // Coerce frequency to ≥1 so a legacy chore with frequencyDays==0 still drops off
                    // after being done today (else it stays "due" and can be tapped for repeat XP).
                    last == null || !last.plusDays(chore.frequencyDays.coerceAtLeast(1).toLong()).isAfter(today)
                }
                .sortedBy { it.sortOrder }
                .map { WidgetChore(it.id, it.name, it.baseEffort) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }

    /**
     * Today's mood summary (main emoji + entry count) and the faces of the device's emoji set;
     * no summary while screen-share safe is on.
     */
    suspend fun moodToday(context: Context): WidgetMood {
        return try {
            val ep = entryPoint(context)
            val faces = (ep.gamificationPreferences().moodEmojiSetFlow.firstOrNull() ?: MoodEmojiSets.DEFAULT).faces
            if (ep.modesRepository().currentVisibility().hideHealth) return WidgetMood("🙈", 0, hidden = true, faces = faces)
            val daily = ep.moodRepository().getDailyMood(LocalDate.now()).firstOrNull()
            WidgetMood(daily?.summary?.mainEmoji ?: "🫥", daily?.entries?.size ?: 0, faces = faces)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { WidgetMood("🫥", 0) }
    }
}
