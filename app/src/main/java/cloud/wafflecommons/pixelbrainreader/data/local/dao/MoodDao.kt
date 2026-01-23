package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cloud.wafflecommons.pixelbrainreader.data.local.entity.MoodEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MoodDao {
    @Query("SELECT * FROM moods ORDER BY timestamp DESC")
    fun getAllMoods(): Flow<List<MoodEntity>>

    @Query("SELECT * FROM moods WHERE date = :date ORDER BY timestamp DESC")
    fun getMood(date: String): Flow<List<MoodEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMood(mood: MoodEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMoodBlocking(mood: MoodEntity)

    @Query("SELECT COUNT(*) FROM moods")
    suspend fun getCount(): Int
}
