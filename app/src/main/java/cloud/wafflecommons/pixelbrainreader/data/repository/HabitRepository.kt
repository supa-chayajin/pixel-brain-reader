package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.local.dao.HabitDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.HabitConfigEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.HabitLogEntity
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.model.HabitLogEntry
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
import cloud.wafflecommons.pixelbrainreader.data.model.HabitType
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetUpdateManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HabitRepository @Inject constructor(
    private val fileRepository: FileRepository,
    private val habitDao: HabitDao,
    private val choreRepository: ChoreRepository,
    private val secretManager: SecretManager,
    private val widgetUpdateManager: WidgetUpdateManager,
    private val pushScheduler: VaultPushScheduler
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

    // --- Auto-Initialization ---

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (habitDao.getAllConfigs().isEmpty()) {
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
        return habitDao.getLogsForYearFlow(year.toString()).map { groupByHabit(it) }
    }

    /** Logs of an inclusive date range, grouped by habit id. */
    fun getLogsBetweenFlow(start: LocalDate, end: LocalDate): Flow<Map<String, List<HabitLogEntry>>> {
        return habitDao.getLogsBetweenFlow(start.toString(), end.toString()).map { groupByHabit(it) }
    }

    private fun groupByHabit(entities: List<HabitLogEntity>): Map<String, List<HabitLogEntry>> =
        entities.groupBy { it.habitId }.mapValues { (_, logs) -> logs.map { mapLogToDomain(it) } }

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
        // New scheduling fields. NULLABLE on purpose: Gson (used by importConfigFromJson) does
        // NOT apply Kotlin default values, so an OLD config.json without these keys deserializes
        // them as null. Coalesced to defaults at the entity boundary below.
        val scheduleMode: String? = null,
        val intervalCount: Int = 0,
        val intervalUnit: String? = null
    )

    @Serializable
    data class HabitLogDto(
        val habitId: String,
        val date: String,
        val value: Double,
        val status: String
    )

    // --- Bridge Logic ---

    /**
     * Sync Bridge: reconciles the vault's habit config and yearly log files into Room.
     *
     * Files are parsed OUTSIDE any transaction (the walk used to hold Room's writer lock) and only
     * rows that actually differ are written: an unchanged vault causes no Room invalidation, where
     * every reconcile (startup, each sync) used to rewrite every habit row and recompute the Habits
     * and Stats screens.
     */
    suspend fun syncWithFileSystem() = withContext(Dispatchers.IO) {
        val root = fileRepository.getLocalFile(habitsDir)
        if (!root.exists()) {
            Log.d("DataSync", "Habits directory not found")
            return@withContext
        }

        importConfigFromJson()

        val parsed = LinkedHashMap<Pair<String, String>, HabitLogEntity>()
        root.walk()
            .filter { it.isFile && it.name.startsWith("log_") && it.name.endsWith(".json") }
            .forEach { file ->
                try {
                    val content = file.readText()
                    if (content.isBlank()) return@forEach
                    val logs: Map<String, List<HabitLogDto>> = jsonParser.decodeFromString(content)
                    logs.forEach { (habitId, entries) ->
                        entries.forEach { dto ->
                            parsed[habitId to dto.date] = HabitLogEntity(
                                habitId = habitId,
                                date = dto.date,
                                value = dto.value,
                                status = parseStatus(dto.status)
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PBR_SYNC", "Failed to parse ${file.name}: ${e.message}")
                }
            }
        if (parsed.isEmpty()) return@withContext

        val years = parsed.values.map { it.date.take(4) }.toSet()
        val existing = years.flatMap { habitDao.getLogsForYear(it) }.associateBy { it.habitId to it.date }
        val changed = parsed.values.filter { existing[it.habitId to it.date] != it }
        if (changed.isNotEmpty()) {
            habitDao.insertLogs(changed)
            Log.d("PBR_SYNC", "Reconciled ${changed.size} habit log(s)")
        }
    }

    suspend fun importConfigFromJson() = withContext(Dispatchers.IO) {
        try {
            val content = fileRepository.readFile(configFile)
            if (content.isNullOrBlank()) return@withContext

            val type = object : TypeToken<List<HabitConfigDto>>() {}.type
            val configs: List<HabitConfigDto> = gson.fromJson(content, type) ?: return@withContext
            val entities = configs.map { it.toEntity() }
            // Unchanged → no delete + re-insert, so no invalidation of every habit observer.
            if (entities.toSet() == habitDao.getAllConfigs().toSet()) return@withContext

            habitDao.replaceAllConfigs(entities)
            Log.d("HabitRepository", "Imported ${entities.size} habit config(s) from JSON")
        } catch (e: JsonSyntaxException) {
            Log.e("HabitRepository", "JSON Syntax Error in habit config", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("HabitRepository", "Error importing config", e)
        }
    }

    suspend fun exportConfigToJson() = withContext(Dispatchers.IO) {
        try {
            fileRepository.createLocalFolder(habitsDir)

            val dtos = habitDao.getAllConfigs()
                .map { entity ->
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

            fileRepository.saveFileLocally(configFile, gson.toJson(dtos))
            Log.d("HabitRepository", "Exported configs to JSON successfully")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("HabitRepository", "Error exporting config to JSON", e)
        }
    }

    /**
     * Settings "Export all to vault": writes habits + rooms + chores JSON, then runs a full
     * commit → pull → push. Throws on any failure so the Settings screen reports it — the previous
     * push-only version ignored the push result and failed silently whenever the remote was ahead.
     */
    suspend fun performBulkConfigSync() = withContext(Dispatchers.IO) {
        exportConfigToJson()
        choreRepository.exportHomeConfigToJson()

        val (owner, repo) = secretManager.getRepoInfo()
        if (owner.isNullOrBlank() || repo.isNullOrBlank()) {
            throw IllegalStateException("Repository is not configured")
        }
        fileRepository.syncRepository(owner, repo).getOrThrow()
    }

    // --- Operations ---

    suspend fun getHabitConfigs(): List<HabitConfig> = withContext(Dispatchers.IO) {
        habitDao.getAllConfigs().map { mapConfigToDomain(it) }
    }

    suspend fun getLogsForYear(year: Int): Map<String, List<HabitLogEntry>> = withContext(Dispatchers.IO) {
        groupByHabit(habitDao.getLogsForYear(year.toString()))
    }

    suspend fun logHabit(date: LocalDate, entry: HabitLogEntry) = logHabits(date, listOf(entry))

    /**
     * Records log entries of ONE day: Room first (the checkbox flips on the next frame), then the
     * vault's yearly JSON, then one widget refresh and one batched push request for the whole batch.
     */
    suspend fun logHabits(date: LocalDate, entries: List<HabitLogEntry>) {
        if (entries.isEmpty()) return

        // Phase 1 — Optimistic UI: write Room FIRST, off the mutex.
        withContext(Dispatchers.IO) {
            habitDao.insertLogs(entries.map { mapLogToEntity(it) })
        }

        // Phase 2 — Vault persistence (yearly JSON). The mutex serializes concurrent writers of the
        // same year file (app + widget), so they can't drop each other's entries.
        val savedToVault = try {
            habitMutex.withLock {
                withContext(Dispatchers.IO) {
                    val logPath = "$habitsDir/log_${date.year}.json"
                    val allLogs = readYearLogsForUpdate(logPath, date.year)
                    entries.forEach { entry ->
                        val habitLogs = allLogs.getOrPut(entry.habitId) { mutableListOf() }
                        habitLogs.removeAll { it.date == entry.date }
                        habitLogs.add(HabitLogDto(entry.habitId, entry.date, entry.value, entry.status.name))
                    }
                    fileRepository.saveFileLocally(logPath, jsonParser.encodeToString(allLogs))
                }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Room holds the entries, so every screen and widget stays correct; only the vault copy
            // misses them.
            Log.e("HabitRepository", "Failed to write the habit log of $date to the vault", e)
            false
        }

        // Refresh widgets so the Habits/Companion widgets reflect the new completion state.
        runCatching { widgetUpdateManager.scheduleSnapshotUpdate() }

        // Phase 3 — Git: queued and batched in the background; a toggle never waits for the network.
        if (savedToVault) pushScheduler.requestPush()
    }

    /**
     * The year's logs for a read-modify-write. An unreadable file is never overwritten with one
     * holding only the new entries — that wiped the whole year's history: it is moved aside, and the
     * year is rebuilt from Room, which holds every log previously imported from it.
     */
    private suspend fun readYearLogsForUpdate(path: String, year: Int): MutableMap<String, MutableList<HabitLogDto>> {
        val content = fileRepository.readFile(path)
        if (content.isNullOrBlank()) return mutableMapOf()
        return try {
            jsonParser.decodeFromString<MutableMap<String, MutableList<HabitLogDto>>>(content)
        } catch (e: Exception) {
            val backup = fileRepository.moveAsideCorrupt(path)
                ?: throw IOException("Unreadable habit log $path could not be preserved", e)
            Log.e("HabitRepository", "Unreadable habit log moved aside to ${backup.name}", e)
            habitDao.getLogsForYear(year.toString())
                .groupBy { it.habitId }
                .mapValues { (_, logs) ->
                    logs.map { HabitLogDto(it.habitId, it.date, it.value, it.status.name) }.toMutableList()
                }
                .toMutableMap()
        }
    }

    /** Most recent COMPLETED date for a habit (this + last year), for INTERVAL scheduling. */
    suspend fun getLastCompletedDate(habitId: String): LocalDate? = withContext(Dispatchers.IO) {
        val year = LocalDate.now().year
        val logs = habitDao.getLogsForYear(year.toString()) + habitDao.getLogsForYear((year - 1).toString())
        logs.asSequence()
            .filter { it.habitId == habitId && it.status == HabitStatus.COMPLETED }
            .maxByOrNull { it.date }
            ?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }

    suspend fun addHabitConfig(config: HabitConfig) = habitMutex.withLock {
        withContext(Dispatchers.IO) {
            habitDao.insertConfig(mapConfigToEntity(config))
            exportConfigToJson() // Instantly mirror the change into the vault JSON
            runCatching { widgetUpdateManager.scheduleSnapshotUpdate() }
            pushScheduler.requestPush()
        }
    }

    // --- Mappers ---

    private fun parseStatus(raw: String): HabitStatus =
        try {
            HabitStatus.valueOf(raw)
        } catch (e: IllegalArgumentException) {
            HabitStatus.FAILED
        }

    private fun HabitConfigDto.toEntity() = HabitConfigEntity(
        id = id,
        title = title,
        description = description,
        frequency = frequency,
        targetValue = targetValue,
        unit = unit,
        type = type,
        color = color,
        icon = icon,
        autoSource = autoSource,
        createdDate = createdDate,
        archived = archived,
        sortOrder = sortOrder,
        scheduleMode = scheduleMode ?: "WEEKLY",
        intervalCount = intervalCount,
        intervalUnit = intervalUnit ?: "DAY"
    )

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
}
