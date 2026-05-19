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

    @Query("UPDATE daily_tasks SET isDone = :isDone WHERE id = :taskId")
    suspend fun updateTaskStatus(taskId: String, isDone: Boolean)

    @Query("SELECT * FROM daily_tasks WHERE googleTaskId = :googleTaskId LIMIT 1")
    suspend fun getTaskByGoogleTaskId(googleTaskId: String): DailyTaskEntity?

    @Query("SELECT * FROM daily_tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: String): DailyTaskEntity?

    /**
     * Drops every Google-sourced row for [date] before re-importing today's
     * Google Tasks. Ensures rows the user deleted on Google's side (or that
     * no longer match the strict "due == today" filter) don't linger.
     * Locally-created tasks (source != 'GoogleTasks') are preserved.
     */
    @Query("DELETE FROM daily_tasks WHERE source = 'GoogleTasks' AND scheduledDate = :date")
    suspend fun purgeGoogleTasksForDate(date: String)
}
