package cloud.wafflecommons.pixelbrainreader.widget.manager

import android.content.Context
import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository
import cloud.wafflecommons.pixelbrainreader.data.health.HealthConnectManager
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.widget.data.SnapshotMood
import cloud.wafflecommons.pixelbrainreader.widget.data.SnapshotPoint
import cloud.wafflecommons.pixelbrainreader.widget.data.WidgetDataSnapshot
import cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetUpdateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetSnapshotManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val healthConnectManager: HealthConnectManager,
    private val gamificationRepository: GamificationRepository,
    private val moodRepository: MoodRepository,
    private val widgetUpdateManager: WidgetUpdateManager,
    private val habitRepository: cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
) {
    private val snapshotFileName = "widget_snapshot.json"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun updateSnapshot() = withContext(Dispatchers.IO) {
        try {
            val today = LocalDate.now()
            val startOfDay = today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
            val now = Instant.now()
            val sixHoursAgo = now.minus(6, ChronoUnit.HOURS)

            // 1. Health Data (Safe Try-Catch)
            val hasPermissions = try { healthConnectManager.checkPermissions() } catch(e: Exception) { false }
            
            val stepsVal = if (hasPermissions) {
                try { healthConnectManager.readSteps(startOfDay, now) } catch (e: Exception) { 0L }
            } else 0L

            // Sleep Logic: Habit Repo (Source of Truth) -> Health Connect (Fallback)
            var sleepDuration = java.time.Duration.ZERO
            
            // Try Habit Repository First
            try {
                // Fetch valid logs for today (quick scan of current year logs)
                val currentYearLogs = habitRepository.getLogsForYear(today.year)
                val sleepLogs = currentYearLogs.filterKeys { id -> 
                    id.equals("sleep", ignoreCase = true) || id.equals("sommeil", ignoreCase = true) 
                }.values.flatten()
                
                val todayLog = sleepLogs.find { it.date == today.toString() }
                
                if (todayLog != null && todayLog.value > 0) {
                     // Habit value is likely in Hours (Double). e.g., 7.5
                     val minutes = (todayLog.value * 60).toLong()
                     sleepDuration = java.time.Duration.ofMinutes(minutes)
                     Log.d("WidgetSnapshotManager", "Using Sleep Data from Habit Repo: ${todayLog.value}h")
                }
            } catch (e: Exception) {
                Log.w("WidgetSnapshotManager", "Failed to read habit repo for sleep", e)
            }

            // Fallback to Health Connect if Habit is empty/zero
            if (sleepDuration.isZero && hasPermissions) {
                 try { 
                     // Sleep is often yesterday night to today morning.
                     // HealthConnectManager.readSleepDuration usually handles window logic if implemented nicely,
                     // otherwise looking at SESSIONS from yesterday 6PM to now is safer.
                     // Current implementation of readSleepDuration uses strictly start-end window.
                     // Let's widen the start for sleep to be yesterday 18:00
                     val yesterdayEvening = startOfDay.minus(6, ChronoUnit.HOURS) // 18:00 prev day
                     sleepDuration = healthConnectManager.readSleepDuration(yesterdayEvening, now)
                 } catch (e: Exception) { }
            }

            val heartRates = if (hasPermissions) {
                try { healthConnectManager.readHeartRateHistory(sixHoursAgo, now, java.time.Duration.ofMinutes(15)) } catch(e: Exception) { emptyList() }
            } else emptyList()

            // Format Stats Strings
            val stepsStr = if (stepsVal > 1000) String.format("%.1fk", stepsVal / 1000.0) else stepsVal.toString()
            val sleepHours = sleepDuration.toHours()
            val sleepMinutes = (sleepDuration.toMinutes() % 60)
            val sleepStr = "${sleepHours}h${sleepMinutes.toString().padStart(2, '0')}"

            // 2. Gamification
            val gameState = try { gamificationRepository.getStateSnapshot() } catch (e: Exception) { null }
            val profile = gameState?.profile

            // 3. Moods
            val moodFlow = moodRepository.getDailyMood(today)
            val dailyMood = moodFlow.firstOrNull()
            val lastMoodEmoji = dailyMood?.summary?.mainEmoji

            // Prepare Chart Data
            val chartPoints = heartRates.map { SnapshotPoint(it.timestamp.epochSecond, it.avgBpm) }
            
            val todayStr = today.toString()
            val chartMoods = dailyMood?.entries?.mapNotNull { entry ->
                try {
                    val dt = java.time.LocalDateTime.parse("${todayStr}T${entry.time}")
                    val dtEpoch = dt.atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
                    
                    if (dtEpoch > sixHoursAgo.epochSecond) {
                        SnapshotMood(dtEpoch, entry.score)
                    } else null
                } catch (e: Exception) { null }
            } ?: emptyList()

            // 4. Construct Snapshot
            val snapshot = WidgetDataSnapshot(
                heroClass = profile?.characterClass ?: cloud.wafflecommons.pixelbrainreader.data.gamification.CharacterClass.PEASANT,
                level = profile?.level ?: 1,
                currentXp = profile?.currentXp?.toInt() ?: 0,
                xpToNext = profile?.xpToNextLevel?.toInt() ?: 100,
                steps = stepsStr,
                sleep = sleepStr,
                lastMoodEmoji = lastMoodEmoji,
                chartHeartRate = chartPoints,
                chartMoods = chartMoods,
                avatarResName = profile?.avatarResName,
                lastUpdatedAt = System.currentTimeMillis()
            )

            // 5. Write to File
            val jsonString = json.encodeToString(snapshot)
            context.openFileOutput(snapshotFileName, Context.MODE_PRIVATE).use {
                it.write(jsonString.toByteArray())
            }

            // 6. Notify Widget
            widgetUpdateManager.triggerUpdate()
            Log.d("WidgetSnapshotManager", "Snapshot updated successfully.")

        } catch (e: Exception) {
            Log.e("WidgetSnapshotManager", "Failed to update snapshot", e)
        }
    }
}
