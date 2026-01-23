package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.model.HabitLogEntry
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
import cloud.wafflecommons.pixelbrainreader.data.model.HabitType
import cloud.wafflecommons.pixelbrainreader.data.local.dao.HabitDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.HabitConfigEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.HabitLogEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HabitRepository @Inject constructor(
    private val fileRepository: FileRepository,
    private val habitDao: HabitDao,
    private val database: cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase,
    private val secretManager: cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
) {
    private val jsonParser = Json { 
        ignoreUnknownKeys = true 
        isLenient = true 
        encodeDefaults = true 
        prettyPrint = true
    }
    private val habitMutex = Mutex()
    private val habitsDir = "10_Journal/data/habits"
    private val configFile = "$habitsDir/config.json"

    // --- SSOT: DB Flows ---

    fun getHabitConfigsFlow(): Flow<List<HabitConfig>> {
        return habitDao.getAllConfigsFlow().map { entities ->
            entities.map { mapConfigToDomain(it) }
        }
    }

    fun getLogsForYearFlow(year: Int): Flow<Map<String, List<HabitLogEntry>>> {
        return habitDao.getLogsForYearFlow(year.toString()).map { entities ->
             entities.groupBy { it.habitId }
                 .mapValues { (_, logs) -> logs.map { mapLogToDomain(it) } }
        }
    }

    
    // --- DTOs ---
    @Serializable
    data class HabitConfigDto(
        val id: String,
        val title: String,
        val description: String = "",
        val frequency: List<String> = emptyList(),
        val type: String = "BOOLEAN",
        val targetValue: Double = 0.0,
        val unit: String = "",
        val color: String = "#FF5722"
    )
    
    @Serializable
    data class HabitLogDto(
        val habitId: String,
        val date: String,
        val value: Double,
        val status: String
    )

    // --- Bridge Logic ---

    suspend fun syncWithFileSystem() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val root = fileRepository.getLocalFile(habitsDir)
        if (!root.exists()) {
             Log.w("DataSync", "Habits directory not found: ${root.absolutePath}")
             return@withContext
        }
        
        // Atomic Transaction for Data Consistency
        database.runInTransaction {
            try {
                // Step A: Check Config Existence First (Defensive)
                val configFileObj = java.io.File(root, "config.json")
                Log.d("PBR_SYNC", "Checking for config at: ${configFileObj.absolutePath}")
                
                if (!configFileObj.exists()) {
                     Log.e("PBR_SYNC", "CRITICAL: config.json missing in ${root.absolutePath}. Aborting sync to preserve data.")
                     return@runInTransaction
                }
                
                // Step B: Clear State (Only safe now)
                habitDao.deleteAllConfigsBlocking()
                habitDao.deleteAllLogsBlocking()
                
                // Step C: Sync Config
                val configContent = configFileObj.readText()
                if (configContent.isNotBlank()) {
                     val configs: List<HabitConfigDto> = jsonParser.decodeFromString(configContent)
                     configs.forEach { config ->
                         habitDao.insertConfigBlocking(
                             HabitConfigEntity(
                                 id = config.id,
                                 title = config.title,
                                 description = config.description,
                                 frequency = config.frequency.joinToString(","),
                                 targetValue = config.targetValue,
                                 unit = config.unit,
                                 type = config.type,
                                 color = config.color
                             )
                         )
                     }
                     Log.d("PBR_SYNC", "Parsed ${configs.size} definitions from config.json")
                }

                // Step C: Sync Logs (Targeting current year log file primarily, or all?)
                // Prompt: "Parse log_2026.json" (or Year). We'll walk all log_*.json to be safe/complete.
                var logsCount = 0
                root.walk().filter { it.isFile && it.name.startsWith("log_") && it.name.endsWith(".json") }.forEach { file ->
                    try {
                        val content = file.readText()
                        if (content.isNotBlank()) {
                            val logs: Map<String, List<HabitLogDto>> = jsonParser.decodeFromString(content)
                            
                            val entities = logs.flatMap { (habitId, entries) ->
                                entries.map { dto ->
                                    HabitLogEntity(
                                        habitId = habitId,
                                        date = dto.date,
                                        value = dto.value,
                                        status = try { HabitStatus.valueOf(dto.status) } catch (e: Exception) { HabitStatus.FAILED }
                                    )
                                }
                            }
                            if (entities.isNotEmpty()) {
                                habitDao.insertLogsBlocking(entities)
                                logsCount += entities.size
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("PBR_SYNC", "Failed to parse ${file.name}: ${e.message}")
                    }
                }
                Log.d("PBR_SYNC", "Total Logs Imported: $logsCount")
                
            } catch (e: Exception) {
                Log.e("HabitSync", "Transaction Failed", e)
                throw e // Rollback
            }
        }
    }

    // --- Operations ---

    suspend fun getHabitConfigs(): List<HabitConfig> = withContext(Dispatchers.IO) {
        // Fallback to DB if synced, else force sync?
        // Let's rely on DB.
        habitDao.getAllConfigs().map { mapConfigToDomain(it) }
    }

    suspend fun getLogsForYear(year: Int): Map<String, List<HabitLogEntry>> = withContext(Dispatchers.IO) {
        val entities = habitDao.getLogsForYear(year.toString())
        entities.groupBy { it.habitId }
             .mapValues { (_, logs) -> logs.map { mapLogToDomain(it) } }
    }

    suspend fun logHabit(date: LocalDate, entry: HabitLogEntry) = habitMutex.withLock {
        withContext(Dispatchers.IO) {
            val year = date.year
            val logPath = "$habitsDir/log_$year.json"
            
            // Read
            val currentJson = fileRepository.readFile(logPath)
            val allLogs: MutableMap<String, MutableList<HabitLogDto>> = try {
                if (!currentJson.isNullOrBlank()) {
                    jsonParser.decodeFromString(currentJson)
                } else mutableMapOf()
            } catch (e: Exception) { mutableMapOf() }
            
            // Map Domain -> DTO
            val dto = HabitLogDto(
                habitId = entry.habitId,
                date = entry.date,
                value = entry.value,
                status = entry.status.name
            )
            
            // Update List
            val habitLogs = allLogs.getOrPut(entry.habitId) { mutableListOf() }
            habitLogs.removeIf { it.date == entry.date }
            habitLogs.add(dto)
            
            // Save File
            val newJson = jsonParser.encodeToString(allLogs)
            fileRepository.saveFileLocally(logPath, newJson)
            
            // Update DB
            habitDao.insertLog(mapLogToEntity(entry))
            
            // Sync
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
            // Read Config File
            val currentContent = fileRepository.readFile(configFile)
            val currentConfigs: MutableList<HabitConfigDto> = try {
                 if (currentContent != null && currentContent.isNotBlank()) {
                    jsonParser.decodeFromString(currentContent)
                 } else mutableListOf()
            } catch(e: Exception) { mutableListOf() }
            
            if (currentConfigs.none { it.id == config.id }) {
                currentConfigs.add(
                    HabitConfigDto(
                        id = config.id,
                        title = config.title,
                        description = config.description,
                        frequency = config.frequency,
                        type = config.type.name,
                        targetValue = config.targetValue,
                        unit = config.unit,
                        color = config.color
                    )
                )
                val json = jsonParser.encodeToString(currentConfigs)
                
                fileRepository.createLocalFolder(habitsDir)
                fileRepository.saveFileLocally(configFile, json)
                
                // Update DB
                habitDao.insertConfig(mapConfigToEntity(config))
            }
        }
    }

    // --- Mappers ---

    private fun mapConfigToEntity(domain: HabitConfig): HabitConfigEntity {
        return HabitConfigEntity(
            id = domain.id,
            title = domain.title,
            description = domain.description,
            frequency = domain.frequency.joinToString(","),
            targetValue = domain.targetValue,
            unit = domain.unit,
            type = domain.type.name,
            color = domain.color
        )
    }

    private fun mapConfigToDomain(entity: HabitConfigEntity): HabitConfig {
        return HabitConfig(
            id = entity.id,
            title = entity.title,
            description = entity.description,
            frequency = if (entity.frequency.isBlank()) emptyList() else entity.frequency.split(","),
            targetValue = entity.targetValue,
            unit = entity.unit,
            type = try { HabitType.valueOf(entity.type) } catch (e: Exception) { HabitType.BOOLEAN },
            color = entity.color
        )
    }

    private fun mapLogToEntity(domain: HabitLogEntry): HabitLogEntity {
        return HabitLogEntity(
            habitId = domain.habitId,
            date = domain.date,
            value = domain.value,
            status = domain.status
        )
    }

    private fun mapLogToDomain(entity: HabitLogEntity): HabitLogEntry {
        return HabitLogEntry(
            habitId = entity.habitId,
            date = entity.date,
            value = entity.value,
            status = entity.status
        )
    }

    fun calculateCompletion(value: Double, target: Double, type: HabitType): Boolean {
        return if (type == HabitType.MEASURABLE) {
            target > 0 && value >= target
        } else {
            value > 0
        }
    }
}
