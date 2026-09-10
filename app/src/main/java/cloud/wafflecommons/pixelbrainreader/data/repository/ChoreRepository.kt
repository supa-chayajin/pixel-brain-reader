package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import androidx.room.withTransaction
import cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase
import cloud.wafflecommons.pixelbrainreader.data.local.dao.ChoreDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.HomeRoomDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChoreEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.HomeRoomEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.RoomWithChores
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetUpdateManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChoreRepository @Inject constructor(
    private val choreDao: ChoreDao,
    private val homeRoomDao: HomeRoomDao,
    private val fileRepository: FileRepository,
    private val database: AppDatabase,
    private val widgetUpdateManager: WidgetUpdateManager,
    private val pushScheduler: VaultPushScheduler
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val homeDir = "10_Journal/data/home"
    private val roomsFile = "$homeDir/rooms.json"
    private val choresFile = "$homeDir/chores.json"

    // --- DTOs (mirror HabitRepository's embedded-DTO pattern) -----------------

    @Serializable
    data class HomeRoomDto(
        val id: String,
        val name: String,
        val icon: String = "home",
        val color: String = "#808080",
        val sortOrder: Int = 0,
        val createdAt: Long = 0L
    )

    // NOTE: these files are parsed with Gson (below), and Gson does NOT apply Kotlin
    // default values — an absent JSON key leaves a reference type null and a primitive 0,
    // NOT the default written here. Vault chores.json may come from an older or hand-seeded
    // schema (e.g. no baseEffort/lastDoneDate/createdAt), so every absent-prone field is
    // nullable and coalesced in [toEntity]. Without this a null lastDoneDate hits
    // ChoreEntity's NOT NULL column and rolls back the WHOLE rooms+chores import transaction
    // (symptom: chores AND rooms both import as 0). sortOrder/archived are first-class and
    // round-trip through import/export.
    @Serializable
    data class ChoreDto(
        val id: String,
        val name: String,
        val roomId: String,
        val baseEffort: Int? = null,
        val frequencyDays: Int? = null,
        val lastDoneDate: String? = null,
        val icon: String? = null,
        val createdAt: Long = 0L,
        val sortOrder: Int? = null,
        val archived: Boolean? = null
    )

    // --- Rooms (live data) ----------------------------------------------------

    fun getAllRoomsStream(): Flow<List<HomeRoomEntity>> = homeRoomDao.getAllRoomsAsFlow()

    fun getRoomsWithChoresStream(): Flow<List<RoomWithChores>> = homeRoomDao.getRoomsWithChoresFlow()

    suspend fun upsertRoom(room: HomeRoomEntity) {
        homeRoomDao.insertRoom(room)
        onHomeConfigChanged()
    }

    suspend fun deleteRoom(room: HomeRoomEntity) {
        // Cascade delete chores first
        homeRoomDao.deleteChoresForRoom(room.id)
        homeRoomDao.deleteRoom(room)
        onHomeConfigChanged()
    }

    // --- Chores (live data) ---------------------------------------------------

    fun getAllChoresStream(): Flow<List<ChoreEntity>> {
        return choreDao.getAllChoresAsFlow()
    }

    suspend fun insertChore(chore: ChoreEntity) {
        choreDao.insertChore(chore)
        onHomeConfigChanged()
    }

    suspend fun updateLastDoneDate(choreId: String, date: String) {
        choreDao.updateLastDoneDate(choreId, date)
        onHomeConfigChanged()
    }

    suspend fun deleteChore(chore: ChoreEntity) {
        choreDao.deleteChore(chore)
        onHomeConfigChanged()
    }

    /**
     * Every home mutation: mirror it into the vault JSON, refresh the Chores/Companion widgets, and
     * queue a batched background push (like habit and mood changes).
     */
    private suspend fun onHomeConfigChanged() {
        exportHomeConfigToJson()
        runCatching { widgetUpdateManager.scheduleSnapshotUpdate() }
        pushScheduler.requestPush()
    }

    // --- Export (Room -> JSON) -----------------------------------------------

    suspend fun exportHomeConfigToJson() = withContext(Dispatchers.IO) {
        try {
            fileRepository.createLocalFolder(homeDir)

            // 1. Export Rooms (via DTO so the on-disk schema is decoupled from Room).
            val roomDtos = homeRoomDao.getAllRoomsAsFlow().first().map { it.toDto() }
            fileRepository.saveFileLocally(roomsFile, gson.toJson(roomDtos))
            Log.d("ChoreRepository", "Exported rooms.json successfully (${roomDtos.size} rooms)")

            // 2. Export Chores
            val choreDtos = choreDao.getAllChoresAsFlow().first().map { it.toDto() }
            fileRepository.saveFileLocally(choresFile, gson.toJson(choreDtos))
            Log.d("ChoreRepository", "Exported chores.json successfully (${choreDtos.size} chores)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("ChoreRepository", "Failed to export home config to JSON", e)
            throw e
        }
    }

    // --- Import (JSON -> Room) -----------------------------------------------

    /**
     * Sync Bridge: reconciles the home-config JSON files of the vault into Room. A table present
     * in the vault replaces Room's copy wholesale, so remote-side deletions propagate.
     *
     * The files are parsed OUTSIDE the transaction (the reads used to hold Room's writer lock) and a
     * table is only replaced when its content actually differs — an unchanged vault no longer
     * invalidates every chore/room observer on each startup and sync. Rooms and chores are written
     * in one transaction because chores.roomId references home_rooms.
     */
    suspend fun syncWithFileSystem() = withContext(Dispatchers.IO) {
        val homeRoot = fileRepository.getLocalFile(homeDir)
        if (!homeRoot.exists()) {
            Log.d("DataSync", "Home directory not found")
            return@withContext
        }

        val roomDtos = readRoomDtos()
        val choreDtos = readChoreDtos()
        val now = System.currentTimeMillis()

        val currentRooms = homeRoomDao.getAllRoomsAsFlow().first()
        val roomsToWrite = roomDtos?.let { dtos ->
            val createdAtById = currentRooms.associate { it.id to it.createdAt }
            dtos.map { it.toEntity(fallbackCreatedAt = createdAtById[it.id] ?: now) }
        }?.takeIf { it.toSet() != currentRooms.toSet() }

        val currentChores = choreDao.getAllChoresAsFlow().first()
        val choresToWrite = choreDtos?.let { dtos ->
            val createdAtById = currentChores.associate { it.id to it.createdAt }
            dtos.map { it.toEntity(fallbackCreatedAt = createdAtById[it.id] ?: now) }
        }?.takeIf { it.toSet() != currentChores.toSet() }

        if (roomsToWrite == null && choresToWrite == null) return@withContext

        database.withTransaction {
            roomsToWrite?.let { rooms ->
                homeRoomDao.deleteAllRoomsBlocking()
                if (rooms.isNotEmpty()) homeRoomDao.insertRoomsBlocking(rooms)
            }
            choresToWrite?.let { chores ->
                choreDao.deleteAllChoresBlocking()
                if (chores.isNotEmpty()) choreDao.insertChoresBlocking(chores)
            }
        }
        Log.d("ChoreRepository", "Imported rooms=${roomsToWrite?.size} chores=${choresToWrite?.size} from JSON")
    }

    /** Parsed rooms.json, or null when it is absent/blank (Room's rooms are then left as they are). */
    private suspend fun readRoomDtos(): List<HomeRoomDto>? {
        val content = fileRepository.readFile(roomsFile)
        if (content.isNullOrBlank()) {
            Log.d("ChoreRepository", "rooms.json missing or empty; skipping room import")
            return null
        }
        return try {
            val type = object : TypeToken<List<HomeRoomDto>>() {}.type
            gson.fromJson<List<HomeRoomDto>>(content, type) ?: emptyList()
        } catch (e: JsonSyntaxException) {
            Log.e("ChoreRepository", "JSON Syntax Error in rooms.json", e)
            throw e
        }
    }

    /** Parsed chores.json, or null when it is absent/blank (Room's chores are then left as they are). */
    private suspend fun readChoreDtos(): List<ChoreDto>? {
        val content = fileRepository.readFile(choresFile)
        if (content.isNullOrBlank()) {
            Log.d("ChoreRepository", "chores.json missing or empty; skipping chore import")
            return null
        }
        return try {
            val type = object : TypeToken<List<ChoreDto>>() {}.type
            gson.fromJson<List<ChoreDto>>(content, type) ?: emptyList()
        } catch (e: JsonSyntaxException) {
            Log.e("ChoreRepository", "JSON Syntax Error in chores.json", e)
            throw e
        }
    }

    // --- Mappers --------------------------------------------------------------

    private fun HomeRoomEntity.toDto() = HomeRoomDto(
        id = id,
        name = name,
        icon = icon,
        color = color,
        sortOrder = sortOrder,
        createdAt = createdAt
    )

    /** [fallbackCreatedAt] applies when the JSON has no createdAt (keeps the sort order stable). */
    private fun HomeRoomDto.toEntity(fallbackCreatedAt: Long) = HomeRoomEntity(
        id = id,
        name = name,
        icon = icon,
        color = color,
        sortOrder = sortOrder,
        createdAt = if (createdAt > 0L) createdAt else fallbackCreatedAt
    )

    private fun ChoreEntity.toDto() = ChoreDto(
        id = id,
        name = name,
        roomId = roomId,
        baseEffort = baseEffort,
        frequencyDays = frequencyDays,
        lastDoneDate = lastDoneDate,
        icon = icon,
        createdAt = createdAt,
        sortOrder = sortOrder,
        archived = archived
    )

    /** [fallbackCreatedAt] applies when the JSON has no createdAt (keeps the sort order stable). */
    private fun ChoreDto.toEntity(fallbackCreatedAt: Long) = ChoreEntity(
        id = id,
        name = name,
        roomId = roomId,
        baseEffort = baseEffort ?: 1,
        frequencyDays = frequencyDays ?: 7,
        lastDoneDate = lastDoneDate ?: "",
        icon = icon ?: "cleaning_services",
        createdAt = if (createdAt > 0L) createdAt else fallbackCreatedAt,
        sortOrder = sortOrder ?: 0,
        archived = archived ?: false
    )
}
