package cloud.wafflecommons.pixelbrainreader.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import cloud.wafflecommons.pixelbrainreader.data.remote.OpenMeteoService
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
    val description: String
)

@Singleton
class WeatherRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: OpenMeteoService
) {

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    suspend fun getCurrentWeatherAndLocation(): WeatherData? {
        val location = getLastKnownLocation() ?: return null
        val city = getCityName(location.latitude, location.longitude)
        
        return try {
            // Use Forecast for Today
            val response = service.getForecast(location.latitude, location.longitude)
            val wmoCode = response.daily.weathercode.firstOrNull() ?: 0
            val maxTemp = response.daily.temperature_2m_max.firstOrNull() ?: 0.0
            
            WeatherData(
                emoji = mapWmoToEmoji(wmoCode),
                temperature = "${maxTemp.toInt()}°C",
                location = city,
                description = "Forecast"
            )
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
                description = "Archive"
            )
        } catch (e: Exception) {
             Log.e("WeatherRepository", "Failed to fetch historical weather", e)
             null
        }
    }

    private suspend fun getLastKnownLocation(): android.location.Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        return suspendCancellableCoroutine { cont ->
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                cont.resume(location)
            }.addOnFailureListener {
                cont.resume(null)
            }.addOnCanceledListener {
                cont.resume(null)
            }
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
}
