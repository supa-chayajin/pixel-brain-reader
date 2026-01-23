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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Standalone Mood Entry model (Domain).
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

// --- DTOs (Internal Serialization) ---
@Serializable
data class MoodEntryDto(
    val time: String,
    val score: Int,
    val label: String,
    val activities: List<String> = emptyList(),
    val note: String? = null
)

@Serializable
data class MoodSummaryDto(
    val averageScore: Double,
    val mainEmoji: String
)

@Serializable
data class DailyMoodDataDto(
    val date: String = "",
    val entries: List<MoodEntryDto> = emptyList(),
    val summary: MoodSummaryDto? = null
)

@Singleton
class MoodRepository @Inject constructor(
    private val fileRepository: FileRepository,
    private val moodDao: cloud.wafflecommons.pixelbrainreader.data.local.dao.MoodDao,
    private val database: cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase,
    private val secretManager: SecretManager
) {
    private val moodDir = "10_Journal/data/health/mood"
    
    private val jsonParser = Json { 
        ignoreUnknownKeys = true 
        isLenient = true 
        prettyPrint = true
    }

    // SSOT: Room Database
    fun getMoodFlow(): Flow<List<cloud.wafflecommons.pixelbrainreader.data.local.entity.MoodEntity>> {
         return moodDao.getAllMoods()
    }

    /**
     * Sync Bridge: Scans local JSON files and upserts to Room.
     * Called by Workers or on App Start.
     */
    /**
     * Sync Bridge: Scans local JSON files and upserts to Room.
     * Called by Workers or on App Start.
     */
    suspend fun syncWithFileSystem() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val root = fileRepository.getLocalFile(moodDir)
        if (!root.exists()) {
             Log.w("DataSync", "Mood directory not found: ${root.absolutePath}")
             return@withContext
        }
        
        Log.d("PBR_SYNC", "Scanning mood folder: ${root.absolutePath}")
        
        // Atomic Transaction (Blocking - Safe in Dispatchers.IO)
        database.runInTransaction {
            var parsedCount = 0
            var filesCount = 0
            root.walk().filter { it.isFile && it.name.endsWith(".json") }.forEach { file ->
                try {
                    val content = file.readText()
                    if (content.isNotBlank()) {
                         val data = jsonParser.decodeFromString<DailyMoodDataDto>(content)
                         
                         // 1. Validate Date
                         var dateKey = data.date
                         if (dateKey.isBlank()) {
                             dateKey = file.nameWithoutExtension.take(10) // Fallback to filename (YYYY-MM-DD)
                         }
                         
                         // 2. Map Entries
                         if (data.entries.isNotEmpty()) {
                            data.entries.forEach { entry ->
                                 // Calc Timestamp
                                 val timeStr = if (entry.time.length == 5) entry.time else "12:00" // Simple Validation
                                 val localDateTime = java.time.LocalDateTime.parse("${dateKey}T${timeStr}")
                                 val timestamp = localDateTime.atZone(java.time.ZoneId.systemDefault()).toEpochSecond() * 1000

                                 val entity = cloud.wafflecommons.pixelbrainreader.data.local.entity.MoodEntity(
                                     timestamp = timestamp,
                                     date = dateKey,
                                     time = timeStr,
                                     score = entry.score,
                                     label = entry.label,
                                     activities = entry.activities.joinToString(","),
                                     note = entry.note
                                 )
                                 moodDao.insertMoodBlocking(entity)
                                 parsedCount++
                            }
                            filesCount++
                         }
                    }
                } catch (e: Exception) {
                    Log.e("PBR_SYNC", "Failed to parse ${file.name}: ${e.message}")
                }
            }
            Log.d("PBR_SYNC", "Imported $parsedCount mood entries (from $filesCount files)")
        }
    }

    /**
     * Observes mood data for a specific date (Reactive from DB).
     */
    fun getDailyMood(date: LocalDate): Flow<DailyMoodData?> {
         return moodDao.getMood(date.toString()).map { entities ->
             if (entities.isNotEmpty()) mapToDomain(entities, date.toString()) else null
         }
    }

    /**
     * Legacy synchronous accessor for Stats
     */
    suspend fun getMood(date: LocalDate): MoodEntry? {
         // Return the LATEST entry for the day based on time
         return getDailyMood(date).first()?.entries?.maxByOrNull { it.time }
    }

    /**
     * Adds a new mood entry:
     */
    suspend fun addEntry(date: LocalDate, entry: MoodEntry) {
        val path = "$moodDir/$date.json"
        
        // 1. Read existing JSON from Disk
        val currentContent = try { fileRepository.readFile(path) } catch(e:Exception) { null }
        val currentData = try {
             if (!currentContent.isNullOrBlank()) {
                 jsonParser.decodeFromString<DailyMoodDataDto>(currentContent)
             } else {
                 DailyMoodDataDto(date = date.toString())
             }
        } catch (e: Exception) {
             DailyMoodDataDto(date = date.toString())
        }

        // 2. Add
        val newDto = MoodEntryDto(entry.time, entry.score, entry.label, entry.activities, entry.note)
        val updatedEntries = currentData.entries + newDto
        
        // 3. Calc Summary
        val avg = updatedEntries.map { it.score }.average()
        val summary = MoodSummaryDto(avg, calculateDailyEmoji(avg))
        
        val updatedData = currentData.copy(entries = updatedEntries, summary = summary)

        // 4. Save
        val jsonString = jsonParser.encodeToString(updatedData)
        fileRepository.saveFileLocally(path, jsonString)

        // 5. Update DB
        // 5. Update DB
        val localDateTime = java.time.LocalDateTime.parse("${date}T${entry.time}")
        val timestamp = localDateTime.atZone(java.time.ZoneId.systemDefault()).toEpochSecond() * 1000

        val entity = cloud.wafflecommons.pixelbrainreader.data.local.entity.MoodEntity(
             timestamp = timestamp,
             date = date.toString(),
             time = entry.time,
             score = entry.score,
             label = entry.label,
             activities = entry.activities.joinToString(","),
             note = entry.note
        )
        moodDao.insertMood(entity)
        
        // 6. Push
        try {
            val (owner, repo) = secretManager.getRepoInfo()
            if (!owner.isNullOrBlank() && !repo.isNullOrBlank()) {
                fileRepository.pushDirtyFiles(owner, repo, "feat(health): update mood $date")
            }
        } catch (e: Exception) {}
    }

    // --- Mappers ---
    
    private fun mapToDomain(entities: List<cloud.wafflecommons.pixelbrainreader.data.local.entity.MoodEntity>, dateIdx: String): DailyMoodData {
         val entries = entities.map { 
             MoodEntry(it.time, it.score, it.label, it.activities.split(",").filter { s -> s.isNotBlank() }, it.note)
         }
         val avg = if (entries.isNotEmpty()) entries.map { it.score }.average() else 0.0
         val summary = MoodSummary(avg, calculateDailyEmoji(avg))
         return DailyMoodData(dateIdx, entries, summary)
    }

    private fun calculateDailyEmoji(avg: Double): String {
        return when {
            avg < 1.8 -> "😫"
            avg.isNaN() -> "😐"
            avg < 2.6 -> "😞"
            avg < 3.4 -> "😐"
            avg < 4.2 -> "🙂"
            else -> "🤩"
        }
    }

    suspend fun getWeeklySparkline(): String {
        val all = moodDao.getAllMoods().first()
        // Map: Date -> Average Score
        val map = all.groupBy { it.date }.mapValues { (_, list) -> 
            list.map { it.score }.average()
        }
        
        val scores = (0..6).map { i ->
             val d = LocalDate.now().minusDays(6L - i).toString()
             map[d]?.roundToInt() ?: 0
        }
        
        val bars = listOf(" ", " ", "▂", "▃", "▅", "▆", "▇", "█")
        return scores.joinToString("") { score ->
            val index = ((score / 10.0) * (bars.size - 1)).roundToInt().coerceIn(0, bars.size - 1)
            bars[index]
        }
    }
}
