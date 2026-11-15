package cloud.wafflecommons.pixelbrainreader.widget.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

/**
 * The Toolkit widget (E17, 12.20; the owner's D12): today's mission-log count, the next meeting
 * and this week's overload signal, in Work mode only: outside it, it only says which mode it waits
 * for. Live-read, read-only; a tap opens the toolkit.
 */
class ToolkitWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val day = WidgetLiveData.toolkitDay(context)
        provideContent {
            GlanceTheme { Content(context, day) }
        }
    }

    @Composable
    private fun Content(context: Context, day: WidgetLiveData.ToolkitDay) {
        val open = actionStartActivity(WidgetNav.openIntent(context, WidgetNav.SCREEN_WORK))
        WidgetSurface(onClick = open) {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                WidgetHeader(emoji = "🧰", title = "Toolkit", onTitleClick = open, subtitle = if (day.shown) "Today" else null)
                Spacer(GlanceModifier.height(8.dp))
                if (!day.shown) {
                    WidgetEmpty("🌙", "Shows in ${day.workLabel} mode.")
                } else {
                    Line(
                        when {
                            day.logLocked -> "📝 Log locked: unlock the journal"
                            day.logToday == null -> "📝 No mission log yet"
                            else -> "📝 ${day.logToday} log entr${if (day.logToday == 1) "y" else "ies"} today"
                        }
                    )
                    Line(day.nextMeeting?.let { "📅 $it" } ?: "📅 No more meetings today")
                    Line(day.overload?.let { "⚠️ $it" } ?: "✅ Load as usual this week")
                }
            }
        }
    }

    @Composable
    private fun Line(text: String) {
        Text(text = text, style = TextStyle(fontSize = 14.sp, color = GlanceTheme.colors.onSurface), maxLines = 2)
        Spacer(GlanceModifier.height(6.dp))
    }
}

class ToolkitWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ToolkitWidget()
}
