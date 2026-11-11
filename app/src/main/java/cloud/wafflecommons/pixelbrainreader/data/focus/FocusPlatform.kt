package cloud.wafflecommons.pixelbrainreader.data.focus

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import cloud.wafflecommons.pixelbrainreader.R
import cloud.wafflecommons.pixelbrainreader.data.workers.FocusWorker
import cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetNav
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The focus session's Android side (D9): the Live Update (an ongoing notification asking for
 * promotion, `POST_PROMOTED_NOTIFICATIONS`; platform APIs only, minSdk 36), the done notification,
 * and the WorkManager chain that steps the bar. Kept apart so [FocusRepository] stays testable.
 */
@Singleton
class FocusPlatform @Inject constructor(@param:ApplicationContext private val context: Context) {

    fun postRunning(session: FocusSession, now: Long) {
        if (!allowed()) return
        val manager = manager() ?: return
        val end = LocalTime.ofInstant(Instant.ofEpochMilli(session.endMillis), ZoneId.systemDefault())
        val spec = FocusSpec.running(session, now, end.format(DateTimeFormatter.ofPattern("HH:mm")))
        val style = Notification.ProgressStyle()
            .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100)))
            .setProgress(spec.progress)
            .setStyledByProgress(true)
        val builder = Notification.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setOngoing(spec.ongoing)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(spec.countdownTo)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setStyle(style)
            .setContentIntent(openDaily())
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_notification), "Stop", broadcast(ACTION_STOP, 31))
                    .build()
            )
        // Promotion arrived in Android 16 QPR2 (36.1); on 36.0 the session stays an ordinary ongoing notification.
        if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
            builder.setRequestPromotedOngoing(spec.promoted)
        }
        manager.notify(ID_RUNNING, builder.build())
    }

    fun cancelRunning() {
        manager()?.cancel(ID_RUNNING)
    }

    /** "Focus done · 3 notes edited"; [canLog] (Work mode) adds "Log it". */
    fun postDone(minutes: Int, notes: List<String>, canLog: Boolean) {
        if (!allowed()) return
        val manager = manager() ?: return
        val builder = Notification.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(FocusSpec.doneTitle(notes.size))
            .setContentText("$minutes minutes of focus: XP earned.")
            .setAutoCancel(true)
            .setContentIntent(openDaily())
        if (canLog) {
            val log = Intent(context, FocusReceiver::class.java)
                .setAction(ACTION_LOG)
                .putExtra(EXTRA_MINUTES, minutes)
                .putExtra(EXTRA_NOTES, notes.toTypedArray())
            val pending = PendingIntent.getBroadcast(context, 32, log, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_notification), "Log it", pending).build())
        }
        manager.notify(ID_DONE, builder.build())
    }

    fun cancelDone() {
        manager()?.cancel(ID_DONE)
    }

    /** The next step in [delayMs]: one unique chain, so a new session replaces the old one's steps. */
    fun schedule(delayMs: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<FocusWorker>().setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS).build()
        )
    }

    fun cancelSchedule() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
    }

    /** POST_NOTIFICATIONS is a runtime permission: without it nothing is posted (the session still runs). */
    private fun allowed(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Two channels: the running session is quiet (low importance, still promotable), its end alerts. */
    private fun manager(): NotificationManager? {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNNING, "Focus session", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The running focus session and its countdown"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Focus session ends", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a focus session ends"
            }
        )
        return manager
    }

    private fun openDaily(): PendingIntent =
        PendingIntent.getActivity(
            context, 30, WidgetNav.openIntent(context, WidgetNav.SCREEN_DAILY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun broadcast(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode, Intent(context, FocusReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        const val CHANNEL_RUNNING = "focus_running"
        const val CHANNEL_DONE = "focus_done"
        const val UNIQUE_WORK = "focus_session_step"
        const val ACTION_STOP = "cloud.wafflecommons.pixelbrainreader.focus.STOP"
        const val ACTION_LOG = "cloud.wafflecommons.pixelbrainreader.focus.LOG"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_NOTES = "notes"
        private const val ID_RUNNING = 2010
        private const val ID_DONE = 2011
    }
}
