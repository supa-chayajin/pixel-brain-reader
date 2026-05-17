package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.auth.GoogleAuthRepository
import cloud.wafflecommons.pixelbrainreader.data.local.dao.TaskDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.HttpUnsuccessfulResponseHandler
import kotlinx.coroutines.runBlocking
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
        // See GoogleCalendarRepository.buildService for the retry-handler rationale.
        val initializer = HttpRequestInitializer { request ->
            request.headers.authorization = "Bearer $token"
            request.unsuccessfulResponseHandler = HttpUnsuccessfulResponseHandler { req, response, supportsRetry ->
                if (response.statusCode == 401 && supportsRetry) {
                    android.util.Log.w(TAG, "401 from Tasks API; invalidating cached token and retrying once")
                    authRepository.invalidateAccessToken()
                    val fresh = runBlocking { authRepository.getValidAccessToken() }
                    if (fresh != null) {
                        req.headers.authorization = "Bearer $fresh"
                        true
                    } else false
                } else false
            }
        }
        return Tasks.Builder(transport, jsonFactory, initializer)
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
                val zone = ZoneId.systemDefault()
                val startInstant = today.atStartOfDay(zone).toInstant()
                val endInstant = today.plusDays(1).atStartOfDay(zone).toInstant()
                val offsetMinutes = zone.rules.getOffset(startInstant).totalSeconds / 60
                // Server-side narrowing. dueMin/dueMax are RFC 3339 strings;
                // we encode the explicit local offset so the bounds match the
                // user's actual "today", not a UTC-shifted day.
                val dueMin = DateTime(startInstant.toEpochMilli(), offsetMinutes).toStringRfc3339()
                val dueMax = DateTime(endInstant.toEpochMilli(), offsetMinutes).toStringRfc3339()

                // Drop yesterday's clean Google rows. Locally-dirty +
                // pending-deletion rows are preserved so TaskSyncWorker can
                // finish draining them.
                taskDao.purgeCleanGoogleTasksForDate(today.toString())

                var total = 0
                for (list in service.tasklists().list().execute().items.orEmpty()) {
                    val tasks = service.tasks().list(list.id)
                        .setShowCompleted(false)
                        .setDueMin(dueMin)
                        .setDueMax(dueMax)
                        .execute()

                    // Strict "today only" filter. A single equality check rejects:
                    //   - tasks with no due date (parseDueToLocalDate returns null)
                    //   - overdue tasks (dueLocal < today)
                    //   - future tasks (dueLocal > today)
                    // Also defends against the server's dueMin/dueMax returning
                    // boundary rows due to RFC 3339 / time-zone quirks.
                    val todayOnly = tasks.items.orEmpty().filter { gt ->
                        val dueLocal = parseDueToLocalDate(gt.due, zone)
                        dueLocal != null && dueLocal == today
                    }

                    todayOnly.forEach { gt ->
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

    /**
     * Google Tasks emits `due` as RFC 3339 (e.g. "2026-05-17T00:00:00.000Z").
     * Returns the calendar date in [zone], or null if missing/unparseable.
     */
    private fun parseDueToLocalDate(due: String?, zone: ZoneId): LocalDate? {
        if (due.isNullOrBlank()) return null
        return try {
            java.time.OffsetDateTime.parse(due).atZoneSameInstant(zone).toLocalDate()
        } catch (e: Exception) {
            null
        }
    }

    private fun dueAsRfc3339(date: LocalDate): String {
        // Tasks model exposes `due` as a String (RFC 3339). The server discards
        // time-of-day on this field but still requires a full timestamp.
        val instant = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return DateTime(instant).toStringRfc3339()
    }
}
