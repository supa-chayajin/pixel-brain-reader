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
import com.google.api.services.calendar.model.EventDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GoogleCalendarRepo"
private const val APP_NAME = "PixelBrainReader"
private const val PRIMARY_CALENDAR = "primary"

/**
 * Google Calendar bidirectional sync.
 *
 * Read: [syncTodayEvents] imports today's events from all calendars into Room.
 * Write: [createEvent] / [updateEvent] / [deleteEvent] target the user's
 * primary calendar. Multi-calendar write is intentionally out of scope.
 *
 * Token plumbing uses [HttpRequestInitializer] (the non-deprecated path):
 * each HTTP request stamps its own bearer header. The deprecated
 * GoogleCredential() approach is gone.
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
        // Initializer stamps the bearer header AND attaches a 401 retry handler:
        // when Google rejects the cached token (early revocation, scope change,
        // clock skew vs our 55-min TTL), invalidate the cache and re-fetch a
        // fresh token via AuthorizationClient. runBlocking is acceptable here
        // because the HTTP call is already on Dispatchers.IO and the handler
        // is sync by design.
        val initializer = HttpRequestInitializer { request ->
            request.headers.authorization = "Bearer $token"
            request.unsuccessfulResponseHandler = HttpUnsuccessfulResponseHandler { req, response, supportsRetry ->
                if (response.statusCode == 401 && supportsRetry) {
                    android.util.Log.w(TAG, "401 from Calendar API; invalidating cached token and retrying once")
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

    // --- READ -----------------------------------------------------------------

    suspend fun syncTodayEvents(today: LocalDate = LocalDate.now()): Result<Int> =
        withContext(Dispatchers.IO) {
            if (!userPreferences.isGoogleSyncEnabled.first()) return@withContext Result.success(0)
            val token = authRepository.getValidAccessToken() ?: run {
                Log.w(TAG, "No valid token; skipping calendar import")
                return@withContext Result.success(0)
            }
            try {
                val service = buildService(token)
                val zone = ZoneId.systemDefault()
                // Compose timeMin / timeMax with the explicit local offset baked
                // into the RFC 3339 string (e.g. 2026-05-17T00:00:00.000+02:00),
                // not the UTC `Z` form. The Calendar API accepts both, but the
                // explicit-offset form is the unambiguous spec for "local today".
                val startInstant = today.atStartOfDay(zone).toInstant()
                val endInstant = today.plusDays(1).atStartOfDay(zone).toInstant()
                val offsetMinutes = zone.rules.getOffset(startInstant).totalSeconds / 60
                val timeMin = DateTime(startInstant.toEpochMilli(), offsetMinutes)
                val timeMax = DateTime(endInstant.toEpochMilli(), offsetMinutes)

                // FK guard: timeline_entries(date) → daily_dashboard(date). Insert
                // the parent dashboard row first (IGNORE on conflict) so the
                // upserts below don't trip SQLITE_CONSTRAINT_FOREIGNKEY when
                // today's dashboard hasn't been materialized yet.
                dailyDashboardDao.insertDashboard(
                    cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyDashboardEntity(date = today)
                )

                // Drop yesterday's Calendar-sourced timeline rows (and rows for
                // events the user moved to another day in Google's UI). Locally-
                // created entries (googleEventId IS NULL) are preserved.
                dailyDashboardDao.purgeGoogleTimelineForDate(today)

                var total = 0
                for (cal in service.calendarList().list().execute().items.orEmpty()) {
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
                    events.items.orEmpty().forEach { evt ->
                        val entity = TimelineEntryEntity(
                            date = today,
                            time = extractStartTime(evt),
                            content = "[${cal.summary ?: "Calendar"}] ${evt.summary ?: "No title"}",
                            googleEventId = evt.id
                        )
                        val existing = dailyDashboardDao
                            .getTimelineEntryByGoogleEventId(entity.googleEventId!!)
                        dailyDashboardDao.insertTimelineEntry(
                            if (existing != null) entity.copy(id = existing.id) else entity
                        )
                        total++
                    }
                }
                Result.success(total)
            } catch (e: Exception) {
                Log.e(TAG, "Calendar import failed", e)
                Result.failure(e)
            }
        }

    // --- WRITE ----------------------------------------------------------------

    suspend fun createEvent(
        title: String,
        startsAt: LocalDateTime,
        endsAt: LocalDateTime = startsAt.plusHours(1)
    ): Result<String> = withContext(Dispatchers.IO) {
        val token = authRepository.getValidAccessToken()
            ?: return@withContext Result.failure(IllegalStateException("No valid Google access token"))
        try {
            val service = buildService(token)
            val tz = TimeZone.getDefault().id
            val event = Event()
                .setSummary(title)
                .setStart(EventDateTime().setDateTime(toApiDateTime(startsAt)).setTimeZone(tz))
                .setEnd(EventDateTime().setDateTime(toApiDateTime(endsAt)).setTimeZone(tz))
            val created = service.events().insert(PRIMARY_CALENDAR, event).execute()
            Log.i(TAG, "Created event ${created.id}: $title @ $startsAt")
            Result.success(created.id)
        } catch (e: Exception) {
            Log.e(TAG, "createEvent failed", e)
            Result.failure(e)
        }
    }

    suspend fun updateEvent(
        eventId: String,
        title: String? = null,
        startsAt: LocalDateTime? = null,
        endsAt: LocalDateTime? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val token = authRepository.getValidAccessToken()
            ?: return@withContext Result.failure(IllegalStateException("No valid Google access token"))
        try {
            val service = buildService(token)
            val current = service.events().get(PRIMARY_CALENDAR, eventId).execute()
            title?.let { current.summary = it }
            val tz = TimeZone.getDefault().id
            startsAt?.let {
                current.start = EventDateTime().setDateTime(toApiDateTime(it)).setTimeZone(tz)
            }
            endsAt?.let {
                current.end = EventDateTime().setDateTime(toApiDateTime(it)).setTimeZone(tz)
            }
            service.events().update(PRIMARY_CALENDAR, eventId, current).execute()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "updateEvent failed for $eventId", e)
            Result.failure(e)
        }
    }

    suspend fun deleteEvent(eventId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val token = authRepository.getValidAccessToken()
            ?: return@withContext Result.failure(IllegalStateException("No valid Google access token"))
        try {
            buildService(token).events().delete(PRIMARY_CALENDAR, eventId).execute()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "deleteEvent failed for $eventId", e)
            Result.failure(e)
        }
    }

    // --- Helpers --------------------------------------------------------------

    private fun extractStartTime(evt: Event): LocalTime {
        val raw = evt.start?.dateTime?.toString() ?: return LocalTime.MIDNIGHT
        return try {
            OffsetDateTime.parse(raw).toLocalTime()
        } catch (e: Exception) {
            LocalTime.MIDNIGHT
        }
    }

    private fun toApiDateTime(local: LocalDateTime): DateTime {
        val epoch = local.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return DateTime(epoch)
    }
}
