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
}
