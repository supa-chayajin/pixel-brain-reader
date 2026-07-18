package cloud.wafflecommons.pixelbrainreader.widget.ui

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
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
    data class WidgetMood(val emoji: String, val entryCount: Int)

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
            configs.mapNotNull { c ->
                val logs = logsByHabit[c.id].orEmpty()
                val lastCompleted = (logs + prevYearLogs[c.id].orEmpty()).asSequence()
                    .filter { it.status == HabitStatus.COMPLETED }
                    .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
                    .maxOrNull()
                if (!HabitScheduler.isScheduledOn(c, today, lastCompleted)) return@mapNotNull null
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

    /** Today's mood summary (main emoji + entry count). */
    suspend fun moodToday(context: Context): WidgetMood {
        return try {
            val ep = entryPoint(context)
            val daily = ep.moodRepository().getDailyMood(LocalDate.now()).firstOrNull()
            WidgetMood(daily?.summary?.mainEmoji ?: "🫥", daily?.entries?.size ?: 0)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { WidgetMood("🫥", 0) }
    }
}
