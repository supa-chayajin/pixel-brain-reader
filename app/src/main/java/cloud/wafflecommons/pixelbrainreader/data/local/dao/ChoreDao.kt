package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChoreEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChoreDao {

    @Query("SELECT * FROM chores ORDER BY createdAt DESC")
    fun getAllChoresAsFlow(): Flow<List<ChoreEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChore(chore: ChoreEntity)

    // Blocking variants for use inside database.withTransaction { ... }.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertChoresBlocking(chores: List<ChoreEntity>)

    @Query("DELETE FROM chores")
    fun deleteAllChoresBlocking()

    @Query("UPDATE chores SET lastDoneDate = :date WHERE id = :choreId")
    suspend fun updateLastDoneDate(choreId: String, date: String)

    @Delete
    suspend fun deleteChore(chore: ChoreEntity)
}
