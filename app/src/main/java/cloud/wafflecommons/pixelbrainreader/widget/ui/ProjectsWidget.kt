package cloud.wafflecommons.pixelbrainreader.widget.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
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
 * The Projects page's projects (C11): the pinned first, then the most recently active, hidden ones
 * left out — each with its two-week activity and, when there is room, its next action. As many as
 * the widget's height allows (`ProjectsWidgetLayout`: about three at the default size, up to ten
 * when stretched). Live-read from the vault mirror; work projects drop out outside Work mode.
 * Tapping opens the Projects screen.
 */
class ProjectsWidget : GlanceAppWidget() {

    // One declared size per height at which the layout changes; the launcher shows the tallest that
    // fits the placement, and each one is composed with its own LocalSize.
    override val sizeMode = SizeMode.Responsive(
        ProjectsWidgetLayout.breakpointsDp().map { DpSize(WIDTH, it.dp) }.toSet()
    )

    private companion object {
        val WIDTH = 150.dp // minResizeWidth: every placement is at least this wide
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val list = WidgetLiveData.activeProjects(context, ProjectsWidgetLayout.MAX_ROWS)
        provideContent {
            GlanceTheme { Content(context, list.projects, list.emptyLine) }
        }
    }

    @Composable
    private fun Content(context: Context, projects: List<WidgetLiveData.WidgetProject>, emptyLine: String) {
        val layout = ProjectsWidgetLayout.forHeight(LocalSize.current.height.value)
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
                    WidgetEmpty("🗂️", emptyLine)
                } else {
                    LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                        items(projects.take(layout.rows)) { p -> ProjectRow(p, layout.withNext, open) }
                    }
                }
            }
        }
    }

    @Composable
    private fun ProjectRow(p: WidgetLiveData.WidgetProject, withNext: Boolean, open: Action) {
        Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 5.dp).clickable(open)) {
            Text(
                p.title,
                maxLines = 1,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface)
            )
            Text(p.line, maxLines = 1, style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant))
            if (withNext) p.next?.let {
                Text("→ $it", maxLines = 1, style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.primary))
            }
        }
    }
}

class ProjectsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ProjectsWidget()
}
