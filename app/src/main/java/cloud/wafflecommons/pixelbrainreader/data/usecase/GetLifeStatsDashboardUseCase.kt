package cloud.wafflecommons.pixelbrainreader.data.usecase

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.health.HealthConnectManager
import cloud.wafflecommons.pixelbrainreader.data.model.CorrelationPoint
import cloud.wafflecommons.pixelbrainreader.data.model.DailyMetric
import cloud.wafflecommons.pixelbrainreader.data.model.IntradayPoint
import cloud.wafflecommons.pixelbrainreader.data.model.StatsDashboardState
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.Instant
import javax.inject.Inject

class GetLifeStatsDashboardUseCase @Inject constructor(
    private val healthConnectManager: HealthConnectManager,
    private val habitRepository: HabitRepository,
    private val moodRepository: MoodRepository
) {
    suspend operator fun invoke(): StatsDashboardState = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        val startOfWeek = today.minusDays(6)
        val zone = ZoneId.systemDefault()
        
        val startInstant = startOfWeek.atStartOfDay(zone).toInstant()
        val endInstant = Instant.now() // Up to now

        // 1. Fetch Data in Parallel (conceptually, or just sequential for simplicity in coroutine scope)
        // We'll do sequential for readability/stability as efficiency gain is minimal here
        
        // A. Configs & Logs
        val habitConfigs = habitRepository.getHabitConfigs()
        val logs = habitRepository.getLogsForYear(today.year)
        
        val stepsHabit = habitConfigs.find { it.autoSource == "health_connect_steps" }
        val sleepHabit = habitConfigs.find { it.autoSource == "health_connect_sleep" } // "sleep_duration" in some contexts? Standardize on "health_connect_sleep"
        
        // B. Health Connect Fallback Data
        val hcDailySteps = healthConnectManager.readDailySteps(startInstant, endInstant)
        val hcDailySleep = healthConnectManager.readDailySleep(startInstant, endInstant)
        
        // C. Sleep & Steps History Calculation
        val sleepHistory = mutableListOf<DailyMetric<Double>>()
        val stepHistory = mutableListOf<DailyMetric<Long>>()
        
        var currentDate = startOfWeek
        while (!currentDate.isAfter(today)) {
            // Steps
            val stepLog = logs[stepsHabit?.id]?.find { it.date == currentDate.toString() }
            val stepVal = stepLog?.value?.toLong() ?: hcDailySteps[currentDate] ?: 0L
            val stepGoal = stepsHabit?.targetValue?.toLong() ?: 6000L
            stepHistory.add(DailyMetric(currentDate, stepVal, stepVal >= stepGoal))
            
            // Sleep
            val sleepLog = logs[sleepHabit?.id]?.find { it.date == currentDate.toString() }
            val sleepValHours = if (sleepLog != null) {
                sleepLog.value // Assumed hours in habit log
            } else {
                val dur = hcDailySleep[currentDate] ?: Duration.ZERO
                dur.toMinutes() / 60.0
            }
            val sleepGoal = sleepHabit?.targetValue ?: 7.0
            sleepHistory.add(DailyMetric(currentDate, sleepValHours, sleepValHours >= sleepGoal))
            
            currentDate = currentDate.plusDays(1)
        }
        
        // D. Moods
        val allMoods = habitRepository.getLogsForYearFlow(today.year) // Wait, Moods are in MoodRepository
        val moodEntities = moodRepository.getMoodFlow().first()
        val weekMoods = moodEntities.filter { entity ->
            val d = LocalDate.parse(entity.date)
            !d.isBefore(startOfWeek) && !d.isAfter(today) 
        }
        
        // E. Weekly Correlation (HR vs Mood)
        // Weekly HR (Daily Avg)
        // We use readDailyHeartRateHistory to align buckets with local calendar days
        val weeklyHrPoints = healthConnectManager.readDailyHeartRateHistory(startInstant, endInstant)
        // Map date -> bpm
        val weeklyHrMap = weeklyHrPoints.associate { point ->
            val d = point.timestamp.atZone(zone).toLocalDate()
            d to point.avgBpm
        }
        
        val weeklyCorrelation = mutableListOf<CorrelationPoint>()
        currentDate = startOfWeek
        while (!currentDate.isAfter(today)) {
             val dateStr = currentDate.toString()
             val dailyMoods = weekMoods.filter { it.date == dateStr }
             val avgMood = if (dailyMoods.isNotEmpty()) dailyMoods.map { it.score }.average() else 0.0
             
             // If no HR data, maybe we shouldn't plot? Or plot 0? 
             // Providing 0 avgBpm where valid might confuse graph scaling. 
             // We'll let UI handle it, or put 0.0.
             val bpm = weeklyHrMap[currentDate] ?: 0.0
             
             weeklyCorrelation.add(CorrelationPoint(currentDate, bpm, avgMood))
             currentDate = currentDate.plusDays(1)
        }

        // F. Today's Biometrics
        // Intraday HR (30 min buckets)
        val todayStart = today.atStartOfDay(zone).toInstant()
        val intradayHr = healthConnectManager.readHeartRateHistory(todayStart, endInstant, Duration.ofMinutes(30))
        
        val todayMoods = weekMoods.filter { it.date == today.toString() }
        
        val todayPoints = mutableListOf<IntradayPoint>()
        
        // Add HR points
        intradayHr.forEach { hr ->
            todayPoints.add(IntradayPoint(hr.timestamp, bpm = hr.avgBpm, moodScore = null))
        }
        
        // Add Mood points
        todayMoods.forEach { mood ->
             // Mood timestamp is millis in DB, convert to Instant
             val moodInstant = Instant.ofEpochMilli(mood.timestamp)
             todayPoints.add(IntradayPoint(moodInstant, bpm = null, moodScore = mood.score.toDouble()))
        }
        
        // Sort by time
        todayPoints.sortBy { it.timestamp }
        
        StatsDashboardState(
            sleepHistory = sleepHistory,
            stepHistory = stepHistory,
            weeklyCorrelation = weeklyCorrelation,
            todayCorrelation = todayPoints
        )
    }
}
