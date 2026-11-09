package cloud.wafflecommons.pixelbrainreader.widget.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

/**
 * The Projects page's first three projects (C11): the pinned first, then the most recently active,
 * hidden ones left out — each with its two-week activity and next action. Live-read from the vault
 * mirror; work projects drop out outside Work mode. Tapping opens the Projects screen.
 */
class ProjectsWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val projects = WidgetLiveData.activeProjects(context)
        provideContent {
            GlanceTheme { Content(context, projects) }
        }
    }

    @Composable
    private fun Content(context: Context, projects: List<WidgetLiveData.WidgetProject>) {
        val open = actionStartActivity(WidgetNav.openIntent(context, WidgetNav.SCREEN_PROJECTS))
        // C13: hand off to a desktop session straight from the home screen.
        val handOff = actionStartActivity(WidgetNav.openIntent(context, WidgetNav.SCREEN_HANDOFF))
        WidgetSurface {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                WidgetHeader(
                    emoji = "🚀",
                    title = "Projects",
                    subtitle = "Last 14 days",
                    onTitleClick = open,
                    trailingEmoji = "🤝",
                    onTrailingClick = handOff
                )
                Spacer(GlanceModifier.height(10.dp))
                if (projects.isEmpty()) {
                    WidgetEmpty("🗂️", "No project to show")
                } else {
                    LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                        items(projects) { p -> ProjectRow(p, open) }
                    }
                }
            }
        }
    }

    @Composable
    private fun ProjectRow(p: WidgetLiveData.WidgetProject, open: Action) {
        Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 5.dp).clickable(open)) {
            Text(
                p.title,
                maxLines = 1,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface)
            )
            Text(p.line, maxLines = 1, style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant))
            p.next?.let {
                Text("→ $it", maxLines = 1, style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.primary))
            }
        }
    }
}

class ProjectsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ProjectsWidget()
}
