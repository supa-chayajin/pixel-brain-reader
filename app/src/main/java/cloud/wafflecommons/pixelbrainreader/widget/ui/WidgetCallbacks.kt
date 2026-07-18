package cloud.wafflecommons.pixelbrainreader.widget.ui

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute
import cloud.wafflecommons.pixelbrainreader.data.model.HabitLogEntry
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodEntry
import cloud.wafflecommons.pixelbrainreader.domain.gamification.XpActionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import java.time.LocalDate
import java.time.LocalTime

/** Typed keys carried from a widget button into its [ActionCallback]. */
object WidgetKeys {
    val HABIT_ID = ActionParameters.Key<String>("habit_id")
    val HABIT_TARGET_DONE = ActionParameters.Key<Boolean>("habit_target_done")
    val TASK_ID = ActionParameters.Key<String>("task_id")
    val TASK_TARGET_DONE = ActionParameters.Key<Boolean>("task_target_done")
    val CHORE_ID = ActionParameters.Key<String>("chore_id")
    val CHORE_EFFORT = ActionParameters.Key<Int>("chore_effort")
    val MOOD_SCORE = ActionParameters.Key<Int>("mood_score")
}

/**
 * Runs a widget mutation with uniform guards:
 *  - rethrow [CancellationException] (cooperative cancellation) but log-and-swallow every other
 *    exception, so a failing DB/vault write never escapes the Glance action broadcast (which runs in
 *    the app process and would otherwise fail silently or crash it);
 *  - on success, instantly re-render the tapped (live-read) widget and enqueue a full snapshot
 *    rebuild so the aggregate widgets (Companion rings, Health) catch up.
 */
private suspend fun runWidgetAction(context: Context, refreshLive: suspend () -> Unit, mutate: suspend () -> Unit) {
    try {
        mutate()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("WidgetCallback", "Widget action failed", e)
        return
    }
    runCatching { refreshLive() }
    runCatching { WidgetLiveData.entryPoint(context).widgetUpdateManager().scheduleSnapshotUpdate() }
}

private fun moodLabel(score: Int): String = when (score) {
    1 -> "Awful"; 2 -> "Bad"; 3 -> "Okay"; 4 -> "Good"; else -> "Great"
}

/** Logs a one-tap mood entry (score 1..5) for right now, awards MOOD_LOGGED XP. */
class MoodLogCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val score = parameters[WidgetKeys.MOOD_SCORE] ?: return
        val ep = WidgetLiveData.entryPoint(context)
        val today = LocalDate.now()
        val now = LocalTime.now()
        val time = String.format("%02d:%02d", now.hour, now.minute)
        runWidgetAction(context, refreshLive = { MoodWidget().updateAll(context) }) {
            // Skip an accidental same-minute, same-score double tap (and its double XP) — multiple
            // genuinely different check-ins a day are still allowed.
            val dup = ep.moodRepository().getDailyMood(today).firstOrNull()
                ?.entries?.any { it.time == time && it.score == score } == true
            if (!dup) {
                ep.moodRepository().addEntry(
                    today,
                    MoodEntry(time = time, score = score, label = moodLabel(score), activities = emptyList(), note = null)
                )
                runCatching { ep.grantXpUseCase().execute("mood_widget", XpActionType.MOOD_LOGGED) }
            }
        }
    }
}

/** Toggles a habit's completion for today. Awards HABIT_DONE XP only on a real not-done → done flip. */
class HabitToggleCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val habitId = parameters[WidgetKeys.HABIT_ID] ?: return
        val targetDone = parameters[WidgetKeys.HABIT_TARGET_DONE] ?: true
        val ep = WidgetLiveData.entryPoint(context)
        val today = LocalDate.now()
        // Defensive: automatic (Health-Connect) habits are read-only. The widget row no longer
        // sends a toggle for them, but a stale/replayed intent must not slip past this guard.
        val isAutomatic = runCatching {
            ep.habitRepository().getHabitConfigs().find { it.id == habitId }?.autoSource?.isNotBlank() == true
        }.getOrDefault(false)
        if (isAutomatic) return
        runWidgetAction(context, refreshLive = { HabitsWidget().updateAll(context) }) {
            // Read the live state so a stale baked parameter (or a double tap) can't double-grant XP.
            val alreadyCompleted = ep.habitRepository().getLogsForYear(today.year)[habitId].orEmpty()
                .any { it.date == today.toString() && it.status == HabitStatus.COMPLETED }
            ep.habitRepository().logHabit(
                today,
                HabitLogEntry(
                    habitId = habitId,
                    date = today.toString(),
                    value = if (targetDone) 1.0 else 0.0,
                    status = if (targetDone) HabitStatus.COMPLETED else HabitStatus.SKIPPED
                )
            )
            if (targetDone && !alreadyCompleted) {
                runCatching { ep.grantXpUseCase().execute(habitId, XpActionType.HABIT_DONE) }
            }
        }
    }
}

/** Toggles a daily-dashboard task's done state. */
class TaskToggleCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val taskId = parameters[WidgetKeys.TASK_ID] ?: return
        val targetDone = parameters[WidgetKeys.TASK_TARGET_DONE] ?: true
        val ep = WidgetLiveData.entryPoint(context)
        runWidgetAction(context, refreshLive = { TodayWidget().updateAll(context) }) {
            ep.dailyDashboardRepository().toggleTask(taskId, targetDone)
        }
    }
}

/** Marks a chore done today (stamps lastDoneDate) and awards its effort as XP — once per day. */
class ChoreDoneCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val choreId = parameters[WidgetKeys.CHORE_ID] ?: return
        val effort = parameters[WidgetKeys.CHORE_EFFORT] ?: 0
        val ep = WidgetLiveData.entryPoint(context)
        val today = LocalDate.now()
        runWidgetAction(context, refreshLive = { ChoresWidget().updateAll(context) }) {
            // Only award XP if the chore wasn't already marked done today (guards repeat taps).
            val alreadyDoneToday = ep.choreRepository().getAllChoresStream().firstOrNull().orEmpty()
                .find { it.id == choreId }?.lastDoneDate == today.toString()
            ep.choreRepository().updateLastDoneDate(choreId, today.toString())
            if (effort > 0 && !alreadyDoneToday) {
                runCatching { ep.grantXpUseCase().executeCustom(Attribute.END, effort.toDouble(), "Chore (widget)") }
            }
        }
    }
}
