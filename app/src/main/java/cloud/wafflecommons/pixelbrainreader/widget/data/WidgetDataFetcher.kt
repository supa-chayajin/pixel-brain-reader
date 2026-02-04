package cloud.wafflecommons.pixelbrainreader.widget.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.core.content.ContextCompat
import cloud.wafflecommons.pixelbrainreader.data.health.HeartRatePoint
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetDataFetcher @Inject constructor(
    private val chartRenderer: WidgetChartRenderer
) {
    private val snapshotFileName = "widget_snapshot.json"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchState(context: Context): CompanionWidgetState = withContext(Dispatchers.IO) {
        try {
            // Read Snapshot File
            val file = File(context.filesDir, snapshotFileName)
            if (!file.exists()) {
                return@withContext CompanionWidgetState(
                    heroClass = "Loading...",
                    isLoading = true
                )
            }

            val content = file.readText()
            val snapshot = try {
                json.decodeFromString<WidgetDataSnapshot>(content)
            } catch (e: Exception) {
                null
            } ?: return@withContext CompanionWidgetState("Error")

            // Map Snapshot to State for UI
            
            // Avatar
            val resolvedResId = resolveAvatarResource(snapshot.heroClass)
            val avatarBitmap = context.drawableToBitmap(resolvedResId, 96) ?: context.drawableToBitmap(cloud.wafflecommons.pixelbrainreader.R.drawable.hero_novice, 96)
            
            // Chart Mapping
            val hrPoints = snapshot.chartHeartRate.map { 
                HeartRatePoint(Instant.ofEpochSecond(it.timestamp), it.value) 
            }
            
            val reconstructMoods = snapshot.chartMoods.map {
                 val dt = java.time.LocalDateTime.ofInstant(Instant.ofEpochSecond(it.timestamp), java.time.ZoneId.systemDefault())
                 val timeStr = java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(dt)
                 MoodEntry(
                    time = timeStr,
                    score = it.score,
                    label = "",
                    activities = emptyList(),
                    note = null
                 )
            }

            val graphBitmap = chartRenderer.renderVitalityGraph(
                context, 
                hrPoints, 
                reconstructMoods
            )

            CompanionWidgetState(
                heroClass = snapshot.heroClass.name,
                currentLevel = snapshot.level,
                currentXp = snapshot.currentXp,
                maxXp = snapshot.xpToNext,
                lastMoodEmoji = snapshot.lastMoodEmoji ?: "😐",
                stepsCount = snapshot.steps,
                sleepDuration = snapshot.sleep,
                graphBitmap = graphBitmap,
                avatarBitmap = avatarBitmap,
                isLoading = false
            )

        } catch (e: Exception) {
            Log.e("CompanionWidget", "Error fetching data", e)
            CompanionWidgetState(heroClass = "Error", isLoading = false)
        }
    }

    private fun resolveAvatarResource(heroClass: cloud.wafflecommons.pixelbrainreader.data.gamification.CharacterClass): Int {
        return when (heroClass) {
            cloud.wafflecommons.pixelbrainreader.data.gamification.CharacterClass.WARRIOR -> cloud.wafflecommons.pixelbrainreader.R.drawable.hero_warrior
            cloud.wafflecommons.pixelbrainreader.data.gamification.CharacterClass.MAGE -> cloud.wafflecommons.pixelbrainreader.R.drawable.hero_sage
            cloud.wafflecommons.pixelbrainreader.data.gamification.CharacterClass.ROGUE -> cloud.wafflecommons.pixelbrainreader.R.drawable.hero_sentinel
            cloud.wafflecommons.pixelbrainreader.data.gamification.CharacterClass.CLERIC -> cloud.wafflecommons.pixelbrainreader.R.drawable.hero_sage
            cloud.wafflecommons.pixelbrainreader.data.gamification.CharacterClass.BARD -> cloud.wafflecommons.pixelbrainreader.R.drawable.hero_creator
            cloud.wafflecommons.pixelbrainreader.data.gamification.CharacterClass.PEASANT -> cloud.wafflecommons.pixelbrainreader.R.drawable.hero_novice
            else -> cloud.wafflecommons.pixelbrainreader.R.drawable.hero_novice
        }
    }

    private fun Context.drawableToBitmap(resId: Int, sizePx: Int): Bitmap? {
        return try {
            // 2. Decode using Canvas (Robust for Vectors & XML)
            val drawable = ContextCompat.getDrawable(this, resId) ?: return null
            
            // 3. Size: sizePx for high density crispness
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
            Log.d("WidgetDataFetcher", "Avatar decoded: $resId")
            
            bitmap
        } catch (e: Exception) {
            Log.e("WidgetDataFetcher", "Avatar decode failed: $resId", e)
            null
        }
    }
}
