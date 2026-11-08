package cloud.wafflecommons.pixelbrainreader.data.notifications

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import cloud.wafflecommons.pixelbrainreader.data.capture.CheckInRouting
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyNoteRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodEntry
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.ScratchRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.TaskRepository
import cloud.wafflecommons.pixelbrainreader.di.ApplicationScope
import cloud.wafflecommons.pixelbrainreader.domain.gamification.GrantXpUseCase
import cloud.wafflecommons.pixelbrainreader.domain.gamification.XpActionType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import javax.inject.Inject

/**
 * Answers the evening check-in notification without opening the app: a mood tap logs the mood
 * and asks for one line; a reply files that line ([CheckInRouting]). Not exported — only our own
 * notification's PendingIntents reach it. Writes go through the same repositories as the app
 * (each requests its own batched push).
 */
@AndroidEntryPoint
class CheckInReceiver : BroadcastReceiver() {

    @Inject lateinit var moodRepository: MoodRepository
    @Inject lateinit var dashboardRepository: DailyDashboardRepository
    @Inject lateinit var dailyNoteRepository: DailyNoteRepository
    @Inject lateinit var taskRepository: TaskRepository
    @Inject lateinit var scratchRepository: ScratchRepository
    @Inject lateinit var grantXpUseCase: GrantXpUseCase
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (VaultWriteGate.readOnly) return // a read-only device logs nothing
        val pending = goAsync()
        val app = context.applicationContext
        appScope.launch {
            try {
                when (intent.action) {
                    ACTION_MOOD -> logMood(app, intent.getIntExtra(EXTRA_SCORE, 0))
                    ACTION_REPLY -> reply(app, intent, forceGratitude = false)
                    ACTION_GRATITUDE -> reply(app, intent, forceGratitude = true)
                    ACTION_DONE -> NotificationHelper.cancelCheckIn(app)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Check-in action failed", e)
                NotificationHelper.postCheckInDone(app, "Couldn't save that. Open the app to add it.")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun logMood(context: Context, score: Int) {
        val mood = CheckInRouting.MOODS.firstOrNull { it.first == score } ?: return
        val now = LocalTime.now()
        val time = String.format(Locale.ROOT, "%02d:%02d", now.hour, now.minute)
        moodRepository.addEntry(
            LocalDate.now(),
            MoodEntry(time = time, score = score, label = mood.second, activities = emptyList(), note = null)
        )
        runCatching { grantXpUseCase.execute("mood_checkin", XpActionType.MOOD_LOGGED) }
        NotificationHelper.postCheckInLine(context, moodLabel = mood.third)
    }

    private suspend fun reply(context: Context, intent: Intent, forceGratitude: Boolean) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString().orEmpty()
        val routed = CheckInRouting.route(text, forceGratitude)
        if (routed == null) {
            NotificationHelper.cancelCheckIn(context)
            return
        }
        val today = LocalDate.now()
        val message = when (routed.destination) {
            CheckInRouting.Destination.MOMENT -> {
                dashboardRepository.addTimelineEntry(today, routed.text, routed.guess.time ?: LocalTime.now().withSecond(0).withNano(0))
                "Added to today's timeline."
            }
            CheckInRouting.Destination.GRATITUDE -> {
                dailyNoteRepository.addGratitude(today, routed.text)
                "Added to today's gratitude."
            }
            CheckInRouting.Destination.TASK_TOMORROW -> {
                taskRepository.addTask(routed.text, today.plusDays(1), routed.guess.time)
                "Added to tomorrow's tasks."
            }
            CheckInRouting.Destination.SCRAP -> {
                scratchRepository.saveScrap(routed.text, 0xFF000000.toInt())
                "Added to the scratchpad."
            }
        }
        NotificationHelper.postCheckInDone(context, message)
    }

    companion object {
        private const val TAG = "CheckInReceiver"
        const val ACTION_MOOD = "cloud.wafflecommons.pixelbrainreader.checkin.MOOD"
        const val ACTION_REPLY = "cloud.wafflecommons.pixelbrainreader.checkin.REPLY"
        const val ACTION_GRATITUDE = "cloud.wafflecommons.pixelbrainreader.checkin.GRATITUDE"
        const val ACTION_DONE = "cloud.wafflecommons.pixelbrainreader.checkin.DONE"
        const val EXTRA_SCORE = "score"
        const val KEY_TEXT = "checkin_text"
    }
}
