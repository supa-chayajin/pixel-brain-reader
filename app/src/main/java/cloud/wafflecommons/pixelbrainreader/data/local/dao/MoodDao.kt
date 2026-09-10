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

    /** Inclusive ISO-date range ("yyyy-MM-dd" sorts lexicographically). */
    @Query("SELECT * FROM moods WHERE date BETWEEN :startDate AND :endDate ORDER BY timestamp DESC")
    fun getMoodsBetween(startDate: String, endDate: String): Flow<List<MoodEntity>>

    @Query("SELECT * FROM moods")
    suspend fun getAllMoodsOnce(): List<MoodEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMood(mood: MoodEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMoods(moods: List<MoodEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMoodBlocking(mood: MoodEntity)

    @Query("DELETE FROM moods WHERE date IN (:dates)")
    suspend fun deleteMoodsForDates(dates: List<String>)

    @Query("SELECT COUNT(*) FROM moods")
    suspend fun getCount(): Int
}
