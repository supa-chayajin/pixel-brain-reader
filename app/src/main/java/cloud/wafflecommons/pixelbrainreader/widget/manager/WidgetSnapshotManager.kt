package cloud.wafflecommons.pixelbrainreader.widget.manager

import android.content.Context
import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.gamification.CharacterClass
import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository
import cloud.wafflecommons.pixelbrainreader.data.health.HealthConnectManager
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChoreEntity
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
import cloud.wafflecommons.pixelbrainreader.data.repository.ChoreRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.domain.lifeos.HabitScheduler
import cloud.wafflecommons.pixelbrainreader.widget.data.SnapshotMood
import cloud.wafflecommons.pixelbrainreader.widget.data.SnapshotPoint
import cloud.wafflecommons.pixelbrainreader.widget.data.WidgetDataSnapshot
import cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetUpdateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the shared `widget_snapshot.json` consumed by the glanceable (non-interactive) widgets.
 *
 * Every read is individually try/caught: one failing data source (e.g. Health Connect not granted)
 * must degrade to a zero/placeholder, never abort the whole snapshot — a half-populated widget is
 * far better than a stale one. Runs entirely on [Dispatchers.IO].
 */
@Singleton
class WidgetSnapshotManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val healthConnectManager: HealthConnectManager,
    private val gamificationRepository: GamificationRepository,
    private val moodRepository: MoodRepository,
    private val widgetUpdateManager: WidgetUpdateManager,
    private val habitRepository: HabitRepository,
    private val choreRepository: ChoreRepository,
    private val dailyDashboardRepository: DailyDashboardRepository
) {
    private val snapshotFileName = "widget_snapshot.json"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun updateSnapshot() = withContext(Dispatchers.IO) {
        try {
            val today = LocalDate.now()
            val startOfDay = today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
            val now = Instant.now()
            val sixHoursAgo = now.minus(6, ChronoUnit.HOURS)

            // ---- 1. Health (one aggregate round-trip, permission-guarded) ----
            val hasPermissions = try { healthConnectManager.checkPermissions() } catch (e: Exception) { false }
            val metrics = if (hasPermissions) {
                try { healthConnectManager.getDailyMetrics(today) } catch (e: Exception) { null }
            } else null

            val stepsVal = metrics?.steps ?: 0L

            // Sleep: Habit repo is the source of truth (manual logging), Health Connect is fallback.
            var sleepMinutes = readSleepFromHabit(today)
            if (sleepMinutes <= 0L) sleepMinutes = metrics?.sleepDurationMinutes ?: 0L

            val heartRates = if (hasPermissions) {
                try {
                    healthConnectManager.readHeartRateHistory(sixHoursAgo, now, java.time.Duration.ofMinutes(15))
                } catch (e: Exception) { emptyList() }
            } else emptyList()

            val stepsStr = if (stepsVal > 1000) String.format("%.1fk", stepsVal / 1000.0) else stepsVal.toString()
            val sleepStr = "${sleepMinutes / 60}h${(sleepMinutes % 60).toString().padStart(2, '0')}"

            // ---- 2. Gamification ----
            val profile = try { gamificationRepository.getStateSnapshot().profile } catch (e: Exception) { null }

            // ---- 3. Mood ----
            val dailyMood = moodRepository.getDailyMood(today).firstOrNull()
            val lastMoodEmoji = dailyMood?.summary?.mainEmoji

            val chartPoints = heartRates.map { SnapshotPoint(it.timestamp.epochSecond, it.avgBpm) }
            val chartMoods = dailyMood?.entries?.mapNotNull { entry ->
                try {
                    val dt = java.time.LocalDateTime.parse("${today}T${entry.time}")
                    val epoch = dt.atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
                    if (epoch > sixHoursAgo.epochSecond) SnapshotMood(epoch, entry.score) else null
                } catch (e: Exception) { null }
            } ?: emptyList()

            // ---- 4. Life-OS progress rings ----
            val (habitsDone, habitsTotal) = countHabits(today)
            val tasks = (try { dailyDashboardRepository.getLiveTasks(today).firstOrNull() } catch (e: Exception) { null }).orEmpty()
            val chores = (try { choreRepository.getAllChoresStream().firstOrNull() } catch (e: Exception) { null }).orEmpty()
            val choresDue = chores.count { !it.archived && isChoreDue(it, today) }

            // ---- 5. Assemble + persist ----
            val snapshot = WidgetDataSnapshot(
                heroClass = profile?.characterClass ?: CharacterClass.PEASANT,
                level = profile?.level ?: 1,
                currentXp = profile?.currentXp?.toInt() ?: 0,
                xpToNext = profile?.xpToNextLevel?.toInt() ?: 100,
                avatarResName = profile?.avatarResName,
                lastMoodEmoji = lastMoodEmoji,
                moodEntryCount = dailyMood?.entries?.size ?: 0,
                steps = stepsStr,
                stepsRaw = stepsVal,
                sleep = sleepStr,
                distanceKm = metrics?.distanceKm ?: 0.0,
                activeMinutes = metrics?.activeMinutes ?: 0L,
                caloriesBurned = (metrics?.caloriesBurned ?: 0.0).toInt(),
                hydrationMl = (metrics?.waterConsumedMl ?: 0.0).toInt(),
                mindfulnessMinutes = metrics?.mindfulnessMinutes ?: 0L,
                avgHeartRate = metrics?.averageHeartRate ?: 0,
                weightKg = metrics?.weight ?: 0.0,
                habitsDone = habitsDone,
                habitsTotal = habitsTotal,
                tasksDone = tasks.count { it.isDone },
                tasksTotal = tasks.size,
                choresDue = choresDue,
                chartHeartRate = chartPoints,
                chartMoods = chartMoods,
                lastUpdatedAt = System.currentTimeMillis()
            )

            context.openFileOutput(snapshotFileName, Context.MODE_PRIVATE).use {
                it.write(json.encodeToString(snapshot).toByteArray())
            }

            // Re-render every widget that reads the snapshot.
            widgetUpdateManager.triggerUpdate()
            Log.d("WidgetSnapshotManager", "Snapshot updated (steps=$stepsVal habits=$habitsDone/$habitsTotal chores=$choresDue).")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("WidgetSnapshotManager", "Failed to update snapshot", e)
        }
    }

    /** Today's sleep (minutes) from the Habit repo ("sleep"/"sommeil" habit, value in hours), or 0. */
    private suspend fun readSleepFromHabit(today: LocalDate): Long {
        return try {
            val logs = habitRepository.getLogsForYear(today.year)
                .filterKeys { it.equals("sleep", true) || it.equals("sommeil", true) }
                .values.flatten()
            val todayLog = logs.find { it.date == today.toString() && it.value > 0 }
            if (todayLog != null) (todayLog.value * 60).toLong() else 0L
        } catch (e: CancellationException) { throw e } catch (e: Exception) { 0L }
    }

    /** Returns (done, scheduledTotal) for today's habits, applying the shared [HabitScheduler]. */
    private suspend fun countHabits(today: LocalDate): Pair<Int, Int> {
        return try {
            val configs = habitRepository.getHabitConfigs().filter { !it.archived }
            val logsByHabit = habitRepository.getLogsForYear(today.year)
            // Include last year so an INTERVAL habit last completed in December isn't wrongly "due".
            val prevYearLogs = habitRepository.getLogsForYear(today.year - 1)
            val todayIso = today.toString()
            var done = 0
            var total = 0
            for (c in configs) {
                val logs = logsByHabit[c.id].orEmpty()
                val lastCompleted = (logs + prevYearLogs[c.id].orEmpty()).asSequence()
                    .filter { it.status == HabitStatus.COMPLETED }
                    .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
                    .maxOrNull()
                if (!HabitScheduler.isScheduledOn(c, today, lastCompleted)) continue
                total++
                val todayLog = logs.find { it.date == todayIso }
                if (todayLog != null && habitRepository.calculateCompletion(todayLog.value, c.targetValue, c.type)) done++
            }
            done to total
        } catch (e: CancellationException) { throw e } catch (e: Exception) { 0 to 0 }
    }

    private fun isChoreDue(chore: ChoreEntity, today: LocalDate): Boolean {
        val last = runCatching { LocalDate.parse(chore.lastDoneDate) }.getOrNull() ?: return true
        // Coerce frequency to ≥1 so a chore with frequencyDays==0 still counts as done for today.
        return !last.plusDays(chore.frequencyDays.coerceAtLeast(1).toLong()).isAfter(today)
    }
}
