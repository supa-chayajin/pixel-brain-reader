package cloud.wafflecommons.pixelbrainreader.ui.lifestats

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository
import cloud.wafflecommons.pixelbrainreader.data.health.DailyHealthMetrics
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChoreEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.MoodEntity
import cloud.wafflecommons.pixelbrainreader.data.local.preferences.GamificationPreferences
import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.model.HabitLogEntry
import cloud.wafflecommons.pixelbrainreader.data.repository.ChoreRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.HealthMetricsRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.TaskRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncOrchestrator
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncState
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.domain.homeos.CalculateChoreEntropyUseCase
import cloud.wafflecommons.pixelbrainreader.domain.lifeos.HabitStatsCalculator
import cloud.wafflecommons.pixelbrainreader.ui.homeos.StatusColor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

@Immutable
data class LifeStatsMoodPoint(
    val date: LocalDate,
    val score: Float,
    val emoji: String,
    val avgBpm: Int = 0
)

@Immutable
data class LifeStatsUiState(
    val moodHistory: List<LifeStatsMoodPoint> = emptyList(),
    val avgMood7Days: Float = 0f,
    val avgMood30Days: Float = 0f,
    val avgHeartRate: Int = 0,
    val caloriesBurned: Int = 0,

    val activeMinutes7Days: List<Float> = emptyList(),
    val distance7Days: List<Float> = emptyList(),
    val sleepDuration7Days: List<Float> = emptyList(),
    val todayDistanceKm: Double = 0.0,
    val todayActiveMinutes: Long = 0L,
    val todaySleepMinutes: Long = 0L,
    val todaySteps: Long = 0L,
    val stepGoal: Int = 10000,
    val sleepGoalMinutes: Int = 300,

    val habitCompletionRate: Float = 0f,
    val taskCompletionRate: Float = 0f,
    val completedHabitsToday: Int = 0,
    val scheduledHabitsToday: Int = 0,
    val totalActiveHabits: Int = 0,
    val bestHabitStreak: Int = 0,

    val criticalChoresCount: Int = 0,
    val cleanChoresCount: Int = 0,
    val warningChoresCount: Int = 0,
    val totalChoresCount: Int = 0,

    // Gamification (RPG)
    val level: Int = 1,
    val currentXp: Int = 0,
    val xpToNextLevel: Int = 100,
    val characterClass: String = "Peasant",

    val isLoading: Boolean = true
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class LifeStatsViewModel @Inject constructor(
    gamificationRepository: GamificationRepository,
    moodRepository: MoodRepository,
    habitRepository: HabitRepository,
    choreRepository: ChoreRepository,
    taskRepository: TaskRepository,
    private val calculateChoreEntropyUseCase: CalculateChoreEntropyUseCase,
    gamificationPreferences: GamificationPreferences,
    private val syncOrchestrator: SyncOrchestrator,
    healthMetricsRepository: HealthMetricsRepository,
    appClock: AppClock
) : ViewModel() {

    private class Inputs(
        val today: LocalDate,
        val moods: List<MoodEntity>,
        val logsByHabit: Map<String, List<HabitLogEntry>>,
        val configs: List<HabitConfig>,
        val chores: List<ChoreEntity>,
        val health: List<DailyHealthMetrics>
    )

    /**
     * Keyed on the REAL current day ([AppClock.today]) — the dates used to be captured once at
     * ViewModel creation — and computed off the main thread: the previous version filtered the whole
     * mood table nine times and walked up to 366 days per habit on every emission, on Main. Health
     * metrics are re-read whenever they are rewritten ([HealthMetricsRepository.changes]).
     */
    val uiState: StateFlow<LifeStatsUiState> = appClock.today
        .flatMapLatest { today ->
            combine(
                moodRepository.getMoodsBetween(today.minusDays(30), today),
                // Two years of logs so streaks continue across January 1st.
                habitRepository.getLogsBetweenFlow(LocalDate.of(today.year - 1, 1, 1), LocalDate.of(today.year, 12, 31)),
                habitRepository.getHabitConfigsFlow(),
                choreRepository.getAllChoresStream(),
                healthMetricsRepository.changes.flatMapLatest { healthMetricsRepository.getMetricsHistoryFlow(today, 7) }
            ) { moods, logsByHabit, configs, chores, health ->
                Inputs(today, moods, logsByHabit, configs, chores, health)
            }
        }
        .map { buildState(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LifeStatsUiState(isLoading = true))

    // Task completion over the last 7 days — reactive now; it used to be a one-shot snapshot.
    private val taskCompletionRate: Flow<Float> = appClock.today
        .flatMapLatest { today -> taskRepository.getTasksInRangeFlow(today.minusDays(6), today) }
        .map { tasks -> if (tasks.isEmpty()) 0f else tasks.count { it.isDone }.toFloat() / tasks.size }

    val finalUiState: StateFlow<LifeStatsUiState> = combine(
        uiState,
        taskCompletionRate,
        gamificationRepository.gamificationState,
        gamificationPreferences.stepTargetFlow,
        gamificationPreferences.sleepMinMinutesFlow
    ) { state, taskRatio, gami, stepGoal, sleepGoal ->
        state.copy(
            taskCompletionRate = taskRatio,
            level = gami.profile.level,
            currentXp = gami.profile.currentXp.toInt(),
            xpToNextLevel = gami.profile.xpToNextLevel.toInt().coerceAtLeast(1),
            characterClass = gami.profile.characterClass.name.lowercase()
                .replaceFirstChar { it.uppercase() },
            stepGoal = stepGoal,
            sleepGoalMinutes = sleepGoal
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LifeStatsUiState(isLoading = true))

    val isSyncing: StateFlow<SyncState> = syncOrchestrator.syncState

    /** Pull-to-refresh: a full, user-initiated sync (bypasses the automatic cooldown). */
    fun triggerSync() {
        syncOrchestrator.requestSync(force = true)
    }

    val sleepDurationState: StateFlow<Long> = finalUiState.map { it.todaySleepMinutes }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val globalCompletionState: StateFlow<Float> = finalUiState.map { state ->
        val totalChores = state.criticalChoresCount + state.cleanChoresCount
        val choreRate = if (totalChores > 0) state.cleanChoresCount.toFloat() / totalChores.toFloat() else 0f
        (state.taskCompletionRate + state.habitCompletionRate + choreRate) / 3f
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    private fun buildState(inputs: Inputs): LifeStatsUiState {
        val today = inputs.today

        // --- Mental Health (Mood) ---
        val weekStart = today.minusDays(7).toString()
        val avgMood7Days = inputs.moods.filter { it.date >= weekStart }.averageScore()
        val avgMood30Days = inputs.moods.averageScore()
        val moodsByDate = inputs.moods.groupBy { it.date }

        // --- Physical Health (7-day metrics) ---
        val healthByDate = inputs.health.associateBy { it.date }
        var totalHrSum = 0
        var hrDaysCount = 0
        val moodHistoryLine = ArrayList<LifeStatsMoodPoint>(7)
        val distanceHistory = ArrayList<Float>(7)
        val activeMinHistory = ArrayList<Float>(7)
        val sleepHistory = ArrayList<Float>(7)
        var todayMetrics: DailyHealthMetrics? = null

        (6 downTo 0).forEach { offset ->
            val day = today.minusDays(offset.toLong())
            val key = day.toString()
            val dhm = healthByDate[key]
            val dayAvgBpm = dhm?.averageHeartRate ?: 0
            if (dayAvgBpm > 0) {
                totalHrSum += dayAvgBpm
                hrDaysCount++
            }
            distanceHistory.add(dhm?.distanceKm?.toFloat() ?: 0f)
            activeMinHistory.add(dhm?.activeMinutes?.toFloat() ?: 0f)
            sleepHistory.add(dhm?.sleepDurationMinutes?.toFloat() ?: 0f)
            if (offset == 0) todayMetrics = dhm

            val dayMoods = moodsByDate[key].orEmpty()
            val avg = if (dayMoods.isNotEmpty()) dayMoods.map { it.score }.average() else 0.0
            moodHistoryLine.add(LifeStatsMoodPoint(day, avg.toFloat(), moodEmoji(avg), dayAvgBpm))
        }

        // --- Productivity (Habits): today's completion + best current streak ---
        val activeConfigs = inputs.configs.filter { !it.archived }
        var scheduledToday = 0
        var completedToday = 0
        var bestHabitStreak = 0
        activeConfigs.forEach { habit ->
            val stats = HabitStatsCalculator.compute(habit, inputs.logsByHabit[habit.id].orEmpty(), today)
            if (stats.isScheduledToday) {
                scheduledToday++
                if (stats.isCompletedToday) completedToday++
            }
            bestHabitStreak = maxOf(bestHabitStreak, stats.currentStreak)
        }
        val habitCompletionRate = if (scheduledToday == 0) 1f else completedToday.toFloat() / scheduledToday

        // --- Home OS (Chores) ---
        val choreModels = calculateChoreEntropyUseCase(inputs.chores)

        return LifeStatsUiState(
            moodHistory = moodHistoryLine,
            avgMood7Days = avgMood7Days,
            avgMood30Days = avgMood30Days,
            avgHeartRate = if (hrDaysCount > 0) totalHrSum / hrDaysCount else 0,
            caloriesBurned = todayMetrics?.caloriesBurned?.toInt() ?: 0,
            activeMinutes7Days = activeMinHistory,
            distance7Days = distanceHistory,
            sleepDuration7Days = sleepHistory,
            todayDistanceKm = todayMetrics?.distanceKm ?: 0.0,
            todayActiveMinutes = todayMetrics?.activeMinutes ?: 0L,
            todaySleepMinutes = todayMetrics?.sleepDurationMinutes ?: 0L,
            todaySteps = todayMetrics?.steps ?: 0L,
            habitCompletionRate = habitCompletionRate,
            taskCompletionRate = 0f, // resolved in finalUiState
            completedHabitsToday = completedToday,
            scheduledHabitsToday = scheduledToday,
            totalActiveHabits = activeConfigs.size,
            bestHabitStreak = bestHabitStreak,
            criticalChoresCount = choreModels.count { it.statusColor == StatusColor.RED },
            cleanChoresCount = choreModels.count { it.statusColor == StatusColor.GREEN },
            warningChoresCount = choreModels.count { it.statusColor == StatusColor.YELLOW },
            totalChoresCount = choreModels.size,
            isLoading = false
        )
    }

    private fun List<MoodEntity>.averageScore(): Float =
        if (isEmpty()) 0f else map { it.score }.average().toFloat()

    private fun moodEmoji(avg: Double): String = when {
        avg == 0.0 -> "∅"
        avg < 1.8 -> "😫"
        avg.isNaN() -> "😐"
        avg < 2.6 -> "😞"
        avg < 3.4 -> "😐"
        avg < 4.2 -> "🙂"
        else -> "🤩"
    }
}
