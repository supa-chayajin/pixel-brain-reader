package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import androidx.room.withTransaction
import cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase
import cloud.wafflecommons.pixelbrainreader.data.local.dao.ChoreDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.HomeRoomDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChoreEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.HomeRoomEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.RoomWithChores
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
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
    private val widgetUpdateManager: cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetUpdateManager
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
        exportHomeConfigToJson()
    }

    suspend fun deleteRoom(room: HomeRoomEntity) {
        // Cascade delete chores first
        homeRoomDao.deleteChoresForRoom(room.id)
        homeRoomDao.deleteRoom(room)
        exportHomeConfigToJson()
    }

    // --- Chores (live data) ---------------------------------------------------

    fun getAllChoresStream(): Flow<List<ChoreEntity>> {
        return choreDao.getAllChoresAsFlow()
    }

    suspend fun insertChore(chore: ChoreEntity) {
        choreDao.insertChore(chore)
        exportHomeConfigToJson()
        runCatching { widgetUpdateManager.scheduleSnapshotUpdate() }
    }

    suspend fun updateLastDoneDate(choreId: String, date: String) {
        choreDao.updateLastDoneDate(choreId, date)
        exportHomeConfigToJson()
        // Reflect the completed chore in the Chores/Companion widgets.
        runCatching { widgetUpdateManager.scheduleSnapshotUpdate() }
    }

    suspend fun deleteChore(chore: ChoreEntity) {
        choreDao.deleteChore(chore)
        exportHomeConfigToJson()
        runCatching { widgetUpdateManager.scheduleSnapshotUpdate() }
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
        } catch (e: Exception) {
            Log.e("ChoreRepository", "Failed to export home config to JSON", e)
            throw e
        }
    }

    // --- Import (JSON -> Room) -----------------------------------------------

    /**
     * Sync Bridge: reads the home-config JSON files written by the vault sync
     * and reconciles them into Room. Mirrors [HabitRepository.syncWithFileSystem]:
     * wipe-and-replace inside a single transaction so remote-side deletions
     * propagate cleanly. Rooms are imported first because chores.roomId is an
     * implicit FK reference into home_rooms.
     *
     * Called from IndexingWorker after a successful git pull.
     */
    suspend fun syncWithFileSystem() = withContext(Dispatchers.IO) {
        val homeRoot = fileRepository.getLocalFile(homeDir)
        if (!homeRoot.exists()) {
            Log.w("DataSync", "Home directory not found: ${homeRoot.absolutePath}")
            return@withContext
        }

        database.withTransaction {
            try {
                importRoomsFromJson()
                importChoresFromJson()
            } catch (e: Exception) {
                Log.e("ChoreSync", "Transaction Failed", e)
                throw e // Rollback
            }
        }
    }

    private suspend fun importRoomsFromJson() {
        try {
            val content = fileRepository.readFile(roomsFile)
            if (content.isNullOrBlank()) {
                Log.d("ChoreRepository", "rooms.json missing or empty; skipping room import")
                return
            }
            val type = object : TypeToken<List<HomeRoomDto>>() {}.type
            val dtos: List<HomeRoomDto> = gson.fromJson(content, type) ?: emptyList()

            homeRoomDao.deleteAllRoomsBlocking()
            if (dtos.isNotEmpty()) {
                homeRoomDao.insertRoomsBlocking(dtos.map { it.toEntity() })
            }
            Log.d("ChoreRepository", "Imported ${dtos.size} rooms from JSON.")
        } catch (e: JsonSyntaxException) {
            Log.e("ChoreRepository", "JSON Syntax Error in rooms.json", e)
            throw e
        }
    }

    private suspend fun importChoresFromJson() {
        try {
            val content = fileRepository.readFile(choresFile)
            if (content.isNullOrBlank()) {
                Log.d("ChoreRepository", "chores.json missing or empty; skipping chore import")
                return
            }
            val type = object : TypeToken<List<ChoreDto>>() {}.type
            val dtos: List<ChoreDto> = gson.fromJson(content, type) ?: emptyList()

            choreDao.deleteAllChoresBlocking()
            if (dtos.isNotEmpty()) {
                choreDao.insertChoresBlocking(dtos.map { it.toEntity() })
            }
            Log.d("ChoreRepository", "Imported ${dtos.size} chores from JSON.")
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

    private fun HomeRoomDto.toEntity() = HomeRoomEntity(
        id = id,
        name = name,
        icon = icon,
        color = color,
        sortOrder = sortOrder,
        // Preserve original createdAt when present; otherwise stamp now so sort order is stable.
        createdAt = if (createdAt > 0L) createdAt else System.currentTimeMillis()
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

    private fun ChoreDto.toEntity() = ChoreEntity(
        id = id,
        name = name,
        roomId = roomId,
        baseEffort = baseEffort ?: 1,
        frequencyDays = frequencyDays ?: 7,
        lastDoneDate = lastDoneDate ?: "",
        icon = icon ?: "cleaning_services",
        createdAt = if (createdAt > 0L) createdAt else System.currentTimeMillis(),
        sortOrder = sortOrder ?: 0,
        archived = archived ?: false
    )
}
