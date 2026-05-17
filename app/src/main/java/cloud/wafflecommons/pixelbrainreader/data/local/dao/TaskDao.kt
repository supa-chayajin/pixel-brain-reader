package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.*
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    // V6 outbox: pendingDeletion rows are hidden from the live UI so deletes
    // feel instant. Use getTasksSnapshot if you need every row including pending.
    @Query("SELECT * FROM daily_tasks WHERE scheduledDate = :date AND pendingDeletion = 0 ORDER BY isDone ASC, priority DESC")
    fun getTasksForDate(date: String): Flow<List<DailyTaskEntity>>

    @Query("SELECT * FROM daily_tasks WHERE scheduledDate = :date ORDER BY isDone ASC, priority DESC")
    suspend fun getTasksSnapshot(date: String): List<DailyTaskEntity>

    @Query("SELECT * FROM daily_tasks WHERE scheduledDate BETWEEN :startDate AND :endDate")
    fun getTasksInRange(startDate: String, endDate: String): Flow<List<DailyTaskEntity>>



    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: DailyTaskEntity)

    @Update
    suspend fun updateTask(task: DailyTaskEntity)

    @Query("DELETE FROM daily_tasks WHERE id = :taskId")
    suspend fun deleteTask(taskId: String)

    // V6: auto-marks dirty when the task is linked to Google so TaskSyncWorker
    // picks the toggle up. Local-only tasks (googleTaskId NULL) stay clean.
    @Query("""
        UPDATE daily_tasks
        SET isDone = :isDone,
            isDirty = CASE WHEN googleTaskId IS NOT NULL THEN 1 ELSE isDirty END
        WHERE id = :taskId
    """)
    suspend fun updateTaskStatus(taskId: String, isDone: Boolean)

    @Query("SELECT * FROM daily_tasks WHERE googleTaskId = :googleTaskId LIMIT 1")
    suspend fun getTaskByGoogleTaskId(googleTaskId: String): DailyTaskEntity?

    // --- V6 outbox -----------------------------------------------------------

    @Query("SELECT * FROM daily_tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: String): DailyTaskEntity?

    @Query("SELECT * FROM daily_tasks WHERE isDirty = 1 OR pendingDeletion = 1")
    suspend fun getDirtyTasksSnapshot(): List<DailyTaskEntity>

    @Query("UPDATE daily_tasks SET googleTaskId = :googleTaskId, isDirty = 0 WHERE id = :id")
    suspend fun markPushedWithGoogleId(id: String, googleTaskId: String)

    @Query("UPDATE daily_tasks SET isDirty = 0 WHERE id = :id")
    suspend fun clearDirty(id: String)

    /**
     * Drops every Google-sourced row for [date] that has no pending local
     * mutation (isDirty=0 AND pendingDeletion=0). Called before re-importing
     * today's Google Tasks so rows that no longer match the strict "due ==
     * today" filter (overdue, null-due, etc.) don't linger from a stale import.
     *
     * Preserves locally-checked rows the TaskSyncWorker hasn't pushed yet, and
     * preserves rows queued for remote deletion.
     */
    @Query("""
        DELETE FROM daily_tasks
        WHERE source = 'GoogleTasks'
          AND scheduledDate = :date
          AND isDirty = 0
          AND pendingDeletion = 0
    """)
    suspend fun purgeCleanGoogleTasksForDate(date: String)
}
