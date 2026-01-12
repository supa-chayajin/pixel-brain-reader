package cloud.wafflecommons.pixelbrainreader.ui.lifestats

import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.wafflecommons.pixelbrainreader.data.model.LifeStatsLogic
import cloud.wafflecommons.pixelbrainreader.data.model.RpgAttribute
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun RadarChart(
    stats: Map<RpgAttribute, Float>,
    modifier: Modifier = Modifier,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    polyColor: Color = MaterialTheme.colorScheme.primary,
    gridColor: Color = MaterialTheme.colorScheme.outlineVariant
) {
    // Animate values from 0f to target
    val animatedProgress = remember { Animatable(0f) }
    
    LaunchedEffect(stats) {
        animatedProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1000)
        )
    }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = (size.minDimension / 2) * 0.8f // Leave room for labels
        
        // Order of attributes (Clockwise starting top)
        val attributes = listOf(
            RpgAttribute.VIGOR,
            RpgAttribute.INTELLIGENCE,
            RpgAttribute.FAITH,
            RpgAttribute.ENDURANCE,
            RpgAttribute.MIND
        )
        
        val angleStep = (2 * Math.PI / attributes.size).toFloat()
        // Rotate so first point is at top (-90 degrees)
        val startAngle = -Math.PI.toFloat() / 2 

        // 1. Draw Web / Grid
        val steps = 5
        for (i in 1..steps) {
            val r = radius * (i / steps.toFloat())
            drawPentagon(center, r, angleStep, startAngle, gridColor)
        }
        
        // 2. Draw Data Polygon
        val dataPath = Path()
        attributes.forEachIndexed { index, attr ->
            val value = (stats[attr] ?: 0f) * animatedProgress.value
            val r = radius * value.coerceIn(0.1f, 1f) // Valid range
            
            val angle = startAngle + index * angleStep
            val x = center.x + r * cos(angle)
            val y = center.y + r * sin(angle)
            
            if (index == 0) dataPath.moveTo(x, y) else dataPath.lineTo(x, y)
        }
        dataPath.close()
        
        drawPath(
            path = dataPath,
            color = polyColor.copy(alpha = 0.4f)
        )
        drawPath(
            path = dataPath,
            color = polyColor,
            style = Stroke(width = 3.dp.toPx())
        )

        // 3. Draw Labels
        drawLabels(
            drawScope = this,
            center = center,
            radius = radius * 1.15f,
            attributes = attributes,
            angleStep = angleStep,
            startAngle = startAngle,
            color = labelColor
        )
    }
}

private fun DrawScope.drawPentagon(
    center: Offset,
    radius: Float,
    angleStep: Float,
    startAngle: Float,
    color: Color
) {
    val path = Path()
    for (i in 0 until 5) {
        val angle = startAngle + i * angleStep
        val x = center.x + radius * cos(angle)
        val y = center.y + radius * sin(angle)
        
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        
        // Draw spoke
        drawLine(
            color = color,
            start = center,
            end = Offset(x, y),
            strokeWidth = 1.dp.toPx()
        )
    }
    path.close()
    drawPath(path, color, style = Stroke(width = 1.dp.toPx()))
}

private fun drawLabels(
    drawScope: DrawScope,
    center: Offset,
    radius: Float,
    attributes: List<RpgAttribute>,
    angleStep: Float,
    startAngle: Float,
    color: Color
) {
    val paint = Paint().apply {
        isAntiAlias = true
        textSize = 12.sp.toPx(drawScope.density) // Need density context
        this.color = color.toArgb()
        textAlign = Paint.Align.CENTER
    }

    attributes.forEachIndexed { index, attr ->
        val angle = startAngle + index * angleStep
        val x = center.x + radius * cos(angle)
        // Adjust Y for text alignment slightly
        val y = center.y + radius * sin(angle) + (paint.textSize / 3) 
        
        val label = LifeStatsLogic.getAttributeName(attr)
        
        drawScope.drawContext.canvas.nativeCanvas.drawText(label, x, y, paint)
    }
}

// Helper for sp to px
private fun androidx.compose.ui.unit.TextUnit.toPx(density: Float): Float {
    return this.value * density
}
