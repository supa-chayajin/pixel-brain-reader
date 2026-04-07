package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.local.dao.ChoreDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.HomeRoomDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChoreEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.HomeRoomEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.RoomWithChores
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChoreRepository @Inject constructor(
    private val choreDao: ChoreDao,
    private val homeRoomDao: HomeRoomDao,
    private val fileRepository: FileRepository
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val homeDir = "10_Journal/data/home"
    private val roomsFile = "$homeDir/rooms.json"
    private val choresFile = "$homeDir/chores.json"

    // --- Rooms ---
    
    fun getAllRoomsStream(): Flow<List<HomeRoomEntity>> = homeRoomDao.getAllRoomsAsFlow()
    
    fun getRoomsWithChoresStream(): Flow<List<RoomWithChores>> = homeRoomDao.getRoomsWithChoresFlow()

    suspend fun upsertRoom(room: HomeRoomEntity) {
        homeRoomDao.insertRoom(room)
        exportRoomsToJson()
    }

    suspend fun deleteRoom(room: HomeRoomEntity) {
        // Cascade delete chores first
        homeRoomDao.deleteChoresForRoom(room.id)
        homeRoomDao.deleteRoom(room)
        exportRoomsToJson()
        exportChoresToJson()
    }

    // --- Chores ---
    
    fun getAllChoresStream(): Flow<List<ChoreEntity>> {
        return choreDao.getAllChoresAsFlow()
    }

    suspend fun insertChore(chore: ChoreEntity) {
        choreDao.insertChore(chore)
        exportChoresToJson()
    }

    suspend fun updateLastDoneDate(choreId: String, date: String) {
        choreDao.updateLastDoneDate(choreId, date)
        exportChoresToJson()
    }

    suspend fun deleteChore(chore: ChoreEntity) {
        choreDao.deleteChore(chore)
        exportChoresToJson()
    }

    // --- JSON Sync ---
    
    suspend fun exportHomeConfigToJson() = withContext(Dispatchers.IO) {
        try {
            fileRepository.createLocalFolder(homeDir)
            
            // 1. Export Rooms
            val rooms = homeRoomDao.getAllRoomsAsFlow().first()
            val roomsJson = gson.toJson(rooms)
            fileRepository.saveFileLocally(roomsFile, roomsJson)
            Log.d("ChoreRepository", "Exported rooms.json successfully")

            // 2. Export Chores
            val chores = choreDao.getAllChoresAsFlow().first()
            val choresJson = gson.toJson(chores)
            fileRepository.saveFileLocally(choresFile, choresJson)
            Log.d("ChoreRepository", "Exported chores.json successfully")
            
        } catch (e: Exception) {
            Log.e("ChoreRepository", "Failed to export home config to JSON", e)
            throw e
        }
    }

    private suspend fun exportRoomsToJson() = exportHomeConfigToJson()

    private suspend fun exportChoresToJson() = exportHomeConfigToJson()
}

