package cloud.wafflecommons.pixelbrainreader.widget.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.core.content.ContextCompat
import cloud.wafflecommons.pixelbrainreader.data.gamification.CharacterClass
import cloud.wafflecommons.pixelbrainreader.data.health.HeartRatePoint
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads `filesDir/widget_snapshot.json` for the glanceable widgets. [readSnapshot] returns the raw
 * decoded model (used by the Health widget); [fetchState] additionally rasterizes the avatar and
 * the vitality graph into bitmaps for the Companion widget.
 */
@Singleton
class WidgetDataFetcher @Inject constructor(
    private val chartRenderer: WidgetChartRenderer
) {
    private val snapshotFileName = "widget_snapshot.json"
    private val json = Json { ignoreUnknownKeys = true }

    /** Decode the raw snapshot, or null if it doesn't exist / is corrupt. */
    suspend fun readSnapshot(context: Context): WidgetDataSnapshot? = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, snapshotFileName)
            if (!file.exists()) return@withContext null
            json.decodeFromString<WidgetDataSnapshot>(file.readText())
        } catch (e: Exception) {
            Log.e("WidgetDataFetcher", "readSnapshot failed", e)
            null
        }
    }

    suspend fun fetchState(context: Context): CompanionWidgetState = withContext(Dispatchers.IO) {
        try {
            val snapshot = readSnapshot(context)
                ?: return@withContext CompanionWidgetState(heroClass = "Loading…", isLoading = true)

            val resolvedResId = resolveAvatarResource(snapshot.heroClass)
            val avatarBitmap = context.drawableToBitmap(resolvedResId, 96)
                ?: context.drawableToBitmap(cloud.wafflecommons.pixelbrainreader.R.drawable.hero_novice, 96)

            val hrPoints = snapshot.chartHeartRate.map {
                HeartRatePoint(Instant.ofEpochSecond(it.timestamp), it.value)
            }
            val reconstructMoods = snapshot.chartMoods.map {
                val dt = java.time.LocalDateTime.ofInstant(Instant.ofEpochSecond(it.timestamp), java.time.ZoneId.systemDefault())
                val timeStr = java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(dt)
                MoodEntry(time = timeStr, score = it.score, label = "", activities = emptyList(), note = null)
            }
            val graphBitmap = chartRenderer.renderVitalityGraph(context, hrPoints, reconstructMoods)

            CompanionWidgetState(
                heroClass = snapshot.heroClass.name,
                currentLevel = snapshot.level,
                currentXp = snapshot.currentXp,
                maxXp = snapshot.xpToNext,
                lastMoodEmoji = snapshot.lastMoodEmoji ?: "😐",
                moodEntryCount = snapshot.moodEntryCount,
                stepsCount = snapshot.steps,
                sleepDuration = snapshot.sleep,
                graphBitmap = graphBitmap,
                avatarBitmap = avatarBitmap,
                isLoading = false,
                habitsDone = snapshot.habitsDone,
                habitsTotal = snapshot.habitsTotal,
                tasksDone = snapshot.tasksDone,
                tasksTotal = snapshot.tasksTotal,
                choresDue = snapshot.choresDue,
                activeMinutes = snapshot.activeMinutes,
                caloriesBurned = snapshot.caloriesBurned,
                distanceKm = snapshot.distanceKm,
                hydrationMl = snapshot.hydrationMl,
                mindfulnessMinutes = snapshot.mindfulnessMinutes,
                avgHeartRate = snapshot.avgHeartRate
            )
        } catch (e: Exception) {
            Log.e("CompanionWidget", "Error fetching data", e)
            CompanionWidgetState(heroClass = "Error", isLoading = false)
        }
    }

    private fun resolveAvatarResource(heroClass: CharacterClass): Int = when (heroClass) {
        CharacterClass.WARRIOR -> cloud.wafflecommons.pixelbrainreader.R.drawable.hero_warrior
        CharacterClass.MAGE -> cloud.wafflecommons.pixelbrainreader.R.drawable.hero_sage
        CharacterClass.ROGUE -> cloud.wafflecommons.pixelbrainreader.R.drawable.hero_sentinel
        CharacterClass.CLERIC -> cloud.wafflecommons.pixelbrainreader.R.drawable.hero_sage
        CharacterClass.BARD -> cloud.wafflecommons.pixelbrainreader.R.drawable.hero_creator
        CharacterClass.PEASANT -> cloud.wafflecommons.pixelbrainreader.R.drawable.hero_novice
    }

    private fun Context.drawableToBitmap(resId: Int, sizePx: Int): Bitmap? {
        return try {
            val drawable = ContextCompat.getDrawable(this, resId) ?: return null
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            Log.e("WidgetDataFetcher", "Avatar decode failed: $resId", e)
            null
        }
    }
}
