package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.model.HabitLogEntry
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

import kotlinx.coroutines.flow.first

@Singleton
class HabitRepository @Inject constructor(
    private val fileRepository: FileRepository,
    private val gson: Gson,
    private val secretManager: cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
) {
    private val habitMutex = Mutex()
    private val habitsDir = "10_Journal/data/habits"
    private val configFile = "$habitsDir/config.json"

    suspend fun getHabitConfigs(): List<HabitConfig> = withContext(Dispatchers.IO) {
        try {
            val json = fileRepository.getFileContentFlow(configFile).first()
            if (json.isNullOrBlank()) {
                return@withContext emptyList()
            }
            val type = object : TypeToken<List<HabitConfig>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e("LifeOS", "Failed to parse habit configs", e)
            emptyList()
        }
    }

    suspend fun getLogsForYear(year: Int): Map<String, List<HabitLogEntry>> = withContext(Dispatchers.IO) {
        val logPath = "$habitsDir/log_$year.json"
        try {
            val json = fileRepository.getFileContentFlow(logPath).first() ?: return@withContext emptyMap()
            if (json.isBlank()) return@withContext emptyMap()
            
            val type = object : TypeToken<Map<String, List<HabitLogEntry>>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // Inside logHabit...
    suspend fun logHabit(date: LocalDate, entry: HabitLogEntry) = habitMutex.withLock {
        withContext(Dispatchers.IO) {
            val year = date.year
            val logPath = "$habitsDir/log_$year.json"
            
            // 1. Read
            val currentJson = fileRepository.getFileContentFlow(logPath).first()
            val allLogs: MutableMap<String, MutableList<HabitLogEntry>> = try {
                if (!currentJson.isNullOrBlank()) {
                    val type = object : TypeToken<MutableMap<String, MutableList<HabitLogEntry>>>() {}.type
                    gson.fromJson(currentJson, type) ?: mutableMapOf()
                } else mutableMapOf()
            } catch (e: Exception) { mutableMapOf() }
            
            // 2. Update
            val habitLogs = allLogs.getOrPut(entry.habitId) { mutableListOf() }
            habitLogs.removeIf { it.date == entry.date } // Replace existing for day
            habitLogs.add(entry)
            
            // 3. Save
            val newJson = gson.toJson(allLogs)
            fileRepository.saveFileLocally(logPath, newJson)
            
            // 4. Sync (Immediate Push)
            try {
                val (owner, repo) = secretManager.getRepoInfo()
                if (!owner.isNullOrBlank() && !repo.isNullOrBlank()) {
                    fileRepository.pushDirtyFiles(owner, repo, "feat(habits): log habit ${entry.habitId} for $date")
                }
            } catch (e: Exception) {
                Log.e("HabitRepository", "Failed to sync", e)
            }
        }
    }

    suspend fun addHabitConfig(config: HabitConfig) = habitMutex.withLock {
        withContext(Dispatchers.IO) {
            val currentConfigs = getHabitConfigs().toMutableList()
            if (currentConfigs.none { it.id == config.id }) {
                currentConfigs.add(config)
                val json = gson.toJson(currentConfigs)
                // Ensure directory exists
                fileRepository.createLocalFolder(habitsDir)
                fileRepository.saveFileLocally(configFile, json)
            }
        }
    }
}
