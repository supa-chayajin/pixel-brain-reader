package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.auth.GoogleAuthRepository
import cloud.wafflecommons.pixelbrainreader.data.local.dao.TaskDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.HttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import com.google.api.services.tasks.model.Task as GoogleTask

private const val TAG = "GoogleTaskRepo"
private const val APP_NAME = "PixelBrainReader"
private const val DEFAULT_LIST = "@default"

/**
 * Google Tasks bidirectional sync.
 *
 * Read: [syncPendingTasks] imports uncompleted tasks from all lists into Room.
 * Write: [createTask] / [pushCompletion] / [deleteTask] target the user's
 * default task list.
 *
 * Writes are typically queued via the dirty-flag outbox on [DailyTaskEntity]
 * and drained by TaskSyncWorker (sub-turn B), but the methods are public so
 * the UI can also call them directly for immediate-confirmation flows.
 */
@Singleton
class GoogleTaskRepository @Inject constructor(
    private val authRepository: GoogleAuthRepository,
    private val userPreferences: UserPreferencesRepository,
    private val taskDao: TaskDao,
    private val transport: HttpTransport,
    private val jsonFactory: JsonFactory
) {

    private fun buildService(token: String): Tasks {
        val bearer = HttpRequestInitializer { request ->
            request.headers.authorization = "Bearer $token"
        }
        return Tasks.Builder(transport, jsonFactory, bearer)
            .setApplicationName(APP_NAME)
            .build()
    }

    // --- READ -----------------------------------------------------------------

    suspend fun syncPendingTasks(today: LocalDate = LocalDate.now()): Result<Int> =
        withContext(Dispatchers.IO) {
            if (!userPreferences.isGoogleSyncEnabled.first()) return@withContext Result.success(0)
            val token = authRepository.getValidAccessToken() ?: run {
                Log.w(TAG, "No valid token; skipping tasks import")
                return@withContext Result.success(0)
            }
            try {
                val service = buildService(token)
                var total = 0
                for (list in service.tasklists().list().execute().items.orEmpty()) {
                    val tasks = service.tasks().list(list.id)
                        .setShowCompleted(false)
                        .execute()
                    tasks.items.orEmpty().forEach { gt ->
                        val entity = DailyTaskEntity(
                            scheduledDate = today.toString(),
                            label = gt.title ?: "Untitled Task",
                            isDone = false,
                            googleTaskId = gt.id,
                            source = "GoogleTasks"
                        )
                        val existing = taskDao.getTaskByGoogleTaskId(entity.googleTaskId!!)
                        if (existing != null) {
                            // Preserve local id + dirty flags; only refresh the label.
                            taskDao.updateTask(existing.copy(label = entity.label))
                        } else {
                            taskDao.insertTask(entity)
                        }
                        total++
                    }
                }
                Result.success(total)
            } catch (e: Exception) {
                Log.e(TAG, "Tasks import failed", e)
                Result.failure(e)
            }
        }

    // --- WRITE ----------------------------------------------------------------

    suspend fun createTask(title: String, dueAt: LocalDate? = null): Result<String> =
        withContext(Dispatchers.IO) {
            val token = authRepository.getValidAccessToken()
                ?: return@withContext Result.failure(IllegalStateException("No valid Google access token"))
            try {
                val service = buildService(token)
                val task = GoogleTask().setTitle(title)
                dueAt?.let { task.due = dueAsRfc3339(it) }
                val created = service.tasks().insert(DEFAULT_LIST, task).execute()
                Log.i(TAG, "Created Google task ${created.id}: $title")
                Result.success(created.id)
            } catch (e: Exception) {
                Log.e(TAG, "createTask failed", e)
                Result.failure(e)
            }
        }

    suspend fun pushCompletion(googleTaskId: String, isDone: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            val token = authRepository.getValidAccessToken()
                ?: return@withContext Result.failure(IllegalStateException("No valid Google access token"))
            try {
                val service = buildService(token)
                val current = service.tasks().get(DEFAULT_LIST, googleTaskId).execute()
                current.status = if (isDone) "completed" else "needsAction"
                // `completed` is RFC 3339 String on the Tasks model; required for "completed", cleared otherwise.
                current.completed = if (isDone) DateTime(System.currentTimeMillis()).toStringRfc3339() else null
                service.tasks().update(DEFAULT_LIST, googleTaskId, current).execute()
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "pushCompletion failed for $googleTaskId", e)
                Result.failure(e)
            }
        }

    suspend fun deleteTask(googleTaskId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val token = authRepository.getValidAccessToken()
                ?: return@withContext Result.failure(IllegalStateException("No valid Google access token"))
            try {
                buildService(token).tasks().delete(DEFAULT_LIST, googleTaskId).execute()
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "deleteTask failed for $googleTaskId", e)
                Result.failure(e)
            }
        }

    private fun dueAsRfc3339(date: LocalDate): String {
        // Tasks model exposes `due` as a String (RFC 3339). The server discards
        // time-of-day on this field but still requires a full timestamp.
        val instant = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return DateTime(instant).toStringRfc3339()
    }
}
