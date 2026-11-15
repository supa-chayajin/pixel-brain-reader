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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import cloud.wafflecommons.pixelbrainreader.data.model.MemoryEcho

/**
 * Echo of the day (K6, 12.20): the note to look at again today: a spaced echo due first (#review
 * notes and the picked folders), else a forgotten one. Live-read, read-only: a tap opens Daily,
 * where the echo is answered. No note path travels in the intent.
 */
class EchoWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val echo = WidgetLiveData.echoOfTheDay(context)
        provideContent {
            GlanceTheme { Content(context, echo) }
        }
    }

    @Composable
    private fun Content(context: Context, echo: MemoryEcho?) {
        val open = actionStartActivity(WidgetNav.openIntent(context, WidgetNav.SCREEN_DAILY))
        WidgetSurface(onClick = open) {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                WidgetHeader(emoji = "🔁", title = "Echo of the day", onTitleClick = open, subtitle = echo?.urgencyLabel)
                Spacer(GlanceModifier.height(8.dp))
                if (echo == null) {
                    WidgetEmpty("🌱", "Nothing to look back at today.")
                } else {
                    Text(
                        text = echo.title,
                        style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GlanceTheme.colors.onSurface),
                        maxLines = 2
                    )
                    Spacer(GlanceModifier.height(4.dp))
                    Text(
                        text = echo.snippet,
                        style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
                        maxLines = 4
                    )
                }
            }
        }
    }
}

class EchoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EchoWidget()
}
