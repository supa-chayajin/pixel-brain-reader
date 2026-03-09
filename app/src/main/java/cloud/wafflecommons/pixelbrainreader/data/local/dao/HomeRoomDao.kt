package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import cloud.wafflecommons.pixelbrainreader.data.local.entity.HomeRoomEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.RoomWithChores
import kotlinx.coroutines.flow.Flow

@Dao
interface HomeRoomDao {

    @Query("SELECT * FROM home_rooms ORDER BY sortOrder ASC, createdAt ASC")
    fun getAllRoomsAsFlow(): Flow<List<HomeRoomEntity>>

    @Transaction // Important for @Relation queries to be consistent
    @Query("SELECT * FROM home_rooms ORDER BY sortOrder ASC, createdAt ASC")
    fun getRoomsWithChoresFlow(): Flow<List<RoomWithChores>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoom(room: HomeRoomEntity)

    @Delete
    suspend fun deleteRoom(room: HomeRoomEntity)

    // Cascade delete option
    @Query("DELETE FROM chores WHERE roomId = :roomId")
    suspend fun deleteChoresForRoom(roomId: String)
}
