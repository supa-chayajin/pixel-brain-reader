package cloud.wafflecommons.pixelbrainreader.ui.journal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.ui.daily.MorningBriefingUiState

@Composable
fun MorningBriefingSection(
    state: MorningBriefingUiState,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .clickable { isExpanded = !isExpanded }
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Morning Briefing",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // Body Content
            AnimatedVisibility(visible = isExpanded) {
                if (state.isLoading) {
                    BriefingSkeleton()
                } else {
                    Column(modifier = Modifier.padding(top = 16.dp)) {
                        // 1. Logistic Weather
                        WeatherAdviceBlock(state.weather)
                        
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                        )

                        // 2. Mood KPI (Sparkline)
                        MoodSparkline(state.moodTrend)
                        
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                        )

                        // 3. Lead Mindset Quote
                        if (state.quote.isNotBlank()) {
                            Text(
                                text = "\"${state.quote}\"",
                                style = MaterialTheme.typography.bodyMedium,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }

                        // 4. Signal News
                        if (state.news.isNotEmpty()) {
                            state.news.take(3).forEach { newsItem ->
                                Text(
                                    text = "• $newsItem",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherAdviceBlock(weather: cloud.wafflecommons.pixelbrainreader.data.repository.WeatherData?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (weather != null) {
            Text(
                text = weather.emoji,
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(end = 12.dp)
            )
            Column {
                Text(
                    text = "${weather.temperature} • ${weather.location ?: "Unknown"}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                // Mock Advice Logic
                val advice = if (weather.emoji.contains("rain", ignoreCase = true)) "Pack an umbrella!" else "Great day for a walk!"
                Text(
                    text = "💡 $advice",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Text("Waiting for forecast...", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MoodSparkline(trend: List<Float>) {
    if (trend.isEmpty()) return
    
    Column {
        Text(
            text = "Mood Trend (7 Day)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        val lineColor = MaterialTheme.colorScheme.primary
        Canvas(modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
        ) {
            val path = Path()
            val stepX = size.width / (trend.size - 1).coerceAtLeast(1)
            val maxY = size.height
            
            trend.forEachIndexed { index, score ->
                val x = index * stepX
                val y = maxY - (score * maxY) // Invert Y because canvas 0,0 is top-left
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun BriefingSkeleton() {
    Column {
        SkeletonBox(width = 200.dp, height = 24.dp) // Simulated Weather
        Spacer(modifier = Modifier.height(16.dp))
        SkeletonBox(width = 120.dp, height = 16.dp) // Label
        Spacer(modifier = Modifier.height(8.dp))
        SkeletonBox(width = 280.dp, height = 40.dp) // Sparkline
        Spacer(modifier = Modifier.height(16.dp))
        SkeletonBox(width = 250.dp, height = 16.dp) // Quote
    }
}

@Composable
private fun SkeletonBox(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(4.dp)
) {
    val shimmerColors = listOf(
        Color.Gray.copy(alpha = 0.3f),
        Color.Gray.copy(alpha = 0.5f),
        Color.Gray.copy(alpha = 0.3f),
    )
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset.Zero,
        end = Offset(x = translateAnim.value, y = translateAnim.value)
    )

    Box(
        modifier = Modifier
            .size(width, height)
            .clip(shape)
            .background(brush)
    )
}
