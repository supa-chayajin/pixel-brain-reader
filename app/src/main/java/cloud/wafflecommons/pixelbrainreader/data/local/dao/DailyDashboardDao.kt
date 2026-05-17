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

    @Query("SELECT * FROM daily_dashboard")
    suspend fun getAllDashboards(): List<DailyDashboardEntity>

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

    // V6 outbox: pendingDeletion rows are hidden from the live UI so deletes
    // feel instant. Snapshot/burn-back queries still see them via getTimelineSnapshot.
    @Query("SELECT * FROM timeline_entries WHERE date = :date AND pendingDeletion = 0 ORDER BY time ASC")
    fun getLiveTimeline(date: LocalDate): Flow<List<TimelineEntryEntity>>

    @Query("SELECT * FROM timeline_entries WHERE date = :date ORDER BY time ASC")
    suspend fun getTimelineSnapshot(date: LocalDate): List<TimelineEntryEntity>

    @Query("DELETE FROM timeline_entries WHERE date = :date")
    suspend fun clearTimeline(date: LocalDate)

    @Query("DELETE FROM timeline_entries WHERE id = :id")
    suspend fun deleteTimelineEntryById(id: String)

    /**
     * Drops every Calendar-sourced timeline entry for [date] (rows whose
     * googleEventId is non-null) that has no pending local mutation. Called
     * before re-importing today's events so rows that no longer match the
     * strict window don't linger from a stale import.
     *
     * Preserves:
     *  - User-created timeline entries (googleEventId IS NULL — kept by source).
     *  - Locally-edited Google entries (isDirty = 1) — worker hasn't pushed yet.
     *  - Entries queued for remote deletion (pendingDeletion = 1).
     */
    @Query("""
        DELETE FROM timeline_entries
        WHERE date = :date
          AND googleEventId IS NOT NULL
          AND isDirty = 0
          AND pendingDeletion = 0
    """)
    suspend fun purgeGoogleTimelineForDate(date: LocalDate)

    // --- V6 Calendar outbox ---------------------------------------------------

    @Query("SELECT * FROM timeline_entries WHERE id = :id LIMIT 1")
    suspend fun getTimelineEntryById(id: String): TimelineEntryEntity?

    @Query("SELECT * FROM timeline_entries WHERE isDirty = 1 OR pendingDeletion = 1")
    suspend fun getDirtyTimelineSnapshot(): List<TimelineEntryEntity>

    @Query("UPDATE timeline_entries SET googleEventId = :googleEventId, isDirty = 0 WHERE id = :id")
    suspend fun markTimelinePushedWithGoogleId(id: String, googleEventId: String)

    @Query("UPDATE timeline_entries SET isDirty = 0 WHERE id = :id")
    suspend fun clearTimelineDirty(id: String)

    @Query("SELECT * FROM timeline_entries WHERE googleEventId = :googleEventId LIMIT 1")
    suspend fun getTimelineEntryByGoogleEventId(googleEventId: String): TimelineEntryEntity?

    // --- Tasks ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: DailyTaskEntity)

    // V6 outbox: pendingDeletion rows are hidden from the live UI so deletes
    // feel instant. Snapshot/burn-back queries still see them via getTasksSnapshot.
    @Query("SELECT * FROM daily_tasks WHERE scheduledDate = :date AND pendingDeletion = 0 ORDER BY isDone ASC, scheduledTime ASC NULLS LAST, priority DESC")
    fun getLiveTasks(date: LocalDate): Flow<List<DailyTaskEntity>>

    @Query("SELECT * FROM daily_tasks WHERE scheduledDate = :date ORDER BY isDone ASC, scheduledTime ASC NULLS LAST, priority DESC")
    suspend fun getTasksSnapshot(date: LocalDate): List<DailyTaskEntity>

    // V6: auto-marks dirty when the task is linked to Google so TaskSyncWorker
    // picks the toggle up. Mirrors TaskDao.updateTaskStatus for the DashboardRepo path.
    @Query("""
        UPDATE daily_tasks
        SET isDone = :isDone,
            isDirty = CASE WHEN googleTaskId IS NOT NULL THEN 1 ELSE isDirty END
        WHERE id = :taskId
    """)
    suspend fun updateTaskStatus(taskId: String, isDone: Boolean)

    @Query("DELETE FROM daily_tasks WHERE scheduledDate = :date")
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
