package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.*
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("SELECT * FROM daily_tasks WHERE scheduledDate = :date ORDER BY isDone ASC, priority DESC")
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
}
