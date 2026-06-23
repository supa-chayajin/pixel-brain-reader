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
            if (!userPreferences.isGoogleSyncEnabled.first()) {
                Log.w(TAG, "syncPendingTasks skipped: Google sync is disabled in settings")
                return@withContext Result.success(0)
            }
            val token = authRepository.getValidAccessToken() ?: run {
                Log.w(TAG, "syncPendingTasks skipped: no valid access token (sign in to Google?)")
                return@withContext Result.success(0)
            }
            try {
                val service = buildService(token)
                val zone = ZoneId.systemDefault()
                // Google Tasks stores `due` as DATE-ONLY, serialized at UTC midnight
                // (e.g. 2026-06-23T00:00:00.000Z). With a tight one-day local window the
                // server-side dueMin/dueMax could drop a legitimately-"today" task on
                // devices whose UTC offset pushes that midnight across the boundary —
                // a likely reason Tasks appeared to "not sync at all". Pad the window by
                // a day on each side (covers every real UTC offset); the strict
                // client-side `dueLocal == today` filter below still narrows to exactly
                // today and excludes undated tasks (the user's chosen behavior).
                val startInstant = today.minusDays(1).atStartOfDay(zone).toInstant()
                val endInstant = today.plusDays(2).atStartOfDay(zone).toInstant()
                val offsetMinutes = zone.rules.getOffset(startInstant).totalSeconds / 60
                val dueMin = DateTime(startInstant.toEpochMilli(), offsetMinutes).toStringRfc3339()
                val dueMax = DateTime(endInstant.toEpochMilli(), offsetMinutes).toStringRfc3339()

                // Wipe today's Google-sourced rows first so the freshly fetched
                // batch is the truth (handles user deletions / completion flips on Google's side).
                taskDao.purgeGoogleTasksForDate(today.toString())

                Log.i(TAG, "syncPendingTasks window: $dueMin .. $dueMax (zone=$zone)")
                var total = 0
                val lists = service.tasklists().list().execute().items.orEmpty()
                Log.d(TAG, "Task lists discovered: ${lists.size} -> ${lists.map { it.title ?: it.id }}")
                for (list in lists) {
                    val tasks = service.tasks().list(list.id)
                        // Include completed + hidden so today's done tasks are imported with
                        // their real status preserved (Rule 3).
                        .setShowCompleted(true)
                        .setShowHidden(true)
                        // The padded 3-day window can exceed the default page size (20);
                        // 100 is the API max and is plenty for ~3 days of tasks per list.
                        .setMaxResults(100)
                        .setDueMin(dueMin)
                        .setDueMax(dueMax)
                        .execute()

                    // Strict "today only" filter. A single equality check rejects:
                    //   - tasks with no due date (parseDueToLocalDate returns null)
                    //   - overdue tasks (dueLocal < today)
                    //   - future tasks (dueLocal > today)
                    // Also defends against the server's dueMin/dueMax returning
                    // boundary rows due to RFC 3339 / time-zone quirks.
                    val rawCount = tasks.items.orEmpty().size
                    val todayOnly = tasks.items.orEmpty().filter { gt ->
                        val dueLocal = parseDueToLocalDate(gt.due, zone)
                        dueLocal != null && dueLocal == today
                    }
                    Log.d(TAG, "  '${list.title ?: list.id}': $rawCount returned, ${todayOnly.size} match today after strict filter")

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
                Log.i(TAG, "syncPendingTasks complete: imported $total task(s)")
                Result.success(total)
            } catch (e: Exception) {
                // A 403 here almost always means the Google Tasks API is not enabled in
                // this app's Cloud project (Calendar can work while Tasks 403 — they are
                // separate APIs), or the granted token lacks the Tasks scope. Surface it
                // explicitly so it's actionable instead of a generic failure.
                val msg = e.message.orEmpty()
                if ("403" in msg || "Forbidden" in msg || "SERVICE_DISABLED" in msg ||
                    "insufficient" in msg.lowercase() || "has not been used" in msg
                ) {
                    Log.e(
                        TAG,
                        "Tasks import failed with 403/insufficient access — enable the Google " +
                            "Tasks API for this Cloud project and/or re-consent to the Tasks scope.",
                        e
                    )
                } else {
                    Log.e(TAG, "Tasks import failed", e)
                }
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
