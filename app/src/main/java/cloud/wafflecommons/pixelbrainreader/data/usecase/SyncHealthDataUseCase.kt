package cloud.wafflecommons.pixelbrainreader.data.usecase

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.health.HealthConnectManager
import cloud.wafflecommons.pixelbrainreader.data.model.HabitLogEntry
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

class SyncHealthDataUseCase @Inject constructor(
    private val healthConnectManager: HealthConnectManager,
    private val habitRepository: HabitRepository
) {

    suspend operator fun invoke(date: LocalDate = LocalDate.now()) = withContext(Dispatchers.IO) {
        if (!healthConnectManager.checkPermissions()) {
            Log.w("SyncHealth", "Permissions not granted for Health Connect sync")
            return@withContext
        }

        val habits = habitRepository.getHabitConfigs()
        val autoHabits = habits.filter { !it.autoSource.isNullOrBlank() }

        if (autoHabits.isEmpty()) return@withContext

        autoHabits.forEach { habit ->
            try {
                when (habit.autoSource) {
                    "health_connect_steps" -> syncSteps(habit.id, date)
                    "health_connect_sleep" -> syncSleep(habit.id, date)
                    else -> Log.d("SyncHealth", "Unknown autoSource: ${habit.autoSource}")
                }
            } catch (e: Exception) {
                Log.e("SyncHealth", "Failed to sync habit ${habit.id}", e)
            }
        }
    }

    private suspend fun syncSteps(habitId: String, date: LocalDate) {
        // Steps: From 00:00 to Now (or end of day if past)
        val startOfDay = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
        
        // If syncing today, take "now" to capture latest. If syncing past day, take "end of day"
        val now = java.time.Instant.now()
        val endTimestamp = if (date == LocalDate.now()) {
            now
        } else {
            date.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant()
        }

        val steps = healthConnectManager.readSteps(startOfDay, endTimestamp)
        
        Log.d("HealthSync", "Synced Steps for $date: $steps")

        // CRITICAL: Force update even if 0 (though usually we want >0). 
        // Safety first: > 0.
        if (steps > 0) {
           habitRepository.updateHabitValue(date, habitId, steps.toDouble())
        }
    }

    private suspend fun syncSleep(habitId: String, date: LocalDate) {
        // Sleep: "Night before" logic. 
        // Window: Yesterday 18:00 to Today 12:00
        val yesterday = date.minusDays(1)
        val startWindow = yesterday.atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant()
        val endWindow = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()

        val duration = healthConnectManager.readSleepDuration(startWindow, endWindow)
        val hours = duration.toMinutes() / 60.0
        
        Log.d("HealthSync", "Synced Sleep for $date: $hours hours")

        if (hours > 0) {
            habitRepository.updateHabitValue(date, habitId, hours)
        }
    }
}
