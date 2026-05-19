package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.wafflecommons.pixelbrainreader.ui.daily.DailyMoodPoint
import cloud.wafflecommons.pixelbrainreader.ui.theme.ChartPalette

@Composable
fun MoodTrendsCard(
    moodTrend: List<DailyMoodPoint>,
    modifier: Modifier = Modifier
) {
    Column(modifier = Modifier.padding(8.dp)) {
        Spacer(modifier = Modifier.height(24.dp))
        if (moodTrend.isEmpty()) {
            Text("No mood data yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            MoodSparklineContent(moodTrend)
        }
    }
}

@Composable
private fun MoodSparklineContent(trend: List<DailyMoodPoint>) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val dateStyle = MaterialTheme.typography.labelSmall.copy(
        color = onSurfaceVariant,
        fontSize = 10.sp
    )
    val textMeasurer = rememberTextMeasurer()
    
    // Graph Area
    Box(modifier = Modifier
        .fillMaxWidth()
        .height(140.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (trend.size < 2) return@Canvas
            
            val width = size.width
            val height = size.height
            val topPadding = 20.dp.toPx()
            val graphHeight = height - topPadding - 25.dp.toPx()
            
            val stepX = width / (trend.size - 1)
            
            val path = Path()
            
            fun getY(score: Float): Float {
                 val normalized = (score - 1f) / 4f
                 val clamped = normalized.coerceIn(0f, 1f)
                 return (topPadding + graphHeight) - (clamped * graphHeight)
            }

            trend.forEachIndexed { index, point ->
                val x = index * stepX
                val validScore = if (point.score < 1f) 1f else point.score
                val y = getY(validScore)
                
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            
            // Fill
            val fillPath = Path()
            fillPath.addPath(path)
            fillPath.lineTo(width, height)
            fillPath.lineTo(0f, height)
            fillPath.close()
            
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(primaryColor.copy(alpha = 0.2f), Color.Transparent),
                    startY = 0f, 
                    endY = height
                )
            )

            // Line
            drawPath(
                path = path,
                color = primaryColor,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
            
            // --- HR Overlay ---
            fun getHrY(bpm: Int): Float {
                 val minBpm = 50f
                 val maxBpm = 130f
                 val normalized = (bpm.toFloat() - minBpm) / (maxBpm - minBpm)
                 val clamped = normalized.coerceIn(0f, 1f)
                 return (topPadding + graphHeight) - (clamped * graphHeight)
            }
            
            val hrPath = Path()
            var firstHr = true
            trend.forEachIndexed { index, point ->
                if (point.avgBpm > 0) {
                    val x = index * stepX
                    val y = getHrY(point.avgBpm)
                    if (firstHr) {
                        hrPath.moveTo(x, y)
                        firstHr = false
                    } else {
                        hrPath.lineTo(x, y)
                    }
                }
            }
            
            if (!hrPath.isEmpty) {
                drawPath(
                    path = hrPath,
                    color = ChartPalette.HeartRate.copy(alpha = 0.7f),
                    style = Stroke(
                        width = 2.dp.toPx(), 
                        cap = StrokeCap.Round, 
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                    )
                )
            }
            val dateFormatter = java.time.format.DateTimeFormatter.ofPattern("EEE", java.util.Locale.getDefault())
            
            // Points & Overlay text
            trend.forEachIndexed { index, point ->
                val x = index * stepX
                
                // Draw Date Label (X-Axis)
                val dateStr = point.date.format(dateFormatter)
                val dateLayout = textMeasurer.measure(text = dateStr, style = dateStyle)
                val dateX = x - (dateLayout.size.width / 2f)
                val dateY = height - dateLayout.size.height // Very bottom
                
                drawText(
                    textLayoutResult = dateLayout,
                    topLeft = Offset(dateX, dateY)
                )

                // Draw HR Label
                if (point.avgBpm > 0) {
                    val yHr = getHrY(point.avgBpm)
                    val hrText = "${point.avgBpm}"
                    val hrStyle = TextStyle(color = ChartPalette.HeartRate, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    val hrLayout = textMeasurer.measure(text = hrText, style = hrStyle)
                    
                    drawText(
                        textLayoutResult = hrLayout,
                        topLeft = Offset(x - (hrLayout.size.width / 2f), yHr - hrLayout.size.height - 4.dp.toPx())
                    )
                }

                // Draw Mood Point and Label
                if (point.score >= 1f) {
                    val y = getY(point.score)
                    drawCircle(color = surfaceColor, radius = 6.dp.toPx(), center = Offset(x, y))
                    drawCircle(color = primaryColor, radius = 4.dp.toPx(), center = Offset(x, y))
                    
                    val emojiStr = if (point.emoji == "∅") "•" else point.emoji
                    val emojiStyle = TextStyle(fontSize = 14.sp)
                    val emojiLayout = textMeasurer.measure(text = emojiStr, style = emojiStyle)
                    
                    val scoreStr = String.format(java.util.Locale.US, "%.1f", point.score)
                    val scoreStyle = TextStyle(color = primaryColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    val scoreLayout = textMeasurer.measure(text = scoreStr, style = scoreStyle)

                    val emojiX = x - (emojiLayout.size.width / 2f)
                    // Push emoji high enough to fit score below it and above the circle dot
                    val emojiY = y - emojiLayout.size.height - scoreLayout.size.height - 8.dp.toPx()
                    
                    val scoreX = x - (scoreLayout.size.width / 2f)
                    val scoreY = emojiY + emojiLayout.size.height + 2.dp.toPx()
                    
                    drawText(
                        textLayoutResult = emojiLayout,
                        topLeft = Offset(emojiX, emojiY)
                    )
                    
                    drawText(
                        textLayoutResult = scoreLayout,
                        topLeft = Offset(scoreX, scoreY)
                    )
                }
            }
    }
}
}
