package cloud.wafflecommons.pixelbrainreader.widget.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle

/**
 * Today's daily-note tasks with in-place check-off via [TaskToggleCallback]. Tapping a row toggles
 * the task; the header deep-links into the Daily note. Complements the Habits widget (habits vs
 * one-off tasks are distinct surfaces in this app).
 */
class TodayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val tasks = WidgetLiveData.tasksForToday(context)
        provideContent {
            GlanceTheme { Content(context, tasks) }
        }
    }

    @Composable
    private fun Content(context: Context, tasks: List<WidgetLiveData.WidgetTask>) {
        val done = tasks.count { it.done }
        val total = tasks.size
        val openDaily = actionStartActivity(WidgetNav.openIntent(context, WidgetNav.SCREEN_DAILY))
        WidgetSurface {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                WidgetHeader(
                    emoji = "📋",
                    title = "Today",
                    subtitle = if (total > 0) "$done of $total tasks" else "No tasks yet",
                    onTitleClick = openDaily,
                    trailingEmoji = "＋",
                    onTrailingClick = openDaily
                )
                Spacer(GlanceModifier.height(10.dp))
                if (total == 0) {
                    WidgetEmpty("🎉", "All clear — no tasks today")
                } else {
                    ProgressRow(
                        label = "Progress",
                        fraction = done.toFloat() / total,
                        trailing = "$done/$total",
                        color = GlanceTheme.colors.primary
                    )
                    Spacer(GlanceModifier.height(8.dp))
                    LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                        items(tasks) { task -> TaskRow(task) }
                    }
                }
            }
        }
    }

    @Composable
    private fun TaskRow(task: WidgetLiveData.WidgetTask) {
        val toggle = actionRunCallback<TaskToggleCallback>(
            actionParametersOf(
                WidgetKeys.TASK_ID to task.id,
                WidgetKeys.TASK_TARGET_DONE to !task.done
            )
        )
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp).clickable(toggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = GlanceModifier
                    .size(24.dp)
                    .cornerRadius(12.dp)
                    .background(if (task.done) WidgetTokens.Success else GlanceTheme.colors.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (task.done) Text("✓", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onPrimary))
            }
            Spacer(GlanceModifier.width(12.dp))
            if (!task.time.isNullOrBlank()) {
                Text(
                    task.time,
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.primary)
                )
                Spacer(GlanceModifier.width(8.dp))
            }
            Text(
                task.label,
                maxLines = 1,
                style = TextStyle(
                    fontSize = 14.sp,
                    color = if (task.done) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface,
                    textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None
                )
            )
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
