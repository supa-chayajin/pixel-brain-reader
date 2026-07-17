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
 * Today's scheduled habits as a checklist. Tapping a row toggles completion in place via
 * [HabitToggleCallback] (Room + vault + XP), and the list re-renders live. The header progress
 * bar + count deep-links into the full Habits screen.
 */
class HabitsWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val habits = WidgetLiveData.habitsForToday(context)
        provideContent {
            GlanceTheme { Content(context, habits) }
        }
    }

    @Composable
    private fun Content(context: Context, habits: List<WidgetLiveData.WidgetHabit>) {
        val done = habits.count { it.done }
        val total = habits.size
        val openHabits = actionStartActivity(WidgetNav.openIntent(context, WidgetNav.SCREEN_HABITS))
        WidgetSurface {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                WidgetHeader(
                    emoji = "✅",
                    title = "Habits",
                    subtitle = if (total > 0) "$done of $total done" else "Nothing scheduled",
                    onTitleClick = openHabits,
                    trailingEmoji = "＋",
                    onTrailingClick = openHabits
                )
                Spacer(GlanceModifier.height(10.dp))
                if (total == 0) {
                    WidgetEmpty("🌱", "No habits scheduled today")
                } else {
                    ProgressRow(
                        label = "Today",
                        fraction = done.toFloat() / total,
                        trailing = "$done/$total",
                        color = WidgetTokens.Success
                    )
                    Spacer(GlanceModifier.height(8.dp))
                    LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                        items(habits) { habit -> HabitRow(habit) }
                    }
                }
            }
        }
    }

    @Composable
    private fun HabitRow(habit: WidgetLiveData.WidgetHabit) {
        val toggle = actionRunCallback<HabitToggleCallback>(
            actionParametersOf(
                WidgetKeys.HABIT_ID to habit.id,
                WidgetKeys.HABIT_TARGET_DONE to !habit.done
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
                    .background(if (habit.done) GlanceTheme.colors.primary else GlanceTheme.colors.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (habit.done) Text("✓", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onPrimary))
            }
            Spacer(GlanceModifier.width(12.dp))
            Text(
                habit.title,
                maxLines = 1,
                style = TextStyle(
                    fontSize = 14.sp,
                    color = if (habit.done) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface,
                    textDecoration = if (habit.done) TextDecoration.LineThrough else TextDecoration.None
                )
            )
        }
    }
}

class HabitsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HabitsWidget()
}
