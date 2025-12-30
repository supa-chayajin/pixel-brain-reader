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
import cloud.wafflecommons.pixelbrainreader.data.utils.DailySummary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DailyNoteHeader(
    summary: DailySummary,
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
                text = summary.dailyEmoji ?: "😐",
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
                
                if (summary.lastUpdate != null) {
                    Text(
                        text = "Last update: ${summary.lastUpdate}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (summary.allActivities.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        summary.allActivities.forEach { activity ->
                            // FIX: Force high-contrast colors for visibility
                            ElevatedAssistChip(
                                onClick = { },
                                label = { 
                                    Text(
                                        text = activity,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    ) 
                                },
                                shape = CircleShape,
                                colors = AssistChipDefaults.elevatedAssistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    labelColor = MaterialTheme.colorScheme.onSurface,
                                    leadingIconContentColor = MaterialTheme.colorScheme.primary,
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                border = null
                            )
                        }
                    }
                }
            }
        }
    }
}
