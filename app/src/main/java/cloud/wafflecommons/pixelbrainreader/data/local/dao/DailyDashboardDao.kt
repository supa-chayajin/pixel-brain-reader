package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyDashboardEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface DailyDashboardDao {

    // --- Daily Dashboard ---
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDashboard(dashboard: DailyDashboardEntity)

    @Query("SELECT * FROM daily_dashboard WHERE date = :date")
    suspend fun getDashboard(date: LocalDate): DailyDashboardEntity?

    @Query("SELECT * FROM daily_dashboard WHERE date = :date")
    fun getLiveDashboard(date: LocalDate): Flow<DailyDashboardEntity?>

    // Update scalar fields
    @Query("UPDATE daily_dashboard SET dailyMantra = :mantra WHERE date = :date")
    suspend fun updateMantra(date: LocalDate, mantra: String)

    @Query("UPDATE daily_dashboard SET ideasContent = :content WHERE date = :date")
    suspend fun updateIdeas(date: LocalDate, content: String)

    @Query("UPDATE daily_dashboard SET notesContent = :content WHERE date = :date")
    suspend fun updateNotes(date: LocalDate, content: String)
    
    @Query("UPDATE daily_dashboard SET aiWeatherBriefing = :weather, aiQuoteOfTheDay = :quote, lastAiGenerationTimestamp = :timestamp WHERE date = :date")
    suspend fun updateAiBriefing(date: LocalDate, weather: String, quote: String, timestamp: Long)

    // --- Timeline ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTimelineEntry(entry: TimelineEntryEntity)

    @Query("SELECT * FROM timeline_entries WHERE date = :date ORDER BY time ASC")
    fun getLiveTimeline(date: LocalDate): Flow<List<TimelineEntryEntity>>

    @Query("SELECT * FROM timeline_entries WHERE date = :date ORDER BY time ASC")
    suspend fun getTimelineSnapshot(date: LocalDate): List<TimelineEntryEntity>

    @Query("DELETE FROM timeline_entries WHERE date = :date")
    suspend fun clearTimeline(date: LocalDate)

    // --- Tasks ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: DailyTaskEntity)

    @Query("SELECT * FROM daily_tasks WHERE date = :date ORDER BY isDone ASC, scheduledTime ASC NULLS LAST, priority DESC")
    fun getLiveTasks(date: LocalDate): Flow<List<DailyTaskEntity>>

    @Query("SELECT * FROM daily_tasks WHERE date = :date ORDER BY isDone ASC, scheduledTime ASC NULLS LAST, priority DESC")
    suspend fun getTasksSnapshot(date: LocalDate): List<DailyTaskEntity>

    @Query("UPDATE daily_tasks SET isDone = :isDone WHERE id = :taskId")
    suspend fun updateTaskStatus(taskId: String, isDone: Boolean)

    @Query("DELETE FROM daily_tasks WHERE date = :date")
    suspend fun clearTasks(date: LocalDate)

    // --- Transactional Helper ---
    @Transaction
    suspend fun ingestDailyData(
        dashboard: DailyDashboardEntity, 
        timeline: List<TimelineEntryEntity>, 
        tasks: List<DailyTaskEntity>
    ) {
        insertDashboard(dashboard)
        // Explicit updates for content that might have changed in file
        updateMantra(dashboard.date, dashboard.dailyMantra)
        updateIdeas(dashboard.date, dashboard.ideasContent)
        updateNotes(dashboard.date, dashboard.notesContent)
        
        clearTimeline(dashboard.date)
        clearTasks(dashboard.date)
        
        timeline.forEach { insertTimelineEntry(it) }
        tasks.forEach { insertTask(it) }
    }
}
