package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.auth.GoogleAuthManager
import cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyDashboardDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.TaskDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import com.google.api.client.http.HttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.calendar.Calendar
import com.google.api.services.tasks.Tasks
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleSyncRepository @Inject constructor(
    private val googleAuthManager: GoogleAuthManager,
    private val userPreferences: UserPreferencesRepository,
    private val dailyDashboardDao: DailyDashboardDao,
    private val taskDao: TaskDao,
    private val transport: HttpTransport,
    private val jsonFactory: JsonFactory
) {

    private fun getCalendarService(token: String): Calendar {
        val credential = GoogleCredential().setAccessToken(token)
        return Calendar.Builder(transport, jsonFactory, credential)
            .setApplicationName("PixelBrainReader")
            .build()
    }

    private fun getTasksService(token: String): Tasks {
        val credential = GoogleCredential().setAccessToken(token)
        return Tasks.Builder(transport, jsonFactory, credential)
            .setApplicationName("PixelBrainReader")
            .build()
    }

    /**
     * Phase 2: Calendar Sync
     * Fetches all user calendars and maps today's events to the Timeline.
     */
    suspend fun syncTodayCalendarEvents() = withContext(Dispatchers.IO) {
        // Safety Check
        if (!userPreferences.isGoogleSyncEnabled.first()) return@withContext Result.success(0)

        try {
            val token = googleAuthManager.getValidAccessToken() ?: run {
                android.util.Log.w("GoogleSync", "No valid Google access token; skipping calendar sync")
                return@withContext Result.success(0)
            }
            val service = getCalendarService(token)
            
            val today = LocalDate.now()
            val startDateTime = com.google.api.client.util.DateTime(today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
            val endDateTime = com.google.api.client.util.DateTime(today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())

            // 1. Get all calendars
            val calendarList = service.calendarList().list().execute()
            var totalSynced = 0

            for (cal in calendarList.items) {
                // 2. Fetch events for today
                val events = service.events().list(cal.id)
                    .setTimeMin(startDateTime)
                    .setTimeMax(endDateTime)
                    .setSingleEvents(true)
                    .execute()

                val entities = events.items.map { event ->
                    val eventTime = if (event.start.dateTime != null) {
                        java.time.OffsetDateTime.parse(event.start.dateTime.toString()).toLocalTime()
                    } else {
                        LocalTime.MIDNIGHT
                    }

                    TimelineEntryEntity(
                        date = today,
                        time = eventTime,
                        content = "[${cal.summary ?: "Calendar"}] ${event.summary ?: "No Title"}",
                        googleEventId = event.id
                    )
                }

                // 3. Mapping to Room (Upsert)
                entities.forEach { entity ->
                    val existing = dailyDashboardDao.getTimelineEntryByGoogleEventId(entity.googleEventId!!)
                    if (existing != null) {
                        dailyDashboardDao.insertTimelineEntry(entity.copy(id = existing.id))
                    } else {
                        dailyDashboardDao.insertTimelineEntry(entity)
                    }
                }
                totalSynced += entities.size
            }
            Result.success(totalSynced)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Phase 3: Tasks Sync
     * Fetches all task lists and maps incomplete tasks to DailyTaskEntity.
     */
    suspend fun syncPendingGoogleTasks() = withContext(Dispatchers.IO) {
        // Safety Check
        if (!userPreferences.isGoogleSyncEnabled.first()) return@withContext Result.success(0)

        try {
            val token = googleAuthManager.getValidAccessToken() ?: run {
                android.util.Log.w("GoogleSync", "No valid Google access token; skipping tasks sync")
                return@withContext Result.success(0)
            }
            val service = getTasksService(token)
            
            val taskLists = service.tasklists().list().execute()
            var totalSynced = 0

            for (list in taskLists.items) {
                val tasks = service.tasks().list(list.id)
                    .setShowCompleted(false)
                    .execute()

                if (tasks.items == null) continue

                val entities = tasks.items.map { task ->
                    DailyTaskEntity(
                        label = task.title ?: "Untitled Task",
                        scheduledDate = LocalDate.now().toString(),
                        isDone = false,
                        googleTaskId = task.id,
                        source = "GoogleTasks"
                    )
                }

                entities.forEach { entity ->
                    val existing = taskDao.getTaskByGoogleTaskId(entity.googleTaskId!!)
                    if (existing != null) {
                        taskDao.updateTask(entity.copy(id = existing.id))
                    } else {
                        taskDao.insertTask(entity)
                    }
                }
                totalSynced += entities.size
            }
            Result.success(totalSynced)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
