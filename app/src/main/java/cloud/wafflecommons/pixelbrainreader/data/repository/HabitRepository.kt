package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.model.HabitLogEntry
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
import cloud.wafflecommons.pixelbrainreader.data.model.HabitType
import androidx.room.withTransaction
import cloud.wafflecommons.pixelbrainreader.data.local.dao.HabitDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.HabitConfigEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.HabitLogEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken

@Singleton
class HabitRepository @Inject constructor(
    private val fileRepository: FileRepository,
    private val habitDao: HabitDao,
    private val choreRepository: ChoreRepository,
    private val jGitProvider: cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider,
    private val database: cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase,
    private val secretManager: cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
) {
    private val jsonParser = Json { 
        ignoreUnknownKeys = true 
        isLenient = true 
        encodeDefaults = true 
        prettyPrint = true
    }
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val habitMutex = Mutex()
    private val habitsDir = "10_Journal/data/habits"
    private val configFile = "10_Journal/data/habits/config.json"

    /**
     * Repository-owned scope for fire-and-forget background work (Git push after
     * a habit log). Outlives any ViewModelScope so a fast user navigation can't
     * cancel the push. SupervisorJob means a single push failure doesn't tear
     * down sibling pushes.
     */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // --- Auto-Initialization ---
    
    suspend fun initialize() = withContext(Dispatchers.IO) {
        val configs = habitDao.getAllConfigs()
        if (configs.isEmpty()) {
            importConfigFromJson()
        }
    }

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
        val color: String = "#FF5722",
        val icon: String = "check_circle",
        val autoSource: String? = null,
        val createdDate: String = "",
        val archived: Boolean = false,
        val sortOrder: Int = 0,
        // New scheduling fields; defaults keep old vault config.json round-tripping across devices.
        val scheduleMode: String = "WEEKLY",
        val intervalCount: Int = 0,
        val intervalUnit: String = "DAY"
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
        database.withTransaction {
            try {
                // Call the new import function
                importConfigFromJson()

                // Sync Logs
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

    suspend fun importConfigFromJson() = withContext(Dispatchers.IO) {
        try {
            val content = fileRepository.readFile(configFile)
            if (content.isNullOrBlank()) return@withContext

            val type = object : TypeToken<List<HabitConfigDto>>() {}.type
            val configs: List<HabitConfigDto> = gson.fromJson(content, type)

            database.withTransaction {
                habitDao.deleteAllConfigsBlocking()
                configs.forEach { config ->
                    habitDao.insertConfigBlocking(
                        HabitConfigEntity(
                            id = config.id,
                            title = config.title,
                            description = config.description,
                            frequency = config.frequency,
                            targetValue = config.targetValue,
                            unit = config.unit,
                            type = config.type,
                            color = config.color,
                            icon = config.icon,
                            autoSource = config.autoSource,
                            createdDate = config.createdDate,
                            archived = config.archived,
                            sortOrder = config.sortOrder,
                            scheduleMode = config.scheduleMode,
                            intervalCount = config.intervalCount,
                            intervalUnit = config.intervalUnit
                        )
                    )
                }
            }
            Log.d("HabitRepository", "Successfully imported configs from JSON.")
        } catch (e: JsonSyntaxException) {
            Log.e("HabitRepository", "JSON Syntax Error in habit config", e)
        } catch (e: Exception) {
            Log.e("HabitRepository", "Error importing config", e)
        }
    }

    suspend fun exportConfigToJson() = withContext(Dispatchers.IO) {
        try {
            fileRepository.createLocalFolder(habitsDir)

            val entities = habitDao.getAllConfigs()
            val activeEntities = entities.filter { !it.archived }
            
            val dtos = activeEntities.map { entity ->
                HabitConfigDto(
                    id = entity.id,
                    title = entity.title,
                    description = entity.description,
                    frequency = entity.frequency,
                    type = entity.type,
                    targetValue = entity.targetValue,
                    unit = entity.unit,
                    color = entity.color,
                    icon = entity.icon,
                    autoSource = entity.autoSource,
                    createdDate = entity.createdDate,
                    archived = entity.archived,
                    sortOrder = entity.sortOrder,
                    scheduleMode = entity.scheduleMode,
                    intervalCount = entity.intervalCount,
                    intervalUnit = entity.intervalUnit
                )
            }

            val jsonOutput = gson.toJson(dtos)
            fileRepository.saveFileLocally(configFile, jsonOutput)
            Log.d("HabitRepository", "Exported configs to JSON successfully")

        } catch (e: Exception) {
            Log.e("HabitRepository", "Error exporting config to JSON", e)
        }
    }
    suspend fun performBulkConfigSync() = withContext(Dispatchers.IO) {
        try {
            // 1. Export both Configs
            exportConfigToJson()
            choreRepository.exportHomeConfigToJson()

            // 2. Add to Git Index (Critical Success Condition 3)
            jGitProvider.addFile(configFile)
            jGitProvider.addFile("10_Journal/data/home/rooms.json")
            jGitProvider.addFile("10_Journal/data/home/chores.json")

            // 3. Commit & Push
            val (owner, repo) = secretManager.getRepoInfo()
            if (!owner.isNullOrBlank() && !repo.isNullOrBlank()) {
                fileRepository.pushDirtyFiles(owner, repo, "chore: Sync configuration (Habits, Home OS)")
            } else {
                Log.e("HabitRepository", "Sync failed: Repo info missing")
            }
        } catch (e: Exception) {
            Log.e("HabitRepository", "Bulk sync failed", e)
            throw e
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

    suspend fun logHabit(date: LocalDate, entry: HabitLogEntry) {
        // Phase 1 — Optimistic UI: write Room FIRST, off the mutex.
        // This is what drives the Habit Flow → Compose recomposition, so the
        // checkbox flips on the next frame instead of after the JSON+network round-trip.
        withContext(Dispatchers.IO) {
            habitDao.insertLog(mapLogToEntity(entry))
        }

        // Phase 2 — Vault persistence (JSON file). Still suspending so a caller
        // that needs to await disk consistency (e.g. before a manual sync) can
        // join here. Held under the mutex to serialize concurrent toggles on
        // the same year file, but no longer blocks the UI update.
        habitMutex.withLock {
            withContext(Dispatchers.IO) {
                val year = date.year
                val logPath = "$habitsDir/log_$year.json"

                val currentJson = fileRepository.readFile(logPath)
                val allLogs: MutableMap<String, MutableList<HabitLogDto>> = try {
                    if (!currentJson.isNullOrBlank()) jsonParser.decodeFromString(currentJson)
                    else mutableMapOf()
                } catch (e: Exception) {
                    mutableMapOf()
                }

                val dto = HabitLogDto(
                    habitId = entry.habitId,
                    date = entry.date,
                    value = entry.value,
                    status = entry.status.name
                )
                val habitLogs = allLogs.getOrPut(entry.habitId) { mutableListOf() }
                habitLogs.removeIf { it.date == entry.date }
                habitLogs.add(dto)

                val newJson = jsonParser.encodeToString(allLogs)
                fileRepository.saveFileLocally(logPath, newJson)
            }
        }

        // Phase 3 — Git push. Fire-and-forget on the repository's own scope so a
        // fast follow-up toggle never waits for the network. Errors are logged;
        // SyncOrchestrator picks up un-pushed work on the next foreground sync.
        backgroundScope.launch {
            try {
                val (owner, repo) = secretManager.getRepoInfo()
                if (!owner.isNullOrBlank() && !repo.isNullOrBlank()) {
                    fileRepository.pushDirtyFiles(
                        owner, repo,
                        "feat(habits): log habit ${entry.habitId} for $date"
                    )
                }
            } catch (e: Exception) {
                Log.e("HabitRepository", "Background push failed for ${entry.habitId}@$date", e)
            }
        }
    }

    /** Most recent COMPLETED date for a habit (this + last year), for INTERVAL scheduling. */
    suspend fun getLastCompletedDate(habitId: String): java.time.LocalDate? = withContext(Dispatchers.IO) {
        val year = java.time.LocalDate.now().year
        val logs = habitDao.getLogsForYear(year.toString()) + habitDao.getLogsForYear((year - 1).toString())
        logs.asSequence()
            .filter { it.habitId == habitId && it.status == HabitStatus.COMPLETED }
            .maxByOrNull { it.date }
            ?.date?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
    }

    suspend fun addHabitConfig(config: HabitConfig) = habitMutex.withLock {
        withContext(Dispatchers.IO) {
            val entity = mapConfigToEntity(config)
            habitDao.insertConfig(entity)
            exportConfigToJson() // Instantly sync changes to JSON vault
        }
    }

    // --- Mappers ---

    private fun mapConfigToEntity(domain: HabitConfig): HabitConfigEntity {
        return HabitConfigEntity(
            id = domain.id,
            title = domain.title,
            description = domain.description,
            frequency = domain.frequency, // List<String> natively supported with converters
            targetValue = domain.targetValue,
            unit = domain.unit,
            type = domain.type.name,
            color = domain.color,
            icon = domain.icon,
            autoSource = domain.autoSource,
            createdDate = domain.createdDate,
            archived = domain.archived,
            sortOrder = domain.sortOrder,
            scheduleMode = domain.scheduleMode,
            intervalCount = domain.intervalCount,
            intervalUnit = domain.intervalUnit
        )
    }

    private fun mapConfigToDomain(entity: HabitConfigEntity): HabitConfig {
        return HabitConfig(
            id = entity.id,
            title = entity.title,
            description = entity.description,
            frequency = entity.frequency, // List<String>
            targetValue = entity.targetValue,
            unit = entity.unit,
            type = try { HabitType.valueOf(entity.type) } catch (e: Exception) { HabitType.BOOLEAN },
            color = entity.color,
            icon = entity.icon,
            autoSource = entity.autoSource,
            createdDate = entity.createdDate,
            archived = entity.archived,
            sortOrder = entity.sortOrder,
            scheduleMode = entity.scheduleMode,
            intervalCount = entity.intervalCount,
            intervalUnit = entity.intervalUnit
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

    suspend fun updateHabitValue(date: LocalDate, habitId: String, value: Double) {
        val configs = getHabitConfigs()
        val habit = configs.find { it.id == habitId } ?: return

        val isCompleted = calculateCompletion(value, habit.targetValue, habit.type)
        val status = if (isCompleted) HabitStatus.COMPLETED else HabitStatus.PARTIAL

        val entry = HabitLogEntry(
            habitId = habitId,
            date = date.toString(),
            value = value,
            status = status
        )
        
        logHabit(date, entry)
        Log.d("HabitRepository", "Updated habit $habitId value to $value (Status: $status)")
    }
}
