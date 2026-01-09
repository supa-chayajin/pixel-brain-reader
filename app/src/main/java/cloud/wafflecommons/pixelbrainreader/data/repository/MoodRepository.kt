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
                        val sortedData = data.copy(entries = data.entries.sortedByDescending { it.time })
                        LocalDate.parse(data.date, DateTimeFormatter.ISO_LOCAL_DATE) to sortedData
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
                             val sortedData = data.copy(entries = data.entries.sortedByDescending { it.time })
                             _moods.update { current -> current + (date to sortedData) }
                         }
                     } catch (e: Exception) {
                         // Ignore if file doesn't exist
                     }
                 }
            }
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

        // 3. Update & Sort entries (Descending chronological order)
        val updatedEntries = (currentData.entries + entry).sortedByDescending { it.time }

        // 4. Recalculate Summary
        val avg = if (updatedEntries.isEmpty()) 0.0 else updatedEntries.map { it.score }.average()
        
        // FIX: Calculate Emoji based on AVERAGE strictly as per request
        val emoji = calculateDailyEmoji(avg)
        
        val updatedData = DailyMoodData(
            date = date.toString(),
            entries = updatedEntries,
            summary = MoodSummary(avg, emoji)
        )

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
}
