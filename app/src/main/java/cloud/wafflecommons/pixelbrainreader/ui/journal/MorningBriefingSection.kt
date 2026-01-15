package cloud.wafflecommons.pixelbrainreader.ui.journal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import cloud.wafflecommons.pixelbrainreader.ui.daily.MorningBriefingUiState
import cloud.wafflecommons.pixelbrainreader.ui.daily.DailyMoodPoint

@Composable
fun MorningBriefingSection(
    state: MorningBriefingUiState,
    onToggle: () -> Unit, // [NEW] Callback
    modifier: Modifier = Modifier
) {
    // var isExpanded by remember { mutableStateOf(true) } // DELETED: Stateless now

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
                .clickable { onToggle() } // Use callback
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
                    imageVector = if (state.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (state.isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // Body Content
            AnimatedVisibility(visible = state.isExpanded) {
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

                        // 4. Lead Mindset Quote
                        if (state.quote.isNotBlank()) {
                            Column(Modifier.padding(bottom = 12.dp)) {
                                Text(
                                    text = "\"${state.quote}\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontStyle = FontStyle.Italic,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (state.quoteAuthor.isNotBlank()) {
                                    Text(
                                        text = "- ${state.quoteAuthor}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
                                    )
                                }
                            }
                        }

                        // 5. Signal News
                        if (state.news.isNotEmpty()) {
                            state.news.forEach { newsItem ->
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
             // [NEW] Offline State
             Icon(
                 imageVector = androidx.compose.material.icons.Icons.Filled.Warning,
                 contentDescription = "Offline",
                 tint = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.size(32.dp).padding(end = 12.dp)
             )
             Column {
                 Text(
                     text = "Forecast Unavailable",
                     style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.Bold,
                     color = MaterialTheme.colorScheme.onSurfaceVariant
                 )
                 Text(
                     text = "Check connection",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant
                 )
             }
        }
    }
}

@Composable
private fun MoodSparkline(trend: List<cloud.wafflecommons.pixelbrainreader.ui.daily.DailyMoodPoint>) {
    if (trend.isEmpty()) return
    
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Text(
            text = "Mood Trend (7 Day)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        val primaryColor = MaterialTheme.colorScheme.primary
        val surfaceColor = MaterialTheme.colorScheme.surface
        val onSurfaceColor = MaterialTheme.colorScheme.onSurface
        
        // Graph Area
        Box(modifier = Modifier
            .fillMaxWidth()
            .height(80.dp) // Taller for labels
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (trend.size < 2) return@Canvas
                
                val width = size.width
                val height = size.height
                // Reserve bottom space for nothing (Emojis are in Row below), but maybe top space for Score labels?
                val topPadding = 20.dp.toPx()
                val bottomPadding = 10.dp.toPx() // Just a bit of margin
                val graphHeight = height - topPadding - bottomPadding
                
                val stepX = width / (trend.size - 1)
                
                // Path for Line
                val path = Path()
                
                // Helper to map score (1-5) to Y
                fun getY(score: Float): Float {
                     // 5 -> top (topPadding)
                     // 1 -> bottom (height - bottomPadding)
                     val normalized = (score - 1f) / 4f // 0.0 (score 1) to 1.0 (score 5)
                     return (topPadding + graphHeight) - (normalized * graphHeight)
                }

                trend.forEachIndexed { index, point ->
                    val x = index * stepX
                    // Clamp to 1 (bottom) if missing, but we handle missing points via loop logic in VM (0f -> "Question Mark").
                    // If score < 1 (e.g. 0), treat as baseline? or skip? 
                    // Let's use 0/1 logic: if < 1, draw at bottom or handle gracefully.
                    val drawScore = if (point.score < 1f) 1f else point.score
                    val y = getY(drawScore)
                    
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                // Draw Gradient Fill
                val fillPath = Path()
                fillPath.addPath(path)
                fillPath.lineTo(width, height)
                fillPath.lineTo(0f, height)
                fillPath.close()
                
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.3f),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = height
                    )
                )

                // Draw Line
                drawPath(
                    path = path,
                    color = primaryColor,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
                
                // Draw Points & Score Labels
                val textPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY 
                    textSize = 30f
                    textAlign = android.graphics.Paint.Align.CENTER
                }

                trend.forEachIndexed { index, point ->
                    if (point.score > 0) {
                        val x = index * stepX
                        val y = getY(point.score)
                        
                        // Circle
                        drawCircle(
                            color = surfaceColor,
                            radius = 6.dp.toPx(),
                            center = Offset(x, y)
                        )
                        // Actually I can just use the Color object passed into scope or generic.
                        // Ideally reused.
                        
                        drawCircle(
                            color = primaryColor,
                            radius = 4.dp.toPx(),
                            center = Offset(x, y)
                        )
                        
                        // Score Label (Formatted Float)
                        val label = String.format("%.1f", point.score)
                        drawContext.canvas.nativeCanvas.drawText(
                            label,
                            x,
                            y - 12.dp.toPx(),
                            textPaint
                        )
                    }
                }
            }
        }
        
        // X-Axis Labels (Emojis) in a Row
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween 
        ) {
            trend.forEach { point ->
                Text(
                    text = point.emoji,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(30.dp), // Fixed width to align? Layout determines.
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
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
