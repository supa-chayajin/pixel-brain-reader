package cloud.wafflecommons.pixelbrainreader.widget.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width

/**
 * A compact launchpad of deep-link buttons — the app's "app actions" surfaced on the home screen.
 * Each button fires an explicit [WidgetNav] intent (capture, or open a top-level screen), so a tap
 * lands the user exactly where they want without hunting through the app.
 */
class QuickActionsWidget : GlanceAppWidget() {

    private data class Item(val emoji: String, val label: String, val action: Action)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme { Content(context) }
        }
    }

    @Composable
    private fun Content(context: Context) {
        val items = listOf(
            Item("📝", "Capture", actionStartActivity(WidgetNav.captureIntent(context))),
            Item("📓", "Daily", actionStartActivity(WidgetNav.openIntent(context, WidgetNav.SCREEN_DAILY))),
            Item("🫧", "Mood", actionStartActivity(WidgetNav.openIntent(context, WidgetNav.SCREEN_MOOD))),
            Item("✅", "Habits", actionStartActivity(WidgetNav.openIntent(context, WidgetNav.SCREEN_HABITS))),
            Item("🧹", "Chores", actionStartActivity(WidgetNav.openIntent(context, WidgetNav.SCREEN_CHORES))),
            Item("🔍", "Ask", actionStartActivity(WidgetNav.openIntent(context, WidgetNav.SCREEN_CHAT)))
        )
        WidgetSurface {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                WidgetHeader(emoji = "⚡", title = "Quick actions")
                Spacer(GlanceModifier.height(10.dp))
                ActionGrid(items)
            }
        }
    }

    /** Lays the six actions out as two rows of three, each cell an even-weight [ActionButton]. */
    @Composable
    private fun ActionGrid(items: List<Item>) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            items.chunked(3).forEachIndexed { rowIndex, rowItems ->
                Row(
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    rowItems.forEachIndexed { colIndex, item ->
                        ActionButton(
                            emoji = item.emoji,
                            label = item.label,
                            action = item.action,
                            modifier = GlanceModifier.defaultWeight()
                        )
                        if (colIndex != rowItems.lastIndex) Spacer(GlanceModifier.width(8.dp))
                    }
                }
                if (rowIndex == 0) Spacer(GlanceModifier.height(8.dp))
            }
        }
    }
}

class QuickActionsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickActionsWidget()
}
