package cloud.wafflecommons.pixelbrainreader.data.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class HeartRatePoint(val timestamp: Instant, val avgBpm: Double)

@Serializable
data class DailyHealthMetrics(
    val date: String,
    val steps: Long,
    val distanceKm: Double = 0.0,
    val activeMinutes: Long = 0L,
    val sleepDurationMinutes: Long,
    val averageHeartRate: Int,
    val waterConsumedMl: Double = 0.0,
    val caloriesConsumed: Double = 0.0,
    val caloriesBurned: Double = 0.0,
    val mindfulnessMinutes: Long = 0L,
    val weight: Double = 0.0,
    // Recovery signals (v11). 0 = not measured.
    val restingHeartRate: Int = 0,
    val hrvRmssdMs: Double = 0.0,
    val sleepDeepMinutes: Long = 0L,
    val sleepRemMinutes: Long = 0L,
    val sleepLightMinutes: Long = 0L,
    val sleepAwakeMinutes: Long = 0L
)

@Singleton
class HealthConnectManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    fun checkAvailability(): Int {
        return HealthConnectClient.getSdkStatus(context)
    }

    @Suppress("OPT_IN_USAGE", "OPT_IN_USAGE_ERROR")
    suspend fun getDailyMetrics(date: LocalDate): DailyHealthMetrics = withContext(Dispatchers.IO) {
        val zoneId = ZoneId.systemDefault()
        
        // Daily filter: 00:00 to 23:59 of current date or now if today
        val startOfDay = date.atStartOfDay(zoneId).toInstant()
        val endOfDay = if (date == java.time.LocalDate.now(zoneId)) {
            java.time.Instant.now()
        } else {
            date.plusDays(1).atStartOfDay(zoneId).toInstant()
        }
        val timeRangeFilter = TimeRangeFilter.between(startOfDay, endOfDay)

        // Sleep filter: 18:00 previous day to 18:00 current day
        val sleepStart = date.minusDays(1).atTime(18, 0).atZone(zoneId).toInstant()
        val sleepEnd = date.atTime(18, 0).atZone(zoneId).toInstant()
        val sleepFilter = TimeRangeFilter.between(sleepStart, sleepEnd)

        val granted = try {
            healthConnectClient.permissionController.getGrantedPermissions()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }

        // 1. Professional Aggregation (Deduplicated by platform)
        val aggregateMetrics = mutableSetOf<androidx.health.connect.client.aggregate.AggregateMetric<*>>()
        if (granted.contains(HealthPermission.getReadPermission(StepsRecord::class))) aggregateMetrics.add(StepsRecord.COUNT_TOTAL)
        if (granted.contains(HealthPermission.getReadPermission(HydrationRecord::class))) aggregateMetrics.add(HydrationRecord.VOLUME_TOTAL)
        if (granted.contains(HealthPermission.getReadPermission(NutritionRecord::class))) aggregateMetrics.add(NutritionRecord.ENERGY_TOTAL)
        if (granted.contains(HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class))) aggregateMetrics.add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
        if (granted.contains(HealthPermission.getReadPermission(DistanceRecord::class))) aggregateMetrics.add(DistanceRecord.DISTANCE_TOTAL)

        val aggregateResponse = if (aggregateMetrics.isNotEmpty()) {
            try {
                healthConnectClient.aggregate(AggregateRequest(metrics = aggregateMetrics, timeRangeFilter = timeRangeFilter))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HealthConnectManager", "failed to aggregate metrics", e)
                null
            }
        } else null

        val totalSteps = if (aggregateMetrics.contains(StepsRecord.COUNT_TOTAL)) aggregateResponse?.get(StepsRecord.COUNT_TOTAL) ?: 0L else 0L
        val waterConsumedMl = if (aggregateMetrics.contains(HydrationRecord.VOLUME_TOTAL)) aggregateResponse?.get(HydrationRecord.VOLUME_TOTAL)?.inMilliliters ?: 0.0 else 0.0
        val distanceKm = if (aggregateMetrics.contains(DistanceRecord.DISTANCE_TOTAL)) aggregateResponse?.get(DistanceRecord.DISTANCE_TOTAL)?.inKilometers ?: 0.0 else 0.0
        
        var caloriesConsumed = 0.0
        if (granted.contains(HealthPermission.getReadPermission(NutritionRecord::class))) {
            try {
                val nutritionRequest = ReadRecordsRequest(NutritionRecord::class, timeRangeFilter)
                val nutritionResponse = healthConnectClient.readRecords(nutritionRequest)
                caloriesConsumed = nutritionResponse.records.sumOf { it.energy?.inKilocalories ?: 0.0 }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HealthConnectManager", "Failed to read nutrition", e)
                caloriesConsumed = aggregateResponse?.get(NutritionRecord.ENERGY_TOTAL)?.inKilocalories ?: 0.0
            }
        }

        var caloriesBurned = if (aggregateMetrics.contains(TotalCaloriesBurnedRecord.ENERGY_TOTAL)) aggregateResponse?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories ?: 0.0 else 0.0
        if (caloriesBurned == 0.0 && granted.contains(HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class))) {
            try {
                val burnedRequest = ReadRecordsRequest(TotalCaloriesBurnedRecord::class, timeRangeFilter)
                val burnedResponse = healthConnectClient.readRecords(burnedRequest)
                caloriesBurned = burnedResponse.records.sumOf { it.energy.inKilocalories }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HealthConnectManager", "Failed to read calories burned", e)
            }
        }

        // 2. Read Sleep Sessions (duration, and the stages when the device records them)
        var sleepDurationMinutes = 0L
        var sleepStages = SleepStages()
        if (granted.contains(HealthPermission.getReadPermission(SleepSessionRecord::class))) {
            try {
                val sleepRequest = ReadRecordsRequest(SleepSessionRecord::class, sleepFilter)
                val sleepResponse = healthConnectClient.readRecords(sleepRequest)
                val nightSessions = sleepResponse.records.filter {
                    it.endTime.atZone(zoneId).toLocalDate() == date
                }
                sleepDurationMinutes = nightSessions.sumOf {
                    Duration.between(it.startTime, it.endTime).toMinutes()
                }
                sleepStages = nightSessions.fold(SleepStages()) { acc, session -> acc + stagesOf(session) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HealthConnectManager", "Failed to read sleep", e)
            }
        }

        // 3. Average heart rate, aggregated (and deduplicated across apps) by Health Connect. Reading
        // every sample of the day built thousands of objects per refresh from one page of records.
        var averageHeartRate = 0
        if (granted.contains(HealthPermission.getReadPermission(HeartRateRecord::class))) {
            try {
                val heartRate = healthConnectClient.aggregate(
                    AggregateRequest(metrics = setOf(HeartRateRecord.BPM_AVG), timeRangeFilter = timeRangeFilter)
                )
                averageHeartRate = heartRate[HeartRateRecord.BPM_AVG]?.toInt() ?: 0
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HealthConnectManager", "Failed to read heart rate", e)
            }
        }

        // 3b. Recovery: resting heart rate over the day, HRV (RMSSD) over the night's sleep window.
        var restingHeartRate = 0
        if (granted.contains(HealthPermission.getReadPermission(RestingHeartRateRecord::class))) {
            try {
                val resting = healthConnectClient.aggregate(
                    AggregateRequest(metrics = setOf(RestingHeartRateRecord.BPM_AVG), timeRangeFilter = timeRangeFilter)
                )
                restingHeartRate = resting[RestingHeartRateRecord.BPM_AVG]?.toInt() ?: 0
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HealthConnectManager", "Failed to read resting heart rate", e)
            }
        }
        var hrvRmssdMs = 0.0
        if (granted.contains(HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class))) {
            try {
                val hrv = healthConnectClient.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, sleepFilter))
                hrvRmssdMs = hrv.records.map { it.heartRateVariabilityMillis }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HealthConnectManager", "Failed to read heart rate variability", e)
            }
        }

        // 4. Read Mindfulness / Exercise
        var mindfulnessMinutes = 0L
        var activeMinutes = 0L

        if (granted.contains(HealthPermission.getReadPermission(MindfulnessSessionRecord::class))) {
            try {
                val minRequest = ReadRecordsRequest(MindfulnessSessionRecord::class, timeRangeFilter)
                val minResponse = healthConnectClient.readRecords(minRequest)
                mindfulnessMinutes += minResponse.records.sumOf {
                    Duration.between(it.startTime, it.endTime).toMinutes()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HealthConnectManager", "Failed to read mindfulness session", e)
            }
        }
        
        if (granted.contains(HealthPermission.getReadPermission(ExerciseSessionRecord::class))) {
            try {
                val exerciseRequest = ReadRecordsRequest(ExerciseSessionRecord::class, timeRangeFilter)
                val exerciseResponse = healthConnectClient.readRecords(exerciseRequest)
                
                // Yoga / Meditation
                mindfulnessMinutes += exerciseResponse.records.filter { 
                    it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_YOGA
                }.sumOf {
                    Duration.between(it.startTime, it.endTime).toMinutes()
                }

                // Active minutes (excluding yoga/meditation which is mindfulness)
                activeMinutes += exerciseResponse.records.filter { 
                    it.exerciseType != ExerciseSessionRecord.EXERCISE_TYPE_YOGA
                }.sumOf {
                    Duration.between(it.startTime, it.endTime).toMinutes()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HealthConnectManager", "Failed to read exercise mindfulness", e)
            }
        }
        
        if (granted.contains(HealthPermission.getReadPermission(androidx.health.connect.client.records.ActiveCaloriesBurnedRecord::class))) {
            try {
                val activeCaloriesRequest = ReadRecordsRequest(androidx.health.connect.client.records.ActiveCaloriesBurnedRecord::class, timeRangeFilter)
                val activeCaloriesResponse = healthConnectClient.readRecords(activeCaloriesRequest)
                activeMinutes += activeCaloriesResponse.records.sumOf {
                    Duration.between(it.startTime, it.endTime).toMinutes()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HealthConnectManager", "Failed to read active calories burned", e)
            }
        }

        // 5. Latest body weight (kg) logged in range — 0.0 on days with no weigh-in.
        // Previously never read, so the "weight" habit auto-source was permanently stuck at 0.
        var weightKg = 0.0
        if (granted.contains(HealthPermission.getReadPermission(androidx.health.connect.client.records.WeightRecord::class))) {
            try {
                val weightRequest = ReadRecordsRequest(androidx.health.connect.client.records.WeightRecord::class, timeRangeFilter)
                val weightResponse = healthConnectClient.readRecords(weightRequest)
                weightKg = weightResponse.records.maxByOrNull { it.time }?.weight?.inKilograms ?: 0.0
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HealthConnectManager", "Failed to read weight", e)
            }
        }

        DailyHealthMetrics(
            date = date.toString(),
            steps = totalSteps,
            distanceKm = distanceKm,
            activeMinutes = activeMinutes,
            sleepDurationMinutes = sleepDurationMinutes,
            averageHeartRate = averageHeartRate,
            waterConsumedMl = waterConsumedMl,
            caloriesConsumed = caloriesConsumed,
            caloriesBurned = caloriesBurned,
            mindfulnessMinutes = mindfulnessMinutes,
            weight = weightKg,
            restingHeartRate = restingHeartRate,
            hrvRmssdMs = hrvRmssdMs,
            sleepDeepMinutes = sleepStages.deep,
            sleepRemMinutes = sleepStages.rem,
            sleepLightMinutes = sleepStages.light,
            sleepAwakeMinutes = sleepStages.awake
        )
    }

    private fun stagesOf(session: SleepSessionRecord): SleepStages =
        SleepStages.of(session.stages.map { it.stage to Duration.between(it.startTime, it.endTime).toMinutes() })

    /** Resting heart rate and HRV need their own grants; sleep stages come with sleep. */
    suspend fun hasRecoveryPermissions(): Boolean = try {
        healthConnectClient.permissionController.getGrantedPermissions().containsAll(recoveryPermissions)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * Recovery signals for every night from [first] to [last], one [RecoveryDay] per night with
     * data (the baseline needs weeks of history; the vault only has days written since v11).
     * Health Connect answers a foreground app only.
     */
    suspend fun readRecoveryNights(first: LocalDate, last: LocalDate): List<RecoveryDay> = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val windowStart = first.minusDays(1).atTime(18, 0).atZone(zone).toInstant()
        val windowEnd = last.atTime(18, 0).atZone(zone).toInstant()
        val granted = try {
            healthConnectClient.permissionController.getGrantedPermissions()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }

        val resting = mutableMapOf<LocalDate, Int>()
        if (granted.contains(HealthPermission.getReadPermission(RestingHeartRateRecord::class))) {
            try {
                healthConnectClient.aggregateGroupByPeriod(
                    androidx.health.connect.client.request.AggregateGroupByPeriodRequest(
                        metrics = setOf(RestingHeartRateRecord.BPM_AVG),
                        timeRangeFilter = TimeRangeFilter.between(first.atStartOfDay(), last.plusDays(1).atStartOfDay()),
                        timeRangeSlicer = java.time.Period.ofDays(1)
                    )
                ).forEach { bucket ->
                    bucket.result[RestingHeartRateRecord.BPM_AVG]?.let { resting[bucket.startTime.toLocalDate()] = it.toInt() }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HealthConnectManager", "Failed to read resting heart rate history", e)
            }
        }

        val hrvByNight = mutableMapOf<LocalDate, MutableList<Double>>()
        if (granted.contains(HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class))) {
            try {
                readAllPages(HeartRateVariabilityRmssdRecord::class, TimeRangeFilter.between(windowStart, windowEnd)).forEach { record ->
                    hrvByNight.getOrPut(RecoveryDay.nightOf(record.time, zone)) { mutableListOf() } += record.heartRateVariabilityMillis
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HealthConnectManager", "Failed to read heart rate variability history", e)
            }
        }

        val sleepMinutes = mutableMapOf<LocalDate, Long>()
        val stagesByNight = mutableMapOf<LocalDate, SleepStages>()
        if (granted.contains(HealthPermission.getReadPermission(SleepSessionRecord::class))) {
            try {
                readAllPages(SleepSessionRecord::class, TimeRangeFilter.between(windowStart, windowEnd)).forEach { session ->
                    val night = session.endTime.atZone(zone).toLocalDate()
                    sleepMinutes[night] = (sleepMinutes[night] ?: 0L) + Duration.between(session.startTime, session.endTime).toMinutes()
                    stagesByNight[night] = (stagesByNight[night] ?: SleepStages()) + stagesOf(session)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HealthConnectManager", "Failed to read sleep history", e)
            }
        }

        (resting.keys + hrvByNight.keys + sleepMinutes.keys)
            .filter { it in first..last }
            .sorted()
            .map { night ->
                RecoveryDay(
                    date = night,
                    restingHeartRate = resting[night]?.takeIf { it > 0 },
                    hrvMs = hrvByNight[night]?.average(),
                    sleepMinutes = sleepMinutes[night]?.takeIf { it > 0 },
                    stages = stagesByNight[night]?.takeIf { it.recorded }
                )
            }
    }

    /** Every record in range, following page tokens (capped: a runaway source can't stall the screen). */
    private suspend fun <T : androidx.health.connect.client.records.Record> readAllPages(
        type: kotlin.reflect.KClass<T>,
        range: TimeRangeFilter
    ): List<T> {
        val records = mutableListOf<T>()
        var pageToken: String? = null
        var pages = 0
        do {
            val response = healthConnectClient.readRecords(ReadRecordsRequest(type, range, pageToken = pageToken))
            records += response.records
            pageToken = response.pageToken
            pages++
        } while (pageToken != null && pages < MAX_PAGES)
        return records
    }

    private val recoveryPermissions: Set<String>
        get() = setOf(
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)
        )

    /** What "connected" means: the recovery grants are asked for alongside, never required. */
    @OptIn(ExperimentalMindfulnessSessionApi::class)
    private val corePermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(HydrationRecord::class),
        HealthPermission.getReadPermission(NutritionRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(androidx.health.connect.client.records.ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(MindfulnessSessionRecord::class),
        HealthPermission.getReadPermission(androidx.health.connect.client.records.WeightRecord::class)
    )

    private val permissions: Set<String> get() = corePermissions + recoveryPermissions

    fun getSdkStatus(): Int {
        return HealthConnectClient.getSdkStatus(context)
    }
    
    suspend fun checkPermissions(): Boolean {
        return try {
            val granted = healthConnectClient.permissionController.getGrantedPermissions()
            granted.containsAll(corePermissions)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun readDailyHeartRateHistory(start: Instant, end: Instant): List<HeartRatePoint> = withContext(Dispatchers.IO) {
        try {
            val zone = ZoneId.systemDefault()
            val localStart = java.time.LocalDateTime.ofInstant(start, zone)
            val localEnd = java.time.LocalDateTime.ofInstant(end, zone)

            val response = healthConnectClient.aggregateGroupByPeriod(
                androidx.health.connect.client.request.AggregateGroupByPeriodRequest(
                    metrics = setOf(HeartRateRecord.BPM_AVG),
                    timeRangeFilter = TimeRangeFilter.between(localStart, localEnd),
                    timeRangeSlicer = java.time.Period.ofDays(1)
                )
            )
            response.mapNotNull { result ->
                val avg = result.result[HeartRateRecord.BPM_AVG]
                if (avg != null) {
                    HeartRatePoint(
                        timestamp = result.startTime.atZone(zone).toInstant(),
                        avgBpm = avg.toDouble()
                    )
                } else null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val MAX_PAGES = 20
    }
}
