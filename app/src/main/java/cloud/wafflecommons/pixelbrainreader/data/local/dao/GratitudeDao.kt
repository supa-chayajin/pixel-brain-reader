package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cloud.wafflecommons.pixelbrainreader.data.local.entity.GratitudeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GratitudeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGratitude(entity: GratitudeEntity)

    @Query("DELETE FROM daily_gratitudes WHERE id = :id")
    suspend fun deleteGratitude(id: String)

    @Query("SELECT * FROM daily_gratitudes WHERE date = :date ORDER BY createdAt ASC")
    fun getGratitudesForDate(date: String): Flow<List<GratitudeEntity>>

    @Query("SELECT * FROM daily_gratitudes WHERE date = :date ORDER BY createdAt ASC")
    suspend fun getGratitudesForDateOneShot(date: String): List<GratitudeEntity>
}
