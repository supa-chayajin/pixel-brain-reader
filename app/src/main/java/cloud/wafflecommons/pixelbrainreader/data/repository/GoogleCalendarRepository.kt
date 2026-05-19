package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.auth.GoogleAuthRepository
import cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyDashboardDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.HttpUnsuccessfulResponseHandler
import kotlinx.coroutines.runBlocking
import com.google.api.client.json.JsonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.model.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GoogleCalendarRepo"
private const val APP_NAME = "PixelBrainReader"

/**
 * Google Calendar one-way importer (Google -> Room).
 *
 * [syncTodayEvents] imports today's events from all the user's calendars into
 * Room. Push back to Google is intentionally not supported here.
 */
@Singleton
class GoogleCalendarRepository @Inject constructor(
    private val authRepository: GoogleAuthRepository,
    private val userPreferences: UserPreferencesRepository,
    private val dailyDashboardDao: DailyDashboardDao,
    private val transport: HttpTransport,
    private val jsonFactory: JsonFactory
) {

    private fun buildService(token: String): Calendar {
        // 401 retry handler: see GoogleTaskRepository.buildService for rationale.
        val initializer = HttpRequestInitializer { request ->
            request.headers.authorization = "Bearer $token"
            request.unsuccessfulResponseHandler = HttpUnsuccessfulResponseHandler { req, response, supportsRetry ->
                if (response.statusCode == 401 && supportsRetry) {
                    Log.w(TAG, "401 from Calendar API; invalidating cached token and retrying once")
                    authRepository.invalidateAccessToken()
                    val fresh = runBlocking { authRepository.getValidAccessToken() }
                    if (fresh != null) {
                        req.headers.authorization = "Bearer $fresh"
                        true
                    } else false
                } else false
            }
        }
        return Calendar.Builder(transport, jsonFactory, initializer)
            .setApplicationName(APP_NAME)
            .build()
    }

    suspend fun syncTodayEvents(today: LocalDate = LocalDate.now()): Result<Int> =
        withContext(Dispatchers.IO) {
            if (!userPreferences.isGoogleSyncEnabled.first()) {
                Log.w(TAG, "syncTodayEvents skipped: Google sync is disabled in settings")
                return@withContext Result.success(0)
            }
            val token = authRepository.getValidAccessToken() ?: run {
                Log.w(TAG, "syncTodayEvents skipped: no valid access token (sign in to Google?)")
                return@withContext Result.success(0)
            }
            try {
                val service = buildService(token)
                val zone = ZoneId.systemDefault()
                // Compose timeMin / timeMax with the explicit local offset baked
                // into the RFC 3339 string (e.g. 2026-05-17T00:00:00.000+02:00),
                // not the UTC `Z` form. Unambiguous spec for "local today".
                val startInstant = today.atStartOfDay(zone).toInstant()
                val endInstant = today.plusDays(1).atStartOfDay(zone).toInstant()
                val offsetMinutes = zone.rules.getOffset(startInstant).totalSeconds / 60
                val timeMin = DateTime(startInstant.toEpochMilli(), offsetMinutes)
                val timeMax = DateTime(endInstant.toEpochMilli(), offsetMinutes)
                Log.i(TAG, "syncTodayEvents window: $timeMin .. $timeMax (zone=$zone)")

                // FK guard: timeline_entries(date) -> daily_dashboard(date). Insert
                // the parent dashboard row first (IGNORE on conflict) so the
                // upserts below don't trip SQLITE_CONSTRAINT_FOREIGNKEY when
                // today's dashboard hasn't been materialized yet.
                dailyDashboardDao.insertDashboard(
                    cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyDashboardEntity(date = today)
                )

                // Wipe today's Google-sourced timeline rows first so the freshly
                // fetched batch is the truth (handles user deletions / moves on Google's side).
                // Locally-created entries (googleEventId IS NULL) are preserved.
                dailyDashboardDao.purgeGoogleTimelineForDate(today)

                var total = 0
                val calendars = service.calendarList().list().execute().items.orEmpty()
                Log.i(TAG, "Calendars discovered: ${calendars.size} -> ${calendars.map { it.summary ?: it.id }}")
                for (cal in calendars) {
                    val events = service.events().list(cal.id)
                        .setTimeMin(timeMin)
                        .setTimeMax(timeMax)
                        .setSingleEvents(true)
                        // Expand recurring events in the user's local TZ so
                        // occurrences land on the same calendar day they would
                        // visually appear in Google Calendar.
                        .setTimeZone(zone.id)
                        .setOrderBy("startTime")
                        .execute()
                    val items = events.items.orEmpty()
                    Log.i(TAG, "  '${cal.summary ?: cal.id}': ${items.size} event(s) in window")
                    items.forEach { evt ->
                        val existing = dailyDashboardDao
                            .getTimelineEntryByGoogleEventId(evt.id)
                        val entity = TimelineEntryEntity(
                            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                            date = today,
                            time = extractStartTime(evt),
                            content = "[${cal.summary ?: "Calendar"}] ${evt.summary ?: "No title"}",
                            googleEventId = evt.id
                        )
                        dailyDashboardDao.insertTimelineEntry(entity)
                        total++
                    }
                }
                Log.i(TAG, "syncTodayEvents complete: imported $total event(s)")
                Result.success(total)
            } catch (e: Exception) {
                Log.e(TAG, "Calendar import failed", e)
                Result.failure(e)
            }
        }

    private fun extractStartTime(evt: Event): LocalTime {
        val raw = evt.start?.dateTime?.toString() ?: return LocalTime.MIDNIGHT
        return try {
            OffsetDateTime.parse(raw).toLocalTime()
        } catch (e: Exception) {
            LocalTime.MIDNIGHT
        }
    }
}
