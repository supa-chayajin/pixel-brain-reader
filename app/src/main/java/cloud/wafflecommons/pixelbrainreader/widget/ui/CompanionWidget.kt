package cloud.wafflecommons.pixelbrainreader.widget.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
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
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import cloud.wafflecommons.pixelbrainreader.widget.data.CompanionWidgetState
import cloud.wafflecommons.pixelbrainreader.widget.data.WidgetChartRenderer
import cloud.wafflecommons.pixelbrainreader.widget.data.WidgetDataFetcher

/**
 * The flagship "Pixel Dashboard": hero + XP, life-OS progress, today's health, and a vitality graph.
 * Fully responsive across four breakpoints — it grows from a compact hero tile to a full dashboard as
 * it is resized, revealing more content (never cropping it) as more height becomes available. Reads
 * the shared snapshot (fast, no live Health Connect round-trip in provideGlance).
 *
 * Content is gated on the available HEIGHT so each declared size renders only what fits — the graph
 * takes the leftover space via `defaultWeight`, so the widget scales cleanly to large placements.
 */
class CompanionWidget : GlanceAppWidget() {

    companion object {
        // Declared sizes → Glance picks the largest that fits and reports it via LocalSize.
        // On the dogfood Fold a 3-row placement reports 340dp tall and a 4-row one ~453dp, so LARGE
        // (340) is the "current" size and XLARGE (400) only wins once the widget grows a whole row.
        private val SMALL = DpSize(160.dp, 120.dp)    // hero + XP only
        private val MEDIUM = DpSize(270.dp, 180.dp)   // + life progress pills
        private val LARGE = DpSize(300.dp, 340.dp)    // + health pills + graph (no quick actions)
        private val XLARGE = DpSize(360.dp, 400.dp)   // one row taller: reveals the quick-action row
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE, XLARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val fetcher = WidgetDataFetcher(WidgetChartRenderer())
        val state = fetcher.fetchState(context)
        provideContent {
            GlanceTheme { Content(context, state) }
        }
    }

    @Composable
    private fun Content(context: Context, state: CompanionWidgetState) {
        val size = LocalSize.current
        val h = size.height
        val showLifeRow = h >= 160.dp     // MEDIUM+
        val showHealthRow = h >= 240.dp   // LARGE+
        val showExtras = h >= 240.dp      // LARGE+ : vitality graph
        val showQuickRow = h >= 400.dp    // XLARGE (one row taller) : quick-action row under the graph

        WidgetSurface {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                Header(context, state, showRefresh = size.width >= 240.dp)
                Spacer(GlanceModifier.height(8.dp))
                XpBar(state)

                if (showLifeRow) {
                    Spacer(GlanceModifier.height(8.dp))
                    LifePills(state)
                    if (showHealthRow) {
                        Spacer(GlanceModifier.height(6.dp))
                        HealthPills(state)
                    }
                }

                if (showExtras) {
                    // Sub-Column keeps the top-level Column within Glance's 10-direct-child limit and
                    // lets the graph flex to fill whatever vertical space is left after the pills.
                    // The quick-action row is only revealed one row taller (XLARGE); at the current
                    // size the graph takes the whole remaining space so nothing sits under it.
                    Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                        Spacer(GlanceModifier.height(8.dp))
                        Graph(state, GlanceModifier.fillMaxWidth().defaultWeight())
                        if (showQuickRow) {
                            Spacer(GlanceModifier.height(8.dp))
                            QuickRow(context)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Header(context: Context, state: CompanionWidgetState, showRefresh: Boolean) {
        val openStats = actionStartActivity(WidgetNav.openIntent(context, WidgetNav.SCREEN_STATS))
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = GlanceModifier
                    .size(40.dp)
                    .cornerRadius(13.dp)
                    .background(GlanceTheme.colors.primaryContainer)
                    .clickable(openStats),
                contentAlignment = Alignment.Center
            ) {
                if (state.avatarBitmap != null) {
                    Image(provider = ImageProvider(state.avatarBitmap), contentDescription = "Avatar", modifier = GlanceModifier.fillMaxSize())
                } else {
                    Text("🧙", style = TextStyle(fontSize = 20.sp))
                }
            }
            Spacer(GlanceModifier.width(10.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    state.heroClass.replaceFirstChar { it.uppercase() },
                    maxLines = 1,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface)
                )
                Text(
                    "Niv. ${state.currentLevel}  ·  ${state.currentXp}/${state.maxXp} XP",
                    maxLines = 1,
                    style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant)
                )
            }
            if (showRefresh) IconChip("↻", actionRunCallback<RefreshWidgetCallback>())
        }
    }

    @Composable
    private fun XpBar(state: CompanionWidgetState) {
        val fraction = if (state.maxXp > 0) state.currentXp.toFloat() / state.maxXp else 0f
        ProgressRow(label = "Niveau ${state.currentLevel}", fraction = fraction, trailing = "${state.currentXp}/${state.maxXp}", color = WidgetTokens.XpGold)
    }

    @Composable
    private fun LifePills(state: CompanionWidgetState) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatPill("✅", "${state.habitsDone}/${state.habitsTotal}", GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(6.dp))
            StatPill("📋", "${state.tasksDone}/${state.tasksTotal}", GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(6.dp))
            StatPill("🧹", "${state.choresDue}", GlanceModifier.defaultWeight())
        }
    }

    @Composable
    private fun HealthPills(state: CompanionWidgetState) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatPill("👣", state.stepsCount, GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(6.dp))
            StatPill("💤", state.sleepDuration, GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(6.dp))
            StatPill("❤️", if (state.avgHeartRate > 0) "${state.avgHeartRate}" else "--", GlanceModifier.defaultWeight())
        }
    }

    @Composable
    private fun Graph(state: CompanionWidgetState, modifier: GlanceModifier) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            if (state.graphBitmap != null) {
                Image(provider = ImageProvider(state.graphBitmap), contentDescription = "Tendance de vitalité", modifier = GlanceModifier.fillMaxSize())
            } else {
                Text("Pas encore de données de vitalité", style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.outline))
            }
        }
    }

    @Composable
    private fun QuickRow(context: Context) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ActionButton("🫧", "Humeur", actionStartActivity(WidgetNav.openIntent(context, WidgetNav.SCREEN_MOOD)), GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(6.dp))
            ActionButton("📓", "Quotidien", actionStartActivity(WidgetNav.openIntent(context, WidgetNav.SCREEN_DAILY)), GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(6.dp))
            ActionButton("📝", "Capturer", actionStartActivity(WidgetNav.captureIntent(context)), GlanceModifier.defaultWeight())
        }
    }
}
