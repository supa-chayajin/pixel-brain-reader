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

private const val TAG = "GoogleTaskRepo"
private const val APP_NAME = "PixelBrainReader"

/**
 * Google Tasks one-way importer (Google -> Room).
 *
 * [syncPendingTasks] imports today's tasks from all lists into Room — both
 * incomplete AND completed, preserving Google's status as the source of truth.
 * Push back to Google is intentionally not supported here.
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
        // 401 retry handler: when Google rejects the cached token (early
        // revocation, scope change, clock skew vs our 55-min TTL), invalidate
        // the cache and re-fetch once. runBlocking is acceptable here because
        // the HTTP call is already on Dispatchers.IO.
        val initializer = HttpRequestInitializer { request ->
            request.headers.authorization = "Bearer $token"
            request.unsuccessfulResponseHandler = HttpUnsuccessfulResponseHandler { req, response, supportsRetry ->
                if (response.statusCode == 401 && supportsRetry) {
                    Log.w(TAG, "401 from Tasks API; invalidating cached token and retrying once")
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
                // Server-side narrowing. RFC 3339 with the explicit local offset
                // bounds match the user's actual "today", not a UTC-shifted day.
                val dueMin = DateTime(startInstant.toEpochMilli(), offsetMinutes).toStringRfc3339()
                val dueMax = DateTime(endInstant.toEpochMilli(), offsetMinutes).toStringRfc3339()

                // Wipe today's Google-sourced rows first so the freshly fetched
                // batch is the truth (handles user deletions / completion flips on Google's side).
                taskDao.purgeGoogleTasksForDate(today.toString())

                var total = 0
                for (list in service.tasklists().list().execute().items.orEmpty()) {
                    val tasks = service.tasks().list(list.id)
                        // Include completed + hidden so today's done tasks are imported with
                        // their real status preserved (Rule 3).
                        .setShowCompleted(true)
                        .setShowHidden(true)
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
                        val isCompleted = gt.status == "completed"
                        val existing = taskDao.getTaskByGoogleTaskId(gt.id)
                        val entity = DailyTaskEntity(
                            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                            scheduledDate = today.toString(),
                            label = gt.title ?: "Untitled Task",
                            isDone = isCompleted,
                            googleTaskId = gt.id,
                            source = "GoogleTasks"
                        )
                        taskDao.insertTask(entity)
                        total++
                    }
                }
                Result.success(total)
            } catch (e: Exception) {
                Log.e(TAG, "Tasks import failed", e)
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
}
