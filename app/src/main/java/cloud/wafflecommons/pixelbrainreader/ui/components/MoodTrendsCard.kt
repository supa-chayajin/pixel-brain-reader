package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.ui.daily.DailyMoodPoint

@Composable
fun MoodTrendsCard(
    moodTrend: List<DailyMoodPoint>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Mood Trends",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            if (moodTrend.isEmpty()) {
                Text("No mood data yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                MoodSparklineContent(moodTrend)
            }
        }
    }
}

@Composable
private fun MoodSparklineContent(trend: List<DailyMoodPoint>) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    
    // Graph Area
    Box(modifier = Modifier
        .fillMaxWidth()
        .height(100.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (trend.size < 2) return@Canvas
            
            val width = size.width
            val height = size.height
            val topPadding = 20.dp.toPx()
            val bottomPadding = 30.dp.toPx() // Space for emojis labels drawn relative to X? No, emojis are separate row.
            val graphHeight = height - topPadding - 10.dp.toPx()
            
            val stepX = width / (trend.size - 1)
            
            val path = Path()
            
            fun getY(score: Float): Float {
                 // 5 -> top
                 // 0/1 -> bottom
                 val normalized = (score - 1f) / 4f // 1..5 -> 0..1
                 // Clamp 0..1
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
            fillPath.lineTo(width, height) // bottom right
            fillPath.lineTo(0f, height)    // bottom left
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
            
            // Points
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.GRAY 
                textSize = 30f
                textAlign = android.graphics.Paint.Align.CENTER
            }

            trend.forEachIndexed { index, point ->
                val x = index * stepX
                // Draw point only if valid?
                if (point.score >= 1f) {
                    val y = getY(point.score)
                    drawCircle(color = surfaceColor, radius = 6.dp.toPx(), center = Offset(x, y))
                    drawCircle(color = primaryColor, radius = 4.dp.toPx(), center = Offset(x, y))
                    
                    // Label
                    drawContext.canvas.nativeCanvas.drawText(
                        String.format("%.1f", point.score),
                        x,
                        y - 12.dp.toPx(),
                        textPaint
                    )
                }
            }
        }
    }
    
    // Emoji Row
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        trend.forEach { point ->
            // If placeholder (score 0), show dot? Or just show the emoji passed (which is "∅") from VM?
            // "If a day is missing, show a neutral placeholder (e.g., a subtle gray dot)."
            // VM sets logic: add(DailyMoodPoint(d, 0f, "∅"))
            // So point.emoji is "∅".
            // Let's filter that for a Dot visually.
            if (point.emoji == "∅") {
                Text("•", color = MaterialTheme.colorScheme.outlineVariant)
            } else {
                Text(point.emoji, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
