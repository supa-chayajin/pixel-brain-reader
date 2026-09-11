package cloud.wafflecommons.pixelbrainreader.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import cloud.wafflecommons.pixelbrainreader.data.remote.OpenMeteoResponse
import cloud.wafflecommons.pixelbrainreader.data.remote.OpenMeteoService
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.roundToInt

data class WeatherData(
    val emoji: String,
    val temperature: String,
    val location: String?,
    val description: String,
    val code: Int = 0,
    /** Epoch millis of the successful fetch behind this reading (0 for placeholders). */
    val fetchedAt: Long = 0L
)

/** Last successful reading for today, persisted so a cold start shows it instantly (no spinner). */
@Serializable
private data class CachedWeather(
    val date: String,
    val emoji: String,
    val temperature: String,
    val location: String? = null,
    val description: String,
    val code: Int,
    val fetchedAt: Long
) {
    fun toWeatherData() = WeatherData(emoji, temperature, location, description, code, fetchedAt)

    companion object {
        fun of(date: LocalDate, data: WeatherData) =
            CachedWeather(date.toString(), data.emoji, data.temperature, data.location, data.description, data.code, data.fetchedAt)
    }
}

/**
 * Location + Open-Meteo weather.
 *
 * Every external wait is bounded — the cached-location lookup, the active fix, the reverse
 * geocoder and the HTTP call — and the city name can never block the forecast. Previously the
 * geocoder callback's `onError` was not implemented (a no-op default), so any geocoding error
 * (typically a network blip right after the app resumed) suspended the weather fetch FOREVER and
 * the card stopped refreshing.
 */
@Singleton
class WeatherRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: OpenMeteoService
) {

    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val json = Json { ignoreUnknownKeys = true }

    /** Serializes fetches so simultaneous callers share one network round-trip. */
    private val fetchMutex = Mutex()

    @Volatile
    private var memoryCache: CachedWeather? = null
    private val cityCache = ConcurrentHashMap<String, String>()

    /** The last saved reading, whoever fetched it (the Daily card, the board burn, the briefing). */
    private val latestReading = MutableStateFlow<CachedWeather?>(null)

    /**
     * Weather for [date]. For today, a reading younger than [CACHE_TTL_MS] is served from the
     * cache unless [forceRefresh]. Returns null when nothing could be fetched — callers can still
     * show [getCachedWeather].
     */
    suspend fun getWeather(date: LocalDate, forceRefresh: Boolean = false): WeatherData? =
        if (date == LocalDate.now()) {
            getCurrentWeatherAndLocation(maxAgeMs = if (forceRefresh) 0L else CACHE_TTL_MS)
        } else {
            getHistoricalWeather(date)
        }

    /** Last successful reading for [date], whatever its age (memory, then disk), or null. */
    suspend fun getCachedWeather(date: LocalDate): WeatherData? = withContext(Dispatchers.IO) {
        loadCache()?.takeIf { it.date == date.toString() }?.toWeatherData()
    }

    /**
     * The saved reading for [date], updated whenever any caller saves a new one. The Daily card
     * observes it, so a reading fetched elsewhere is never hidden behind a failed or pending refresh
     * of its own. Starts emitting a reading once the cache has been read ([getCachedWeather]).
     */
    fun readingsFor(date: LocalDate): Flow<WeatherData?> =
        latestReading
            .map { entry -> entry?.takeIf { it.date == date.toString() }?.toWeatherData() }
            .distinctUntilChanged()

    suspend fun getCurrentWeatherAndLocation(maxAgeMs: Long = CACHE_TTL_MS): WeatherData? = fetchMutex.withLock {
        val today = LocalDate.now()
        val cached = withContext(Dispatchers.IO) { loadCache() }?.takeIf { it.date == today.toString() }
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < maxAgeMs) {
            return@withLock cached.toWeatherData()
        }
        val fresh = fetchCurrentWeather() ?: return@withLock null
        withContext(Dispatchers.IO) { saveCache(CachedWeather.of(today, fresh)) }
        fresh
    }

    suspend fun getHistoricalWeather(date: LocalDate, lat: Double? = null, long: Double? = null): WeatherData? {
        val location = if (lat == null || long == null) getLastKnownLocation() else null
        val latitude = lat ?: location?.latitude ?: return null
        val longitude = long ?: location?.longitude ?: return null
        val dateStr = date.format(DateTimeFormatter.ISO_DATE)

        return try {
            coroutineScope {
                val city = async { resolveCity(latitude, longitude) }
                val daysAgo = ChronoUnit.DAYS.between(date, LocalDate.now())
                val response = if (daysAgo in -FORECAST_HORIZON_DAYS..RECENT_PAST_DAYS) {
                    service.getRecentDay(latitude, longitude, startDate = dateStr, endDate = dateStr)
                } else {
                    service.getHistoricalWeather(latitude, longitude, startDate = dateStr, endDate = dateStr)
                }
                toWeatherData(response, "Archive") { city.await() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch historical weather", e)
            null
        }
    }

    private suspend fun fetchCurrentWeather(): WeatherData? {
        val location = getLastKnownLocation()
        if (location == null) {
            Log.w(TAG, "Weather skipped: no location (no permission, no cached fix, or the active fix timed out)")
            return null
        }
        return try {
            coroutineScope {
                // The city name is resolved in parallel and is optional: it can never hold up the forecast.
                val city = async { resolveCity(location.latitude, location.longitude) }
                val response = service.getForecast(location.latitude, location.longitude)
                toWeatherData(response, "Forecast") { city.await() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch weather", e)
            null
        }
    }

    private suspend fun toWeatherData(
        response: OpenMeteoResponse,
        description: String,
        city: suspend () -> String?
    ): WeatherData? {
        val daily = response.daily
        val maxTemp = daily?.temperature_2m_max?.firstOrNull()
        if (daily == null || maxTemp == null) {
            // Never fail silently: an empty reading is otherwise indistinguishable from "no network".
            Log.w(TAG, "$description response had no usable daily data (daily present: ${daily != null})")
            return null
        }
        val wmoCode = daily.weatherCodeAt(0) ?: 0
        return WeatherData(
            emoji = mapWmoToEmoji(wmoCode),
            temperature = "${maxTemp.roundToInt()}°C",
            location = city(),
            description = description,
            code = wmoCode,
            fetchedAt = System.currentTimeMillis()
        )
    }

    private suspend fun getLastKnownLocation(): Location? {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(TAG, "ACCESS_COARSE_LOCATION not granted; weather disabled until permission is granted")
            return null
        }

        // Stops the active request if it outlives its timeout (await() alone doesn't cancel the Task).
        val cancellation = CancellationTokenSource()
        return try {
            // Step 1: the cheap cached fix — bounded, a Play-services Task can take arbitrarily long.
            withTimeoutOrNull(LAST_LOCATION_TIMEOUT_MS) { fusedLocationClient.lastLocation.await() }
                // Step 2: no cached fix (fresh install, location was off…): one active BALANCED request.
                ?: withTimeoutOrNull(ACTIVE_FIX_TIMEOUT_MS) {
                    fusedLocationClient
                        .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellation.token)
                        .await()
                }.also { if (it == null) Log.w(TAG, "Active location fix timed out or returned null") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission revoked mid-request", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Location lookup failed", e)
            null
        } finally {
            cancellation.cancel()
        }
    }

    /** Reverse-geocodes to a city name. Bounded, cached per ~1 km cell, and never throws. */
    private suspend fun resolveCity(lat: Double, long: Double): String? {
        val key = String.format(Locale.ROOT, "%.2f,%.2f", lat, long)
        cityCache[key]?.let { return it }
        if (!Geocoder.isPresent()) return null

        val city = withTimeoutOrNull(GEOCODER_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                try {
                    Geocoder(context, Locale.getDefault()).getFromLocation(lat, long, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            val first = addresses.firstOrNull()
                            if (continuation.isActive) continuation.resume(first?.locality ?: first?.subAdminArea)
                        }

                        // The default implementation is a no-op: without this override the
                        // coroutine never resumed on a geocoding error.
                        override fun onError(errorMessage: String?) {
                            Log.w(TAG, "Geocoder error: $errorMessage")
                            if (continuation.isActive) continuation.resume(null)
                        }
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "Geocoder failed", e)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
        if (city != null) cityCache[key] = city
        return city
    }

    private fun loadCache(): CachedWeather? {
        memoryCache?.let { return it }
        val raw = prefs.getString(KEY_TODAY, null) ?: return null
        return try {
            json.decodeFromString<CachedWeather>(raw).also {
                memoryCache = it
                latestReading.value = it
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveCache(entry: CachedWeather) {
        memoryCache = entry
        latestReading.value = entry
        prefs.edit { putString(KEY_TODAY, json.encodeToString(CachedWeather.serializer(), entry)) }
    }

    private fun mapWmoToEmoji(code: Int): String {
        return when (code) {
            0 -> "☀️"
            1, 2, 3 -> "⛅"
            45, 48 -> "🌫️"
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
            71, 73, 75, 77, 85, 86 -> "❄️"
            95, 96, 99 -> "⛈️"
            else -> "🌡️"
        }
    }

    private companion object {
        const val TAG = "WeatherRepository"
        const val PREFS_NAME = "weather_cache"
        const val KEY_TODAY = "today"

        /** The card shows the day's forecast (max temperature + daily code): 30 min is plenty fresh. */
        const val CACHE_TTL_MS = 30 * 60_000L
        const val LAST_LOCATION_TIMEOUT_MS = 5_000L
        const val ACTIVE_FIX_TIMEOUT_MS = 10_000L
        const val GEOCODER_TIMEOUT_MS = 5_000L

        const val RECENT_PAST_DAYS = 90L
        const val FORECAST_HORIZON_DAYS = 15L
    }
}
