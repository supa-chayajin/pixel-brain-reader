package cloud.wafflecommons.pixelbrainreader.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

interface OpenMeteoService {

    /** Today's daily forecast. */
    @GET("v1/forecast")
    suspend fun getForecast(
        @Query("latitude") lat: Double,
        @Query("longitude") long: Double,
        @Query("daily") daily: String = "weathercode,temperature_2m_max,temperature_2m_min",
        @Query("timezone") timezone: String = "auto",
        @Query("forecast_days") days: Int = 1
    ): OpenMeteoResponse

    /**
     * A specific recent day. The forecast API serves roughly the last three months (and the near
     * future) through start/end dates — which also covers the ~5 days the archive is behind.
     */
    @GET("v1/forecast")
    suspend fun getRecentDay(
        @Query("latitude") lat: Double,
        @Query("longitude") long: Double,
        @Query("start_date") startDate: String,
        @Query("end_date") endDate: String,
        @Query("daily") daily: String = "weather_code,temperature_2m_max,temperature_2m_min",
        @Query("timezone") timezone: String = "auto"
    ): OpenMeteoResponse

    /**
     * ERA5 reanalysis for older days. Lives on its OWN host: the previous
     * `api.open-meteo.com/v1/archive` path does not exist, so every past-day lookup failed.
     */
    @GET("https://archive-api.open-meteo.com/v1/archive")
    suspend fun getHistoricalWeather(
        @Query("latitude") lat: Double,
        @Query("longitude") long: Double,
        @Query("start_date") startDate: String,
        @Query("end_date") endDate: String,
        @Query("daily") daily: String = "weather_code,temperature_2m_max,temperature_2m_min",
        @Query("timezone") timezone: String = "auto"
    ): OpenMeteoResponse
}

data class OpenMeteoResponse(
    val daily: DailyUnits? = null
)

/**
 * Gson-mapped (fields are kept by R8, see proguard-rules.pro). Everything is nullable with defaults:
 * Gson ignores Kotlin non-null guarantees, so a missing key used to surface as a null "List" and
 * crash the caller. Open-Meteo names the weather code `weathercode` (legacy) or `weather_code`
 * depending on the variable requested, so both are mapped.
 */
data class DailyUnits(
    val time: List<String>? = null,
    val weathercode: List<Int>? = null,
    @SerializedName("weather_code") val weatherCode: List<Int>? = null,
    val temperature_2m_max: List<Double>? = null,
    val temperature_2m_min: List<Double>? = null
) {
    fun weatherCodeAt(index: Int): Int? = (weatherCode ?: weathercode)?.getOrNull(index)
}
