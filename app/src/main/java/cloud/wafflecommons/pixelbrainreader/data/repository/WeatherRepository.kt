package cloud.wafflecommons.pixelbrainreader.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import cloud.wafflecommons.pixelbrainreader.data.remote.OpenMeteoService
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class WeatherData(
    val emoji: String,
    val temperature: String,
    val location: String?,
    val description: String,
    val code: Int = 0
)

@Singleton
class WeatherRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: OpenMeteoService
) {

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    suspend fun getCurrentWeatherAndLocation(): WeatherData? {
        val location = getLastKnownLocation()
        if (location == null) {
            Log.w("WeatherRepository", "Weather skipped: getLastKnownLocation returned null " +
                    "(no permission, no cached fix, or active fetch timed out)")
            return null
        }
        Log.d("WeatherRepository", "Got location: ${location.latitude},${location.longitude}")
        val city = getCityName(location.latitude, location.longitude)
        Log.d("WeatherRepository", "Resolved city: $city")

        return try {
            val response = service.getForecast(location.latitude, location.longitude)
            val wmoCode = response.daily.weathercode.firstOrNull() ?: 0
            val maxTemp = response.daily.temperature_2m_max.firstOrNull() ?: 0.0
            Log.d("WeatherRepository", "OpenMeteo OK: code=$wmoCode, max=$maxTemp")
            WeatherData(
                emoji = mapWmoToEmoji(wmoCode),
                temperature = "${maxTemp.toInt()}°C",
                location = city,
                description = "Forecast",
                code = wmoCode
            )
        } catch (e: CancellationException) {
            // flatMapLatest cancelled us; let the framework see it.
            throw e
        } catch (e: Exception) {
            Log.e("WeatherRepository", "Failed to fetch weather", e)
            null
        }
    }

    suspend fun getHistoricalWeather(date: LocalDate, lat: Double? = null, long: Double? = null): WeatherData? {
        // Fallback to current location if historical location not provided (approximation)
        // In a real app, we might check if we stored location for that date in DB.
        val latitude = lat ?: getLastKnownLocation()?.latitude ?: return null
        val longitude = long ?: getLastKnownLocation()?.longitude ?: return null
        
        val city = getCityName(latitude, longitude)
        val dateStr = date.format(DateTimeFormatter.ISO_DATE)

        return try {
             val response = service.getHistoricalWeather(
                 latitude, longitude, 
                 startDate = dateStr, 
                 endDate = dateStr
             )
            val wmoCode = response.daily.weathercode.firstOrNull() ?: 0
            val maxTemp = response.daily.temperature_2m_max.firstOrNull() ?: 0.0

             WeatherData(
                emoji = mapWmoToEmoji(wmoCode),
                temperature = "${maxTemp.toInt()}°C",
                location = city,
                description = "Archive",
                code = wmoCode
            )
        } catch (e: Exception) {
             Log.e("WeatherRepository", "Failed to fetch historical weather", e)
             null
        }
    }

    private suspend fun getLastKnownLocation(): android.location.Location? {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w("WeatherRepository", "ACCESS_COARSE_LOCATION not granted; weather disabled until permission is granted")
            return null
        }

        // Step 1: try the cheap cached fix.
        val cached: android.location.Location? = suspendCancellableCoroutine { cont ->
            fusedLocationClient.lastLocation
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
                .addOnCanceledListener { cont.resume(null) }
        }
        if (cached != null) return cached

        // Step 2: no cached fix (fresh install, location was off, etc.). Force a one-shot
        // active request with the cheap BALANCED priority. Many devices return null from
        // lastLocation until something has actively asked for a fix at least once.
        // 10s timeout so an offline GPS doesn't keep the weather flow stuck forever —
        // the caller will see null and emit, instead of waiting indefinitely.
        Log.i("WeatherRepository", "lastLocation was null; requesting active fix (10s timeout)")
        return try {
            withTimeoutOrNull(10_000L) {
                suspendCancellableCoroutine<android.location.Location?> { cont ->
                    fusedLocationClient
                        .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener {
                            Log.w("WeatherRepository", "Active location request failed", it)
                            cont.resume(null)
                        }
                        .addOnCanceledListener { cont.resume(null) }
                }
            }.also {
                if (it == null) Log.w("WeatherRepository", "Active location fix timed out or returned null")
            }
        } catch (e: SecurityException) {
            // Permission revoked between our check and the call (rare).
            Log.e("WeatherRepository", "Permission revoked mid-request", e)
            null
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun getCityName(lat: Double, long: Double): String? = withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // New Async API
                suspendCancellableCoroutine<String?> { cont ->
                    geocoder.getFromLocation(lat, long, 1) { addresses ->
                         val city = addresses.firstOrNull()?.locality ?: addresses.firstOrNull()?.subAdminArea ?: "Unknown"
                         cont.resume(city)
                    }
                }
            } else {
                // Legacy Blocking API
                val addresses = geocoder.getFromLocation(lat, long, 1)
                addresses?.firstOrNull()?.locality ?: addresses?.firstOrNull()?.subAdminArea
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("WeatherRepository", "Geocoder failed", e)
            null
        }
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

    fun mapWmoToIcon(code: Int): androidx.compose.ui.graphics.vector.ImageVector {
        val icons = androidx.compose.material.icons.Icons.Rounded
        return icons.AutoAwesome
    }





    fun getParentingAdvice(weather: WeatherData): String {
        return when {
            weather.emoji.contains("🌧️") || weather.emoji.contains("⛈️") -> "Museum / Library"
            weather.emoji.contains("🌫️") -> "Hi-vis vest!"
            weather.temperature.replace("°C", "").toIntOrNull()?.let { it < 10 } == true -> "Hat required"
            weather.temperature.replace("°C", "").toIntOrNull()?.let { it > 25 } == true -> "Sunscreen & Water"
            else -> "Park / Walk"
        }
    }
}
