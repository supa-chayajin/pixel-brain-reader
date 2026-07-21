package cloud.wafflecommons.pixelbrainreader.ui.journal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.wafflecommons.pixelbrainreader.data.health.DailyHealthMetrics
import cloud.wafflecommons.pixelbrainreader.data.repository.WeatherData
import cloud.wafflecommons.pixelbrainreader.ui.daily.DailyMoodPoint

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DailyNoteHeader(
    emoji: String?,
    lastUpdate: String?,
    topDailyTags: List<String>,
    healthMetrics: DailyHealthMetrics? = null,
    moodTrend: List<DailyMoodPoint> = emptyList(),
    modifier: Modifier = Modifier
) {

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
             // Top Row: Emoji + Summary Stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Huge Emoji
                Text(
                    text = emoji ?: "😐",
                    fontSize = 48.sp,
                    modifier = Modifier.padding(end = 20.dp)
                )

                // Right: Content
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column {
                            Text(
                                text = "Résumé du jour",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            
                            if (lastUpdate != null) {
                                Text(
                                    text = "Dernière mise à jour : $lastUpdate",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    
                    if (topDailyTags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Tags principaux du jour :", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(4.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            topDailyTags.forEach { tag ->
                                SuggestionChip(
                                    onClick = { },
                                    label = { Text("#$tag") },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    ),
                                    border = null,
                                    modifier = Modifier.height(26.dp)
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = healthMetrics != null) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (healthMetrics != null) {
                            Text(
                                text = "👟 ${String.format("%,d", healthMetrics.steps)} pas",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            val hours = healthMetrics.sleepDurationMinutes / 60
                            val minutes = healthMetrics.sleepDurationMinutes % 60
                            Text(
                                text = "🌙 ${hours}h ${minutes}m",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Column {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(16.dp))

                cloud.wafflecommons.pixelbrainreader.ui.components.MoodTrendsCard(
                    moodTrend = moodTrend,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}

