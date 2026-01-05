package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
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

    /**
     * Observes mood data for a specific date.
     * Path: 10_Journal/data/health/mood/{date}.json
     */
    fun getDailyMood(date: LocalDate): Flow<DailyMoodData?> = flow {
        try {
            val fileName = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val path = "$moodDir/$fileName.json"

            // .first() will throw NoSuchElementException if the file doesn't exist and the flow is empty.
            val content = fileRepository.getFileContentFlow(path).first()

            if (content.isNullOrBlank()) {
                emit(null) // File is empty or just whitespace
                return@flow
            }

            val data = gson.fromJson(content, DailyMoodData::class.java)
            emit(data)
        } catch (e: java.util.NoSuchElementException) {
            // This is expected if the file doesn't exist
            emit(null)
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            Log.e("MoodRepo", "Error reading mood for $date", e)
            emit(null) // Emit null on corruption or other errors
        }
    }

    /**
     * Adds a new mood entry to the daily log and recalculates the summary.
     */
    suspend fun addEntry(date: LocalDate, entry: MoodEntry) {
        val path = "$moodDir/$date.json"
        
        // 1. Ensure Directory Structure exists in DB metadata
        ensureDirectoryStructure()

        // 2. Load Existing Data
        val currentData = try {
            val content = fileRepository.getFileContentFlow(path).first()
            if (content.isNullOrBlank()) {
                DailyMoodData(date = date.toString(), entries = emptyList(), summary = MoodSummary(0.0, "😐"))
            } else {
                gson.fromJson(content, DailyMoodData::class.java)
            }
        } catch (e: Exception) {
            DailyMoodData(date = date.toString(), entries = emptyList(), summary = MoodSummary(0.0, "😐"))
        }

        // 3. Update & Sort entries (Descending chronological order)
        val updatedEntries = (currentData.entries + entry).sortedByDescending { it.time }

        // 4. Recalculate Summary
        val avg = if (updatedEntries.isEmpty()) 0.0 else updatedEntries.map { it.score }.average()
        val latestScore = updatedEntries.firstOrNull()?.score
        val emoji = calculateDailyEmoji(avg, latestScore)
        
        val updatedData = DailyMoodData(
            date = date.toString(),
            entries = updatedEntries,
            summary = MoodSummary(avg, emoji)
        )

        // 5. Serialize & Save
        val updatedContent = gson.toJson(updatedData)
        fileRepository.saveFileLocally(path, updatedContent)

        // 6. SYNC: Push changes to Remote
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

    private fun calculateDailyEmoji(avg: Double, latestEntryScore: Int? = null): String {
        // Fix: If we have a latest entry, use its label/score directly for the "Current Mood" feel
        // But the summary is "Daily Summary", so maybe Average is better? 
        // User Request: "Daily Note Dashboard displays the FIRST (08:00) instead of LAST (most recent)."
        // User requests the Dashboard emoji to reflect the Latest.
        
        if (latestEntryScore != null) {
            return when(latestEntryScore) {
                1 -> "😫"
                2 -> "😞"
                3 -> "😐"
                4 -> "🙂"
                5 -> "🤩"
                else -> "😐"
            }
        }

        // Fallback to average if needed
        return when {
            avg < 1.8 -> "😫"
            avg < 2.6 -> "😞"
            avg < 3.4 -> "😐"
            avg < 4.2 -> "🙂"
            else -> "🤩"
        }
    }
}
