package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyBriefingEntity

@Dao
interface DailyBriefingDao {
    
    @Query("SELECT * FROM daily_briefings WHERE date = :date")
    suspend fun getBriefing(date: String): DailyBriefingEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBriefing(briefing: DailyBriefingEntity)
    
}
