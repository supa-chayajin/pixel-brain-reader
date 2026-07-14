package cloud.wafflecommons.pixelbrainreader.data.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import cloud.wafflecommons.pixelbrainreader.MainActivity
import cloud.wafflecommons.pixelbrainreader.R
import cloud.wafflecommons.pixelbrainreader.ui.privatevault.PrivateJournalActivity

/**
 * Single home for reminder notifications (Phase 3). Stateless object — callers
 * (the reminder workers) pass a Context. Channels are created lazily/idempotently
 * before every post so we never depend on init order.
 */
object NotificationHelper {

    const val CHANNEL_VAULT = "reminder_vault"
    const val CHANNEL_CHORES = "reminder_chores_habits"

    // Groups both reminder channels under one "Reminders" category in system settings.
    private const val GROUP_REMINDERS = "reminders"

    private const val NOTIF_VAULT = 2001
    private const val NOTIF_CHORES = 2002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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
        // POST_NOTIFICATIONS is a runtime permission on API 33+. If it isn't granted
        // we silently drop rather than crash — the Settings toggle + the runtime
        // request in MainActivity are the user's opt-in path.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }
}
