package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.local.dao.TaskDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRepository @Inject constructor(
    private val taskDao: TaskDao,
    private val dailyDashboardDao: cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyDashboardDao,
    private val widgetUpdateManager: WidgetUpdateManager
) {

    suspend fun addTask(
        content: String,
        date: LocalDate,
        scheduledTime: LocalTime? = null,
        priority: Int = 1
    ) = withContext(Dispatchers.IO) {
        dailyDashboardDao.insertDashboard(
            cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyDashboardEntity(date = date)
        )
        val task = DailyTaskEntity(
            scheduledDate = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
            label = content,
            scheduledTime = scheduledTime?.format(DateTimeFormatter.ofPattern("HH:mm")),
            priority = priority
        )
        taskDao.insertTask(task)
        refreshWidgets()
    }

    suspend fun toggleTask(taskId: String, isDone: Boolean) = withContext(Dispatchers.IO) {
        taskDao.updateTaskStatus(taskId, isDone)
        refreshWidgets()
    }

    suspend fun getTasks(date: LocalDate): List<DailyTaskEntity> = withContext(Dispatchers.IO) {
        taskDao.getTasksSnapshot(date.format(DateTimeFormatter.ISO_LOCAL_DATE))
    }

    fun getTasksFlow(date: LocalDate): Flow<List<DailyTaskEntity>> {
        return taskDao.getTasksForDate(date.format(DateTimeFormatter.ISO_LOCAL_DATE))
    }

    fun getTasksInRangeFlow(start: LocalDate, end: LocalDate): Flow<List<DailyTaskEntity>> {
        return taskDao.getTasksInRange(start.format(DateTimeFormatter.ISO_LOCAL_DATE), end.format(DateTimeFormatter.ISO_LOCAL_DATE))
    }

    suspend fun deleteTask(taskId: String) = withContext(Dispatchers.IO) {
        taskDao.deleteTask(taskId)
        refreshWidgets()
    }

    /** The Today and Companion widgets show tasks: rebuild them after every in-app task change. */
    private fun refreshWidgets() {
        runCatching { widgetUpdateManager.scheduleSnapshotUpdate() }
    }
}
