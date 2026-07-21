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
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import cloud.wafflecommons.pixelbrainreader.widget.data.WidgetChartRenderer
import cloud.wafflecommons.pixelbrainreader.widget.data.WidgetDataFetcher
import cloud.wafflecommons.pixelbrainreader.widget.data.WidgetDataSnapshot
import java.util.Locale

/**
 * A glanceable dashboard of the day's Health Connect metrics — a steps goal bar plus a tile grid
 * (sleep, heart rate, active minutes, calories, distance, hydration). Responsive: a shorter widget
 * shows the steps bar + one tile row, a taller one reveals the second row instead of cropping it.
 * Reads the shared snapshot; the header refreshes it and deep-links into Life Stats.
 */
class HealthWidget : GlanceAppWidget() {

    companion object {
        // A 2-row placement on the dogfood Fold is ~213dp tall — TALL is sized under that so both tile
        // rows are selected there (was 280dp → forced a 3rd, empty grid row). The layout below is tuned
        // (tight surface padding + 4dp gaps) to fit both rows inside that ~213dp without clipping.
        private val SHORT = DpSize(200.dp, 150.dp)  // steps bar + one tile row (compact placement)
        private val TALL = DpSize(300.dp, 205.dp)   // + second tile row (fits a 2-row placement)
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
        val showSecondRow = LocalSize.current.height >= 200.dp
        val openStats = actionStartActivity(WidgetNav.openIntent(context, WidgetNav.SCREEN_STATS))
        WidgetSurface(contentPadding = 10.dp) {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                WidgetHeader(
                    emoji = "❤️",
                    title = "Health today",
                    onTitleClick = openStats,
                    trailingEmoji = "↻",
                    onTrailingClick = actionRunCallback<RefreshWidgetCallback>()
                )
                Spacer(GlanceModifier.height(4.dp))
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
                    Spacer(GlanceModifier.height(4.dp))
                    // Both tile rows share the leftover height equally (defaultWeight) and every tile
                    // fills its row (fillMaxHeight) so all six cards are identical regardless of how
                    // their emoji/value line-heights would otherwise wrap.
                    Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                        StatTile("💤", s.sleep, "sleep", GlanceModifier.defaultWeight().fillMaxHeight())
                        Spacer(GlanceModifier.width(8.dp))
                        StatTile("❤️", if (s.avgHeartRate > 0) "${s.avgHeartRate}" else "--", "bpm", GlanceModifier.defaultWeight().fillMaxHeight())
                        Spacer(GlanceModifier.width(8.dp))
                        StatTile("🔥", "${s.activeMinutes}m", "active", GlanceModifier.defaultWeight().fillMaxHeight())
                    }
                    if (showSecondRow) {
                        Spacer(GlanceModifier.height(4.dp))
                        Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                            StatTile("🍽️", "${s.caloriesBurned}", "kcal", GlanceModifier.defaultWeight().fillMaxHeight())
                            Spacer(GlanceModifier.width(8.dp))
                            StatTile("📏", fmtKm(s.distanceKm), "km", GlanceModifier.defaultWeight().fillMaxHeight())
                            Spacer(GlanceModifier.width(8.dp))
                            StatTile("💧", fmtWater(s.hydrationMl), "water", GlanceModifier.defaultWeight().fillMaxHeight())
                        }
                    }
                }
            }
        }
    }

    private fun fmtKm(km: Double): String = String.format(Locale.getDefault(), "%.1f", km)
    private fun fmtK(v: Long): String = if (v >= 1000) String.format(Locale.getDefault(), "%.0fk", v / 1000.0) else v.toString()
    private fun fmtWater(ml: Int): String = if (ml >= 1000) String.format(Locale.getDefault(), "%.1fL", ml / 1000.0) else "${ml}ml"
}

class HealthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HealthWidget()
}
