package cloud.wafflecommons.pixelbrainreader.ui.journal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.wafflecommons.pixelbrainreader.data.repository.WeatherData
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DailyNoteHeader(
    emoji: String?,
    lastUpdate: String?,
    topDailyTags: List<String>,
    oracleInsight: String? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
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
                    Text(
                        text = "Daily Summary",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    
                    if (lastUpdate != null) {
                        Text(
                            text = "Last update: $lastUpdate",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (topDailyTags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Today's Top Tags:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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

            // Oracle Insight Section
            if (!oracleInsight.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(16.dp))
                OracleCard(insight = oracleInsight)
            }
        }
    }
}

@Composable
fun OracleCard(insight: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = Color(0xFF3F51B5).copy(alpha = 0.1f), // Deep Purple / Indigo tint
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = 1.dp,
                color = Color(0xFF3F51B5).copy(alpha = 0.3f), // Indigo border
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {

        Icon(
            imageVector = Icons.Default.AutoAwesome, // Fixed import
            contentDescription = "Oracle",
            tint = Color(0xFF3F51B5), // Indigo
            modifier = Modifier.size(24.dp).padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = "Oracle Insight",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF3F51B5),
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = insight,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
            )
        }
    }
}
