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
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

/**
 * Chores that are due today. Tapping the "Done" chip stamps the chore's lastDoneDate and awards its
 * effort as XP via [ChoreDoneCallback], then the chore drops off the list on re-render. The header
 * deep-links into the HomeOS chore dashboard.
 */
class ChoresWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val chores = WidgetLiveData.choresDue(context)
        provideContent {
            GlanceTheme { Content(context, chores) }
        }
    }

    @Composable
    private fun Content(context: Context, chores: List<WidgetLiveData.WidgetChore>) {
        val openChores = actionStartActivity(WidgetNav.openIntent(context, WidgetNav.SCREEN_CHORES))
        WidgetSurface {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                WidgetHeader(
                    emoji = "🧹",
                    title = "Chores",
                    subtitle = if (chores.isNotEmpty()) "${chores.size} due" else "All done",
                    onTitleClick = openChores,
                    trailingEmoji = "⌂",
                    onTrailingClick = openChores
                )
                Spacer(GlanceModifier.height(10.dp))
                if (chores.isEmpty()) {
                    WidgetEmpty("✨", "Nothing due — home is tidy")
                } else {
                    LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                        items(chores) { chore -> ChoreRow(chore) }
                    }
                }
            }
        }
    }

    @Composable
    private fun ChoreRow(chore: WidgetLiveData.WidgetChore) {
        val complete = actionRunCallback<ChoreDoneCallback>(
            actionParametersOf(
                WidgetKeys.CHORE_ID to chore.id,
                WidgetKeys.CHORE_EFFORT to chore.effort
            )
        )
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                chore.name,
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(fontSize = 14.sp, color = GlanceTheme.colors.onSurface)
            )
            if (chore.effort > 0) {
                Text(
                    "+${chore.effort}",
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = WidgetTokens.XpGold)
                )
                Spacer(GlanceModifier.width(8.dp))
            }
            Box(
                modifier = GlanceModifier
                    .background(GlanceTheme.colors.primary)
                    .cornerRadius(14.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clickable(complete),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Done",
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onPrimary)
                )
            }
        }
    }
}

class ChoresWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ChoresWidget()
}
