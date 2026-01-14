package cloud.wafflecommons.pixelbrainreader.ui.journal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.wafflecommons.pixelbrainreader.data.repository.WeatherData
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DailyNoteHeader(
    emoji: String?,
    lastUpdate: String?,
    topDailyTags: List<String>,
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
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
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
                    color = MaterialTheme.colorScheme.primary
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
    }
}
