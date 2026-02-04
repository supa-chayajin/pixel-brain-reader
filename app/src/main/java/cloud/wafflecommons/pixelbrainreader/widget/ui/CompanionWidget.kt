package cloud.wafflecommons.pixelbrainreader.widget.ui

import android.content.Context
import androidx.glance.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
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
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import cloud.wafflecommons.pixelbrainreader.R
import cloud.wafflecommons.pixelbrainreader.widget.data.CompanionWidgetState
import cloud.wafflecommons.pixelbrainreader.widget.data.WidgetChartRenderer
import cloud.wafflecommons.pixelbrainreader.widget.data.WidgetDataFetcher

class CompanionWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val fetcher = WidgetDataFetcher(WidgetChartRenderer()) 
        val state = fetcher.fetchState(context)

        provideContent {
            GlanceTheme {
                CompanionWidgetContent(state)
            }
        }
    }

    @Composable
    private fun CompanionWidgetContent(state: CompanionWidgetState) {
        // Root Container
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .cornerRadius(24.dp)
                .padding(16.dp)
        ) {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                
                // --- 1. Header Row ---
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Avatar (Bitmap from State)
                    Box(
                        modifier = GlanceModifier
                            .size(48.dp)
                            .cornerRadius(16.dp)
                            .background(GlanceTheme.colors.primaryContainer)
                            .clickable(actionStartActivity<cloud.wafflecommons.pixelbrainreader.MainActivity>()),
                        contentAlignment = Alignment.Center
                    ) {
                        // Priority 1: Bitmap (Guaranteed by Fetcher)
                         if (state.avatarBitmap != null) {
                             Image(
                                 provider = ImageProvider(state.avatarBitmap),
                                 contentDescription = "Avatar",
                                 modifier = GlanceModifier.fillMaxSize()
                             )
                         } else {
                             // Fallback to text emoji or default icon
                             Text("🧙", style = TextStyle(fontSize = 24.sp))
                         }
                    }

                    Spacer(GlanceModifier.width(12.dp))

                    // Info: Title & Level
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            text = state.heroClass,
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = GlanceTheme.colors.onSurface
                            )
                        )
                        Text(
                            text = "Lvl ${state.currentLevel} • ${state.currentXp}/${state.maxXp} XP",
                            style = TextStyle(
                                fontSize = 12.sp,
                                color = GlanceTheme.colors.onSurfaceVariant
                            )
                        )
                    }

                    // Refresh Button
                    Box(
                        modifier = GlanceModifier
                            .size(32.dp)
                            .background(GlanceTheme.colors.secondaryContainer)
                            .cornerRadius(16.dp)
                            .clickable(actionRunCallback<RefreshWidgetCallback>()),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "↻",
                            style = TextStyle(
                                fontSize = 18.sp, 
                                color = GlanceTheme.colors.onSecondaryContainer
                            )
                        )
                    }
                }

                Spacer(GlanceModifier.height(16.dp))

                // --- 2. Stats Row (Pills) ---
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Steps (Pill)
                    StatPill(
                        icon = "👣", 
                        value = state.stepsCount,
                        modifier = GlanceModifier.defaultWeight()
                    )
                    
                    Spacer(GlanceModifier.width(8.dp))
                    
                    // Sleep (Pill)
                    StatPill(
                        icon = "💤", 
                        value = state.sleepDuration,
                        modifier = GlanceModifier.defaultWeight()
                    )
                }

                Spacer(GlanceModifier.height(12.dp))

                // --- 3. Vitality Graph ---
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .defaultWeight()
                        .cornerRadius(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.graphBitmap != null) {
                        Image(
                            provider = ImageProvider(state.graphBitmap),
                            contentDescription = "Vitality Trend",
                            modifier = GlanceModifier.fillMaxSize()
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                             Text(
                                text = "N/A",
                                style = TextStyle(color = GlanceTheme.colors.outline, fontSize = 12.sp)
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StatPill(
        icon: String,
        value: String,
        modifier: GlanceModifier = GlanceModifier
    ) {
        Row(
            modifier = modifier
                .background(GlanceTheme.colors.secondaryContainer)
                .cornerRadius(24.dp) // Full Pill
                .padding(vertical = 10.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = icon, style = TextStyle(fontSize = 14.sp))
            Spacer(GlanceModifier.width(8.dp))
            Text(
                text = value,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = GlanceTheme.colors.onSecondaryContainer
                )
            )
        }
    }
}
