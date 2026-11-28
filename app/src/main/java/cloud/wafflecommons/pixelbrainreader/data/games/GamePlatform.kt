package cloud.wafflecommons.pixelbrainreader.data.games

import android.Manifest
import android.app.AutomaticZenRule
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import cloud.wafflecommons.pixelbrainreader.MainActivity
import cloud.wafflecommons.pixelbrainreader.R
import cloud.wafflecommons.pixelbrainreader.data.workers.GameSessionCheckWorker
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
 * Games' Android side (RFC-009 §4.3, §4.5): the running session as a Live Update counting up (the
 * 12.14 focus session's permission, `POST_PROMOTED_NOTIFICATIONS`), and the "Game night" mode, an
 * `AutomaticZenRule` the app owns (D13): Android lists it in Settings › Modes, where the owner sets
 * its exceptions, and turning it off never touches the owner's own Do Not Disturb. Kept apart so
 * [GameSessionController] stays testable.
 */
@Singleton
class GamePlatform @Inject constructor(@param:ApplicationContext private val context: Context) {

    /**
     * The running session's Live Update; [hasMap] adds "Open the map" (13.2: the phone as a second
     * screen); [largeIcon] is the game's picture (13.4, P1), shown as it is (never a tint from it).
     */
    fun postRunning(session: RunningGame, title: String, hasMap: Boolean = false, largeIcon: Bitmap? = null) {
        if (!notificationsAllowed()) return
        val manager = manager() ?: return
        val since = LocalTime.ofInstant(Instant.ofEpochMilli(session.startMillis), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
        val builder = Notification.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("🎮 $title")
            .setContentText("Playing since $since")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setWhen(session.startMillis)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(openGames())
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_notification), "Stop", stopIntent()).build()
            )
        largeIcon?.let { builder.setLargeIcon(Icon.createWithBitmap(it)) }
        if (hasMap) {
            builder.addAction(
                Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_notification), "Open the map", openMap()).build()
            )
        }
        // Promotion arrived in Android 16 QPR2 (36.1); on 36.0 the session stays an ordinary ongoing notification.
        if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
            builder.setRequestPromotedOngoing(true)
        }
        manager.notify(ID_RUNNING, builder.build())
    }

    /** Safe to repeat (13.4: a return to the app with no session calls it); a cancel creates no channel. */
    fun cancelRunning() {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID_RUNNING)
    }

    /**
     * "Still playing?" (13.1): an alerting notification beside the running one, with Keep playing and
     * Stop. [endsAt] is when the session will be recorded as ending if nobody answers by [answerBy];
     * [largeIcon] is the game's picture (13.4, P1).
     */
    fun postAsk(title: String, startMillis: Long, endsAt: Long, answerBy: Long, largeIcon: Bitmap? = null) {
        if (!notificationsAllowed()) return
        val manager = manager() ?: return
        val hhmm = DateTimeFormatter.ofPattern("HH:mm")
        val zone = ZoneId.systemDefault()
        val since = LocalTime.ofInstant(Instant.ofEpochMilli(startMillis), zone).format(hhmm)
        val end = LocalTime.ofInstant(Instant.ofEpochMilli(endsAt), zone).format(hhmm)
        val by = LocalTime.ofInstant(Instant.ofEpochMilli(answerBy), zone).format(hhmm)
        val builder = Notification.Builder(context, CHANNEL_CHECK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Still playing $title?")
            .setContentText("Playing since $since. No answer by $by: the session ends at $end.")
            .setStyle(Notification.BigTextStyle().bigText("Playing since $since. No answer by $by: the session ends at $end."))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openGames())
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_notification), "Keep playing", keepIntent()).build()
            )
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_notification), "Stop", stopIntent()).build()
            )
        largeIcon?.let { builder.setLargeIcon(Icon.createWithBitmap(it)) }
        manager.notify(ID_CHECK, builder.build())
    }

    fun cancelAsk() {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID_CHECK)
    }

    /**
     * The next look at the running session in [delayMs]: one unique job. From a start, Keep playing
     * or a return to the app, the new job replaces the one waiting. From the check itself
     * ([fromCheck], 13.4) it is appended instead: replacing would cancel the job running that very
     * check (WorkManager cancels a replaced job's coroutine wherever it is), so a check never cancels
     * itself and ends by returning; the appended job starts once it has.
     */
    fun scheduleCheck(delayMs: Long, fromCheck: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            CHECK_WORK,
            if (fromCheck) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<GameSessionCheckWorker>().setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS).build()
        )
    }

    fun cancelCheck() {
        WorkManager.getInstance(context).cancelUniqueWork(CHECK_WORK)
    }

    /** True when the owner granted Do Not Disturb access: without it the session runs unguarded. */
    fun canGuard(): Boolean = context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    /**
     * Turns the "Game night" mode on, creating it the first time (or again, when the owner deleted it
     * in Settings). Returns the rule's id, or null when it couldn't be turned on.
     */
    fun startGameNight(knownRuleId: String?): String? {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        if (!manager.isNotificationPolicyAccessGranted) return null
        return try {
            val id = knownRuleId?.takeIf { manager.getAutomaticZenRule(it) != null } ?: manager.addAutomaticZenRule(rule())
            id?.also { manager.setAutomaticZenRuleState(it, condition(Condition.STATE_TRUE)) }
        } catch (e: SecurityException) {
            Log.w(TAG, "Game night refused: no Do Not Disturb access")
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Game night refused by the system", e)
            null
        }
    }

    /** Turns the mode off; the owner's own Do Not Disturb, if it was on, stays as it was. */
    fun endGameNight(ruleId: String?) {
        val id = ruleId ?: return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            if (manager.isNotificationPolicyAccessGranted && manager.getAutomaticZenRule(id) != null) {
                manager.setAutomaticZenRuleState(id, condition(Condition.STATE_FALSE))
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Game night couldn't be turned off: no Do Not Disturb access")
        }
    }

    /** The system page where the owner grants Do Not Disturb access. */
    fun accessSettingsIntent(): Intent =
        Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun rule(): AutomaticZenRule =
        AutomaticZenRule.Builder("Game night", CONDITION)
            .setType(AutomaticZenRule.TYPE_IMMERSIVE)
            .setConfigurationActivity(ComponentName(context, MainActivity::class.java))
            .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            .setTriggerDescription("While a game session runs in PixelBrain")
            .setIconResId(R.drawable.ic_notification)
            .setManualInvocationAllowed(false)
            .build()

    private fun condition(state: Int) = Condition(CONDITION, "Game night", state, Condition.SOURCE_USER_ACTION)

    private fun notificationsAllowed(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun manager(): NotificationManager? {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNNING, "Game session", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The running game session and its time"
            }
        )
        // "Still playing?" vibrates through Game night (the owner's call, 2026-11-16): no sound, and it
        // passes Do Not Disturb, which Android allows the app because it has Do Not Disturb access
        // (asked for Game night); re-created at each post so the access, once granted, applies.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CHECK, "Still playing?", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Asks after 3 hours whether a game session is still running. Vibrates, even during Game night."
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
                setBypassDnd(true)
            }
        )
        return manager
    }

    private fun openGames(): PendingIntent =
        PendingIntent.getActivity(
            context, 40, WidgetNav.openIntent(context, WidgetNav.SCREEN_GAMES),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** The running game's map: a fixed screen key, no path (the app opens the game being played). */
    private fun openMap(): PendingIntent =
        PendingIntent.getActivity(
            context, 43, WidgetNav.openIntent(context, WidgetNav.SCREEN_GAME_MAP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun stopIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context, 41, Intent(context, GameReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun keepIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context, 42, Intent(context, GameReceiver::class.java).setAction(ACTION_KEEP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        private const val TAG = "GamePlatform"
        const val CHANNEL_RUNNING = "game_running"
        const val ACTION_STOP = "cloud.wafflecommons.pixelbrainreader.games.STOP"
        const val ACTION_KEEP = "cloud.wafflecommons.pixelbrainreader.games.KEEP"
        const val CHANNEL_CHECK = "game_check"
        private const val CHECK_WORK = "game_session_check"
        private const val ID_RUNNING = 2020
        private const val ID_CHECK = 2021
        private val CONDITION: Uri = "condition://cloud.wafflecommons.pixelbrainreader/game_night".toUri()
    }
}
