package cloud.wafflecommons.pixelbrainreader.ui.lifeos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.wafflecommons.pixelbrainreader.data.model.BriefingData

@Composable
fun BriefingCard(
    data: BriefingData,
    onLinkClick: (String) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.WbTwilight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Morning Briefing",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            Spacer(Modifier.height(16.dp))

            // Weather & Advice
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = data.weather, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(8.dp))
                VerticalDivider(modifier = Modifier.height(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = data.parentingAdvice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(Modifier.height(12.dp))
            
            // Sparkline
            Row(verticalAlignment = Alignment.CenterVertically) {
                 Text(
                    text = "Mood 7j",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(64.dp)
                )
                Text(
                    text = data.moodStats,
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.secondary,
                     letterSpacing = 2.sp
                )
            }
            
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))
            
            // Quote
            Text(
                text = "“${data.quote}”",
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )
            
            Spacer(Modifier.height(16.dp))
            
            // News
            if (data.news.isNotEmpty()) {
                Text(
                     text = "Veille Tech",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(Modifier.height(4.dp))
                data.news.forEach { link ->
                     Text(
                        text = "• ${link.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLinkClick(link.url) }
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}
