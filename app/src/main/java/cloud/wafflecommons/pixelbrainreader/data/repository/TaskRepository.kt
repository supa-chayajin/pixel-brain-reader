package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.local.dao.TaskDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRepository @Inject constructor(
    private val taskDao: TaskDao
) {

    /**
     * @param pushToGoogle when true, the new task is marked dirty so
     *   TaskSyncWorker creates it on Google Tasks on the next drain.
     */
    suspend fun addTask(
        content: String,
        date: LocalDate,
        scheduledTime: LocalTime? = null,
        priority: Int = 1,
        pushToGoogle: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val task = DailyTaskEntity(
            scheduledDate = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
            label = content,
            scheduledTime = scheduledTime?.format(DateTimeFormatter.ofPattern("HH:mm")),
            priority = priority,
            isDirty = pushToGoogle
        )
        taskDao.insertTask(task)
    }

    suspend fun toggleTask(taskId: String, isDone: Boolean) = withContext(Dispatchers.IO) {
        taskDao.updateTaskStatus(taskId, isDone)
    }
    
    suspend fun getTasks(date: LocalDate): List<DailyTaskEntity> = withContext(Dispatchers.IO) {
        taskDao.getTasksSnapshot(date.format(DateTimeFormatter.ISO_LOCAL_DATE))
    }

    fun getTasksFlow(date: LocalDate): kotlinx.coroutines.flow.Flow<List<DailyTaskEntity>> {
        return taskDao.getTasksForDate(date.format(DateTimeFormatter.ISO_LOCAL_DATE))
    }

    fun getTasksInRangeFlow(start: LocalDate, end: LocalDate): kotlinx.coroutines.flow.Flow<List<DailyTaskEntity>> {
        return taskDao.getTasksInRange(start.format(DateTimeFormatter.ISO_LOCAL_DATE), end.format(DateTimeFormatter.ISO_LOCAL_DATE))
    }


    /**
     * For Google-linked tasks the delete is deferred: the row is flagged
     * pendingDeletion and TaskSyncWorker drops it on Google before removing
     * the local copy. Local-only tasks are hard-deleted immediately.
     */
    suspend fun deleteTask(taskId: String) = withContext(Dispatchers.IO) {
        val task = taskDao.getTaskById(taskId)
        if (task?.googleTaskId != null) {
            taskDao.updateTask(task.copy(pendingDeletion = true, isDirty = true))
        } else {
            taskDao.deleteTask(taskId)
        }
    }
}

