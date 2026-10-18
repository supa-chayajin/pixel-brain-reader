package cloud.wafflecommons.pixelbrainreader.data.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import cloud.wafflecommons.pixelbrainreader.MainActivity
import cloud.wafflecommons.pixelbrainreader.R
import cloud.wafflecommons.pixelbrainreader.ui.privatevault.PrivateJournalActivity
import cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetNav

/**
 * Single home for the app's notifications: reminders (Phase 3) and sync alerts. Stateless object — callers
 * (the reminder workers) pass a Context. Channels are created lazily/idempotently
 * before every post so we never depend on init order.
 */
object NotificationHelper {

    const val CHANNEL_VAULT = "reminder_vault"
    const val CHANNEL_CHORES = "reminder_chores_habits"
    const val CHANNEL_SYNC = "sync_alerts"
    const val CHANNEL_WORK = "work_nudges"

    // Groups both reminder channels under one "Reminders" category in system settings.
    private const val GROUP_REMINDERS = "reminders"

    private const val NOTIF_VAULT = 2001
    private const val NOTIF_CHORES = 2002
    private const val NOTIF_GUARD = 2003
    private const val NOTIF_OBJECTIVES = 2004

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // Category group first — channels below reference it so they nest under
        // one "Reminders" heading in the system notification settings.
        nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP_REMINDERS, "Reminders"))
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_VAULT,
                "Private vault reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Nudges to write in your private vault"
                group = GROUP_REMINDERS
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CHORES,
                "Chores & habits reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Reminders for due chores and unfinished habits"
                group = GROUP_REMINDERS
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC,
                "Sync alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Changes held back before they reach your git remote"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WORK,
                "Work nudges",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Your weekly objectives slot. Muted outside Work mode."
                group = GROUP_REMINDERS
            }
        )
    }

    /** Deep-links to [PrivateJournalActivity]. */
    fun postVaultReminder(context: Context) {
        ensureChannels(context)
        val intent = Intent(context, PrivateJournalActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val builder = baseBuilder(
            context,
            CHANNEL_VAULT,
            title = "🔒 Time to write",
            text = "Take a moment to journal in your private vault."
        ).setContentIntent(activityPending(context, 0, intent))
        notify(context, NOTIF_VAULT, builder)
    }

    /** Summary of due chores + unfinished habits; deep-links to [MainActivity]. */
    fun postChoresHabitsReminder(context: Context, title: String, lines: List<String>) {
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val text = lines.joinToString("\n")
        val builder = baseBuilder(context, CHANNEL_CHORES, title, lines.firstOrNull() ?: text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(activityPending(context, 1, intent))
        notify(context, NOTIF_CHORES, builder)
    }

    /**
     * The confidentiality guard held a commit. Counts only: the lock screen must never show what
     * was found. Deep-links to the review screen.
     */
    fun postGuardHold(context: Context, items: Int, notes: Int) {
        ensureChannels(context)
        val intent = WidgetNav.openIntent(context, WidgetNav.SCREEN_CONFIDENTIALITY)
        val what = if (items == 1) "1 item" else "$items items"
        val where = if (notes == 1) "1 note" else "$notes notes"
        val builder = baseBuilder(
            context,
            CHANNEL_SYNC,
            title = "Sync held",
            text = "$what in $where to review before your notes can sync."
        )
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setOnlyAlertOnce(true)
            .setContentIntent(activityPending(context, 2, intent))
        notify(context, NOTIF_GUARD, builder)
    }

    /** The weekly objectives slot starts. Counts only; deep-links to the objectives. */
    fun postObjectiveSlot(context: Context, minutes: Int, objectives: Int, dueSoon: Int, stalled: Int) {
        ensureChannels(context)
        val intent = WidgetNav.openIntent(context, WidgetNav.SCREEN_OBJECTIVES)
        val parts = buildList {
            add(if (objectives == 1) "1 objective" else "$objectives objectives")
            if (dueSoon > 0) add("$dueSoon with a milestone due within 2 weeks")
            if (stalled > 0) add("$stalled without progress for 3 weeks")
        }
        val builder = baseBuilder(context, CHANNEL_WORK, title = "Objectives time: $minutes min", text = parts.joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(parts.joinToString("\n")))
            .setContentIntent(activityPending(context, 3, intent))
        notify(context, NOTIF_OBJECTIVES, builder)
    }

    fun cancelGuardHold(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_GUARD)
    }

    private fun baseBuilder(context: Context, channel: String, title: String, text: String) =
        NotificationCompat.Builder(context, channel)
            // Small icon (status bar) = the Pixel Brain logo silhouette (alpha-only / monochrome).
            .setSmallIcon(R.drawable.ic_notification)
            // Large icon (notification body) = the full-colour app launcher logo.
            .setLargeIcon(ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap(width = 128, height = 128))
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

    private fun activityPending(context: Context, requestCode: Int, intent: Intent): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, requestCode, intent, flags)
    }

    private fun notify(context: Context, id: Int, builder: NotificationCompat.Builder) {
        // POST_NOTIFICATIONS is a runtime permission (no API gate needed at minSdk 36).
        // If it isn't granted we silently drop rather than crash — the Settings toggle
        // + the runtime request in MainActivity are the user's opt-in path.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }
}
