package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Standalone Mood Entry model.
 */
data class MoodEntry(
    val time: String,
    val score: Int,
    val label: String,
    val activities: List<String>,
    val note: String? = null
)

/**
 * Summary statistics for a daily mood log.
 */
data class MoodSummary(
    val averageScore: Double,
    val mainEmoji: String
)

/**
 * Container for daily mood data stored as JSON.
 */
data class DailyMoodData(
    val date: String,
    val entries: List<MoodEntry>,
    val summary: MoodSummary
)


@Singleton
class MoodRepository @Inject constructor(
    private val fileRepository: FileRepository,
    private val gson: Gson,
    private val secretManager: SecretManager
) {
    private val moodDir = "10_Journal/data/health/mood"

    private val _moods = kotlinx.coroutines.flow.MutableStateFlow<Map<LocalDate, DailyMoodData>>(emptyMap())
    val moods = _moods.asStateFlow()

    init {
        // Preload cache asynchronously
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            getAllMoods()
        }
    }

    /**
     * Loads all mood files into cache.
     */
    suspend fun getAllMoods() {
        val files = fileRepository.getFilesFlow("10_Journal/data/health/mood").first()
        val loadedCache = files
            .filter { it.name.endsWith(".json") }
            .mapNotNull { fileEntity ->
                try {
                    val content = fileRepository.getFileContentFlow(fileEntity.path).first()
                    if (content.isNullOrBlank()) null
                    else {
                        val data = gson.fromJson(content, DailyMoodData::class.java)
                        val recalculated = recalculateDailyData(data)
                        LocalDate.parse(data.date, DateTimeFormatter.ISO_LOCAL_DATE) to recalculated
                    }
                } catch (e: Exception) {
                    null
                }
            }
            .toMap()
        
        _moods.value = loadedCache
    }

    /**
     * Observes mood data for a specific date.
     * Uses Cache first, falling back to file load if missing (and updates cache).
     */
    fun getDailyMood(date: LocalDate): Flow<DailyMoodData?> = _moods.map { it[date] }
        .onStart {
            // If not in cache, try to load specific file
            if (_moods.value[date] == null) {
                 kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                     try {
                         val fileName = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                         val path = "$moodDir/$fileName.json"
                         val content = fileRepository.getFileContentFlow(path).first()
                         if (!content.isNullOrBlank()) {
                             val data = gson.fromJson(content, DailyMoodData::class.java)
                             val recalculated = recalculateDailyData(data)
                             _moods.update { current -> current + (date to recalculated) }
                         }
                     } catch (e: Exception) {
                         // Ignore if file doesn't exist
                     }
                 }
            }
        }

    /**
     * Returns a synthetic "Daily Mood" entry representing the day's average mood
     * and the latest update timestamp.
     */
    suspend fun getMood(date: LocalDate): MoodEntry? {
        val dailyData = _moods.value[date] ?: run {
             // Try load
             val fileName = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
             val path = "$moodDir/$fileName.json"
             try {
                 val content = fileRepository.getFileContentFlow(path).first()
                 if (!content.isNullOrBlank()) {
                     val data = gson.fromJson(content, DailyMoodData::class.java)
                     recalculateDailyData(data) // Return recalculated
                 } else null
             } catch (e: Exception) { null }
        } ?: return null
        
        if (dailyData.entries.isEmpty()) return null

        // 1. Get Latest Timestamp
        // entries are sorted descending in recalculateDailyData
        val latestEntry = dailyData.entries.first() 
        val lastUpdate = latestEntry.time

        // 2. Average already calculated in summary
        val avgScore = dailyData.summary.averageScore
        val roundedScore = avgScore.roundToInt()
        val emoji = calculateDailyEmoji(avgScore)

        return MoodEntry(
            time = lastUpdate,
            score = roundedScore,
            label = emoji,
            activities = latestEntry.activities, // Or combine? User said "Leave empty or take latest note"
            note = latestEntry.note
        )
    }

    /**
     * Adds a new mood entry to the daily log and recalculates the summary.
     */
    suspend fun addEntry(date: LocalDate, entry: MoodEntry) {
        val path = "$moodDir/$date.json"
        
        // 1. Ensure Directory Structure exists in DB metadata
        ensureDirectoryStructure()

        // 2. Load Existing Data (Use Cache or default)
        val currentData = _moods.value[date] ?: run {
             // Fallback to File if not in cache (rare if we observed it, but possible)
             try {
                val content = fileRepository.getFileContentFlow(path).first()
                if (content.isNullOrBlank()) {
                     DailyMoodData(date = date.toString(), entries = emptyList(), summary = MoodSummary(0.0, "😐"))
                } else {
                    gson.fromJson(content, DailyMoodData::class.java)
                }
             } catch (e: Exception) {
                 DailyMoodData(date = date.toString(), entries = emptyList(), summary = MoodSummary(0.0, "😐"))
             }
        }

        // 3. Update entries
        val updatedEntries = currentData.entries + entry
        
        // 4. Recalculate & Sort
        val tempContainer = currentData.copy(entries = updatedEntries)
        val updatedData = recalculateDailyData(tempContainer)

        // 5. Update Cache
        _moods.update { current -> current + (date to updatedData) }

        // 6. Serialize & Save
        val updatedContent = gson.toJson(updatedData)
        fileRepository.saveFileLocally(path, updatedContent)

        // 7. SYNC: Push changes to Remote
        try {
            val (owner, repo) = secretManager.getRepoInfo()
            if (!owner.isNullOrBlank() && !repo.isNullOrBlank()) {
                val message = "Update mood log: $date"
                fileRepository.pushDirtyFiles(owner, repo, message)
            }
        } catch (e: Exception) {
            // Log warning but don't crash, local save is successful
            android.util.Log.w("MoodRepository", "Failed to sync mood entry: ${e.message}")
        }
    }



    private fun recalculateDailyData(data: DailyMoodData): DailyMoodData {
        // 1. Sort descending (Latest first)
        val sortedEntries = data.entries.sortedByDescending { it.time }
        
        // 2. Calculate Average
        val avg = if (sortedEntries.isEmpty()) 0.0 else sortedEntries.map { it.score }.average()
        
        // 3. Determine Emoji
        val emoji = calculateDailyEmoji(avg)
        
        return data.copy(
            entries = sortedEntries,
            summary = MoodSummary(averageScore = avg, mainEmoji = emoji)
        )
    }



    private suspend fun ensureDirectoryStructure() {
        // We create entities for the nested structure to ensure folder navigation works
        fileRepository.createLocalFolder("10_Journal")
        fileRepository.createLocalFolder("10_Journal/data")
        fileRepository.createLocalFolder("10_Journal/data/health")
        fileRepository.createLocalFolder(moodDir)
    }

    private fun calculateDailyEmoji(avg: Double): String {
        // Strict mapping based on Average Score
        return when {
            avg < 1.8 -> "😫"
            avg < 2.6 -> "😞"
            avg < 3.4 -> "😐"
            avg < 4.2 -> "🙂"
            else -> "🤩"
        }
    }
    // Simplified Sparkline Generator
    suspend fun getWeeklySparkline(): String {
        val scores = (0..6).map { i ->
            val date = LocalDate.now().minusDays(6L - i)
            // Synchronously get cached or fetch (pseudo-code, usage of getDailyMood(date) is Flow)
            // For now, let's assume we can get it or return 0.
            try {
                // In a real scenario, we'd query DB. Transforming Flow to suspended value here for simplicity.
                val entry = getDailyMood(date).first()
                if (entry == null) 0 else entry.summary.averageScore.toInt()
            } catch (e: Exception) { 0 }
        }
        
        val bars = listOf(" ", " ", "▂", "▃", "▅", "▆", "▇", "█") 
        // Mapping 0-10 score to index 0-7
        return scores.joinToString("") { score ->
            val index = ((score / 10.0) * (bars.size - 1)).roundToInt().coerceIn(0, bars.size - 1)
            bars[index]
        }
    }
}
