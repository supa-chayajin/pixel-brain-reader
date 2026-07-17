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
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

/**
 * One-tap mood check-in. Five emoji buttons log a mood entry for right now via [MoodLogCallback] —
 * no need to open the app. The header shows today's summary and deep-links into the Mood screen.
 */
class MoodWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val mood = WidgetLiveData.moodToday(context)
        provideContent {
            GlanceTheme { Content(context, mood) }
        }
    }

    @Composable
    private fun Content(context: Context, mood: WidgetLiveData.WidgetMood) {
        WidgetSurface {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                WidgetHeader(
                    emoji = "🫧",
                    title = "How are you?",
                    subtitle = if (mood.entryCount > 0) "${mood.entryCount} today  ·  ${mood.emoji}" else "Tap to check in",
                    onTitleClick = actionStartActivity(WidgetNav.openIntent(context, WidgetNav.SCREEN_MOOD))
                )
                Spacer(GlanceModifier.height(14.dp))
                Row(
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val faces = listOf(1 to "😫", 2 to "🙁", 3 to "😐", 4 to "🙂", 5 to "🤩")
                    faces.forEachIndexed { index, (score, emoji) ->
                        MoodButton(score, emoji, GlanceModifier.defaultWeight().fillMaxHeight())
                        if (index != faces.lastIndex) Spacer(GlanceModifier.width(8.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun MoodButton(score: Int, emoji: String, modifier: GlanceModifier) {
        Box(
            modifier = modifier
                .background(GlanceTheme.colors.secondaryContainer)
                .cornerRadius(18.dp)
                .padding(vertical = 14.dp)
                .clickable(actionRunCallback<MoodLogCallback>(actionParametersOf(WidgetKeys.MOOD_SCORE to score))),
            contentAlignment = Alignment.Center
        ) {
            Text(emoji, style = TextStyle(fontSize = 26.sp))
        }
    }
}

class MoodWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MoodWidget()
}
