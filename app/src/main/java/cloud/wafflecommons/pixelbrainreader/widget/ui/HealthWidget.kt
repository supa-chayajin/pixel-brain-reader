package cloud.wafflecommons.pixelbrainreader.widget.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import cloud.wafflecommons.pixelbrainreader.widget.data.WidgetChartRenderer
import cloud.wafflecommons.pixelbrainreader.widget.data.WidgetDataFetcher
import cloud.wafflecommons.pixelbrainreader.widget.data.WidgetDataSnapshot

/**
 * A glanceable dashboard of the day's Health Connect metrics — a steps goal bar plus a tile grid
 * (sleep, heart rate, active minutes, calories, distance, hydration). Responsive: a shorter widget
 * shows the steps bar + one tile row, a taller one reveals the second row instead of cropping it.
 * Reads the shared snapshot; the header refreshes it and deep-links into Life Stats.
 */
class HealthWidget : GlanceAppWidget() {

    companion object {
        private val SHORT = DpSize(200.dp, 190.dp)  // steps bar + one tile row
        private val TALL = DpSize(300.dp, 280.dp)   // + second tile row
    }

    override val sizeMode = SizeMode.Responsive(setOf(SHORT, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetDataFetcher(WidgetChartRenderer()).readSnapshot(context)
        provideContent {
            GlanceTheme { Content(context, snapshot) }
        }
    }

    @Composable
    private fun Content(context: Context, s: WidgetDataSnapshot?) {
        val showSecondRow = LocalSize.current.height >= 210.dp
        val openStats = actionStartActivity(WidgetNav.openIntent(context, WidgetNav.SCREEN_STATS))
        WidgetSurface {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                WidgetHeader(
                    emoji = "❤️",
                    title = "Health today",
                    onTitleClick = openStats,
                    trailingEmoji = "↻",
                    onTrailingClick = actionRunCallback<RefreshWidgetCallback>()
                )
                Spacer(GlanceModifier.height(10.dp))
                if (s == null) {
                    WidgetEmpty("⌛", "Open the app to sync health")
                } else {
                    val goal = if (s.stepGoal > 0) s.stepGoal else 10_000
                    ProgressRow(
                        label = "👣 Steps",
                        fraction = s.stepsRaw.toFloat() / goal,
                        trailing = "${s.steps} / ${fmtK(goal)}",
                        color = WidgetTokens.Success
                    )
                    Spacer(GlanceModifier.height(10.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        StatTile("💤", s.sleep, "sleep", GlanceModifier.defaultWeight())
                        Spacer(GlanceModifier.width(8.dp))
                        StatTile("❤️", if (s.avgHeartRate > 0) "${s.avgHeartRate}" else "--", "bpm", GlanceModifier.defaultWeight())
                        Spacer(GlanceModifier.width(8.dp))
                        StatTile("🔥", "${s.activeMinutes}m", "active", GlanceModifier.defaultWeight())
                    }
                    if (showSecondRow) {
                        Spacer(GlanceModifier.height(8.dp))
                        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            StatTile("🍽️", "${s.caloriesBurned}", "kcal", GlanceModifier.defaultWeight())
                            Spacer(GlanceModifier.width(8.dp))
                            StatTile("📏", String.format("%.1f", s.distanceKm), "km", GlanceModifier.defaultWeight())
                            Spacer(GlanceModifier.width(8.dp))
                            StatTile("💧", fmtWater(s.hydrationMl), "water", GlanceModifier.defaultWeight())
                        }
                    }
                }
            }
        }
    }

    private fun fmtK(v: Long): String = if (v >= 1000) String.format("%.0fk", v / 1000.0) else v.toString()
    private fun fmtWater(ml: Int): String = if (ml >= 1000) String.format("%.1fL", ml / 1000.0) else "${ml}ml"
}

class HealthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HealthWidget()
}
