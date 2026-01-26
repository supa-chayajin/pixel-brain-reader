package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyBufferEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface DailyBufferDao {

    // --- Daily Buffer ---
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBuffer(buffer: DailyBufferEntity)

    @Query("SELECT * FROM daily_buffer WHERE date = :date")
    suspend fun getBuffer(date: LocalDate): DailyBufferEntity?

    @Query("UPDATE daily_buffer SET mantra = :mantra, lastModified = :timestamp WHERE date = :date")
    suspend fun updateMantra(date: LocalDate, mantra: String, timestamp: Long = System.currentTimeMillis())

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
        buffer: DailyBufferEntity, 
        timeline: List<TimelineEntryEntity>, 
        tasks: List<DailyTaskEntity>
    ) {
        insertBuffer(buffer)
        // We might want to be careful not to wipe un-synced data, but "ingest" implies
        // taking authoritative state from file (e.g. at startup or after pull).
        // For safety, let's clear and replace for that specific day.
        clearTimeline(buffer.date)
        clearTasks(buffer.date)
        
        timeline.forEach { insertTimelineEntry(it) }
        tasks.forEach { insertTask(it) }
    }
}
