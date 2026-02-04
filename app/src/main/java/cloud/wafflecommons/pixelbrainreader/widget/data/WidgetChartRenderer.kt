package cloud.wafflecommons.pixelbrainreader.widget.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import cloud.wafflecommons.pixelbrainreader.data.health.HeartRatePoint
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodEntry
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class WidgetChartRenderer @Inject constructor() {

    fun renderVitalityGraph(
        context: Context,
        heartRates: List<HeartRatePoint>,
        moods: List<MoodEntry>
    ): Bitmap? {
        // Even if empty heart rates, we might want to show empty graph line? 
        // No, return null to show "No Data" state if absolutely nothing.
        if (heartRates.isEmpty() && moods.isEmpty()) return null

        val density = context.resources.displayMetrics.density
        // Widget Width usually around 300dp for 4 columns
        val width = (300 * density).toInt()
        val height = (80 * density).toInt()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // --- Style Config (LifeStats Replica) ---
        // Vitality Line: Warm Pink/Red Gradient
        val colorStart = Color.parseColor("#FFD8E4") // Pink
        val colorEnd = Color.parseColor("#6750A4")   // Purple
        
        // 1. Data Prep
        // Filter valid HR
        val validHr = heartRates.filter { it.avgBpm > 0 }
        
        if (validHr.isNotEmpty()) {
            val minBpm = validHr.minOf { it.avgBpm }.toFloat().coerceAtLeast(40f) // Floor at 40
            val maxBpm = validHr.maxOf { it.avgBpm }.toFloat().coerceAtLeast(minBpm + 20f) // Min range 20
            
            val sortedHr = validHr.sortedBy { it.timestamp }
            val startTime = sortedHr.first().timestamp.epochSecond
            val endTime = sortedHr.last().timestamp.epochSecond
            val timeRange = (endTime - startTime).coerceAtLeast(1) // Avoid div/0
            
            val path = Path()
            val fillPath = Path()
            
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 3f * density
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                pathEffect = CornerPathEffect(12f * density)
                shader = LinearGradient(0f, 0f, width.toFloat(), 0f, colorStart, colorEnd, Shader.TileMode.CLAMP)
            }
            
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    Color.argb(100, Color.red(colorStart), Color.green(colorStart), Color.blue(colorStart)),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
            }

            sortedHr.forEachIndexed { index, point ->
                val normX = (point.timestamp.epochSecond - startTime) / timeRange.toFloat()
                val x = normX * width
                
                // Y Scaling: Invert (High BPM = Low Y)
                val normY = (point.avgBpm.toFloat() - minBpm) / (maxBpm - minBpm)
                val drawingHeight = height * 0.7f // Leave space for mood dots
                val y = (height * 0.85f) - (normY * drawingHeight)
                
                if (index == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, height.toFloat())
                    fillPath.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            
            // Finish Fill
            val lastX = width.toFloat() // Extend to edge? Or keep at last data point?
            // Let's close at last point
            val finalPoint = sortedHr.last()
            val finalX = width.toFloat() // We stretch to fit width? No, normX=1.0 at end.
            
            // Close the fill path down to bottom
            fillPath.lineTo(width.toFloat(), height.toFloat())
            fillPath.close()

            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(path, linePaint)
        }

        // 2. Draw Mood Emojis
        // Moods are simpler points. We need to map them to X axis.
        // Assuming MoodEntry time is HH:mm and Graph is last 6H.
        // This is tricky without dates. 
        // Simplification: Just draw them if we have them, evenly spaced? NO, inaccurate.
        // Better: We only draw if we can map effectively.
        // Since `moods` passed in are "Daily" entries, and graph is "Last 6H", there is a mismatch.
        // User asked: "Replicate LifeStats chart" which implies "Daily" view usually.
        // But logic says "readHeartRateHistory(Last 6h)".
        // Compromise: We draw ONLY moods that happened in the last 6H (approx).
        // Since we don't have full timestamps on MoodEntry (just HH:mm string), we rely on "Today".
        // If current time is 15:00, 6h ago is 09:00.
        // We parse MoodEntry.time and see if it fits.
        
        if (moods.isNotEmpty()) {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 14f * density
                textAlign = Paint.Align.CENTER
            }
            
            // We need a rough X mapping.
            // Let's just place them relative to "Now".
            // If empty HR, we can't map to X axis of HR.
            // If HR exists, we use its time window.
            
            if (validHr.isNotEmpty()) {
                val startTime = validHr.minOf { it.timestamp.epochSecond }
                val endTime = validHr.maxOf { it.timestamp.epochSecond }
                val range = endTime - startTime
                
                // We need date of "Today" to combine with HH:mm
                val todayStr = java.time.LocalDate.now().toString()
                
                moods.forEach { mood ->
                    try {
                        val dt = java.time.LocalDateTime.parse("${todayStr}T${mood.time}")
                        val epoch = dt.atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
                        
                        // Check if inside graph window
                        if (epoch in startTime..endTime) {
                            val normX = (epoch - startTime) / range.toFloat()
                            val x = normX * width
                            // Draw at top
                            canvas.drawText(mood.label, x, 16f * density, textPaint) // Label is emoji? "label" in MoodEntry is text.
                            // MoodEntry definition: val label: String (e.g. "Happy"), val score: Int.
                            // The emoji is in summary? 
                            // Wait, MoodEntry doesn't have emoji field directly in my view of `MoodRepository.kt`.
                            // It calculates `mainEmoji` for summary only.
                            // I should probably use a helper to get emoji from score.
                            val emoji = getEmojiForScore(mood.score)
                            canvas.drawText(emoji, x, 16f * density, textPaint)
                        }
                    } catch (e: Exception) {
                        // ignore parse errors
                    }
                }
            }
        }

        return bitmap
    }
    
    private fun getEmojiForScore(score: Int): String {
        return when (score) {
            1 -> "😞"
            2 -> "😐"
            3 -> "🙂"
            4 -> "😀"
            5 -> "🤩"
            else -> "😐"
        }
    }
}
