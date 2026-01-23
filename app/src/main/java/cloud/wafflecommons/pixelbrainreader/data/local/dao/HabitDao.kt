package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cloud.wafflecommons.pixelbrainreader.data.local.entity.HabitConfigEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.HabitLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitDao {
    @Query("SELECT * FROM habit_configs")
    suspend fun getAllConfigs(): List<HabitConfigEntity>
    
    @Query("SELECT * FROM habit_configs")
    fun getAllConfigsFlow(): Flow<List<HabitConfigEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: HabitConfigEntity)

    @Query("SELECT * FROM habit_logs WHERE date LIKE :year || '%'")
    suspend fun getLogsForYear(year: String): List<HabitLogEntity>
    
    @Query("SELECT * FROM habit_logs WHERE date LIKE :year || '%'")
    fun getLogsForYearFlow(year: String): Flow<List<HabitLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: HabitLogEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLogs(logs: List<HabitLogEntity>)

    @Query("DELETE FROM habit_configs")
    fun deleteAllConfigsBlocking()

    @Query("DELETE FROM habit_logs")
    fun deleteAllLogsBlocking()
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertConfigBlocking(config: HabitConfigEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertLogsBlocking(logs: List<HabitLogEntity>)
}
