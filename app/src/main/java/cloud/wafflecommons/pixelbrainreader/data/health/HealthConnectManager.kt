package cloud.wafflecommons.pixelbrainreader.data.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class HeartRatePoint(val timestamp: Instant, val avgBpm: Double)

data class DailyHealthMetrics(
    val date: String,
    val steps: Long,
    val sleepDurationMinutes: Long,
    val averageHeartRate: Int,
    val waterConsumedMl: Double = 0.0,
    val caloriesConsumed: Double = 0.0,
    val mindfulnessMinutes: Long = 0L,
    val weight: Double = 0.0
)

@Singleton
class HealthConnectManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    fun checkAvailability(): Int {
        return HealthConnectClient.getSdkStatus(context)
    }

    @Suppress("OPT_IN_USAGE", "OPT_IN_USAGE_ERROR")
    suspend fun getDailyMetrics(date: LocalDate): DailyHealthMetrics = withContext(Dispatchers.IO) {
        val zoneId = ZoneId.systemDefault()
        
        // Daily filter: 00:00 to 23:59 of current date
        val startOfDay = date.atStartOfDay(zoneId).toInstant()
        val endOfDay = date.plusDays(1).atStartOfDay(zoneId).toInstant()
        val timeRangeFilter = TimeRangeFilter.between(startOfDay, endOfDay)

        // Sleep filter: 18:00 previous day to 18:00 current day
        val sleepStart = date.minusDays(1).atTime(18, 0).atZone(zoneId).toInstant()
        val sleepEnd = date.atTime(18, 0).atZone(zoneId).toInstant()
        val sleepFilter = TimeRangeFilter.between(sleepStart, sleepEnd)

        // 1. Professional Aggregation (Deduplicated by platform)
        val aggregateResponse = try {
            healthConnectClient.aggregate(
                AggregateRequest(
                    metrics = setOf(
                        StepsRecord.COUNT_TOTAL,
                        HydrationRecord.VOLUME_TOTAL,
                        NutritionRecord.ENERGY_TOTAL
                    ),
                    timeRangeFilter = timeRangeFilter
                )
            )
        } catch (e: Exception) {
            Log.e("HealthConnectManager", "failed to aggregate metrics", e)
            null
        }

        val totalSteps = aggregateResponse?.get(StepsRecord.COUNT_TOTAL) ?: 0L
        val waterConsumedMl = aggregateResponse?.get(HydrationRecord.VOLUME_TOTAL)?.inMilliliters ?: 0.0
        
        var caloriesConsumed = 0.0
        try {
            val nutritionRequest = ReadRecordsRequest(NutritionRecord::class, timeRangeFilter)
            val nutritionResponse = healthConnectClient.readRecords(nutritionRequest)
            caloriesConsumed = nutritionResponse.records.sumOf { it.energy?.inKilocalories ?: 0.0 }
        } catch (e: Exception) {
            Log.e("HealthConnectManager", "Failed to read nutrition", e)
            caloriesConsumed = aggregateResponse?.get(NutritionRecord.ENERGY_TOTAL)?.inKilocalories ?: 0.0
        }

        // 2. Read Sleep Sessions (Still involves some manual logic for overlaps)
        val sleepRequest = ReadRecordsRequest(SleepSessionRecord::class, sleepFilter)
        val sleepResponse = try { healthConnectClient.readRecords(sleepRequest) } catch (e: Exception) { null }
        
        val totalSleepMillis = sleepResponse?.records?.filter { 
            it.endTime.atZone(zoneId).toLocalDate() == date 
        }?.sumOf {
            it.endTime.toEpochMilli() - it.startTime.toEpochMilli()
        } ?: 0L
        val sleepDurationMinutes = totalSleepMillis / (1000 * 60)

        // 3. Read Heart Rate Samples
        val heartRateRequest = ReadRecordsRequest(HeartRateRecord::class, timeRangeFilter)
        val heartRateResponse = try { healthConnectClient.readRecords(heartRateRequest) } catch (e: Exception) { null }
        var totalBpm = 0L
        var heartRateCount = 0
        heartRateResponse?.records?.forEach { record ->
            record.samples.forEach { sample ->
                totalBpm += sample.beatsPerMinute
                heartRateCount++
            }
        }
        val averageHeartRate = if (heartRateCount > 0) (totalBpm / heartRateCount).toInt() else 0

        // 4. Read Mindfulness / Exercise
        var mindfulnessMinutes = 0L
        try {
            val minRequest = ReadRecordsRequest(androidx.health.connect.client.records.MindfulnessSessionRecord::class, timeRangeFilter)
            val minResponse = healthConnectClient.readRecords(minRequest)
            mindfulnessMinutes += minResponse.records.sumOf {
                Duration.between(it.startTime, it.endTime).toMinutes()
            }
        } catch (e: Exception) {
            Log.e("HealthConnectManager", "Failed to read mindfulness session", e)
        }
        
        try {
            val exerciseRequest = ReadRecordsRequest(ExerciseSessionRecord::class, timeRangeFilter)
            val exerciseResponse = healthConnectClient.readRecords(exerciseRequest)
            mindfulnessMinutes += exerciseResponse.records.filter { 
                it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_YOGA
            }.sumOf {
                Duration.between(it.startTime, it.endTime).toMinutes()
            }
        } catch (e: Exception) {
            Log.e("HealthConnectManager", "Failed to read exercise mindfulness", e)
        }

        DailyHealthMetrics(
            date = date.toString(),
            steps = totalSteps,
            sleepDurationMinutes = sleepDurationMinutes,
            averageHeartRate = averageHeartRate,
            waterConsumedMl = waterConsumedMl,
            caloriesConsumed = caloriesConsumed,
            mindfulnessMinutes = mindfulnessMinutes
        )
    }

    private val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(HydrationRecord::class),
        HealthPermission.getReadPermission(NutritionRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    )

    fun getSdkStatus(): Int {
        return HealthConnectClient.getSdkStatus(context)
    }
    
    suspend fun checkPermissions(): Boolean {
        return try {
            val granted = healthConnectClient.permissionController.getGrantedPermissions()
            val hasAll = granted.containsAll(permissions)
            hasAll
        } catch (e: Exception) {
            false
        }
    }
    
    suspend fun requestPermissions() {
    }
    
    fun getRequiredPermissions() = permissions

    suspend fun readSteps(start: Instant, end: Instant): Long = withContext(Dispatchers.IO) {
        try {
            val response = healthConnectClient.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            response[StepsRecord.COUNT_TOTAL] ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    suspend fun readSleepDuration(start: Instant, end: Instant): Duration = withContext(Dispatchers.IO) {
        try {
            val response = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            response.records.fold(Duration.ZERO) { acc, session ->
                val sessionDuration = Duration.between(session.startTime, session.endTime)
                acc.plus(sessionDuration)
            }
        } catch (e: Exception) {
            Duration.ZERO
        }
    }

    suspend fun readHeartRate(start: Instant, end: Instant): List<HeartRateRecord> = withContext(Dispatchers.IO) {
        try {
            val response = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            response.records
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun readDailySteps(start: Instant, end: Instant): Map<LocalDate, Long> = withContext(Dispatchers.IO) {
        try {
            val zone = ZoneId.systemDefault()
            val localStart = java.time.LocalDateTime.ofInstant(start, zone)
            val localEnd = java.time.LocalDateTime.ofInstant(end, zone)

            val response = healthConnectClient.aggregateGroupByPeriod(
                androidx.health.connect.client.request.AggregateGroupByPeriodRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(localStart, localEnd),
                    timeRangeSlicer = java.time.Period.ofDays(1)
                )
            )
            response.associate { result ->
                val date = result.startTime.toLocalDate()
                val steps = result.result[StepsRecord.COUNT_TOTAL] ?: 0L
                date to steps
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    suspend fun readDailySleep(start: Instant, end: Instant): Map<LocalDate, Duration> = withContext(Dispatchers.IO) {
        try {
            val response = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            val map = mutableMapOf<LocalDate, Duration>()
            response.records.forEach { session ->
                val date = LocalDate.ofInstant(session.endTime, ZoneId.systemDefault())
                val duration = Duration.between(session.startTime, session.endTime)
                val current = map.getOrDefault(date, Duration.ZERO)
                map[date] = current.plus(duration)
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    suspend fun readHeartRateHistory(start: Instant, end: Instant, bucketSize: Duration): List<HeartRatePoint> = withContext(Dispatchers.IO) {
        try {
            val response = healthConnectClient.aggregateGroupByDuration(
                androidx.health.connect.client.request.AggregateGroupByDurationRequest(
                    metrics = setOf(HeartRateRecord.BPM_AVG),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    timeRangeSlicer = bucketSize
                )
            )
            response.mapNotNull { result ->
                val avg = result.result[HeartRateRecord.BPM_AVG]
                if (avg != null) {
                    HeartRatePoint(
                        timestamp = result.startTime,
                        avgBpm = avg.toDouble()
                    )
                } else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
