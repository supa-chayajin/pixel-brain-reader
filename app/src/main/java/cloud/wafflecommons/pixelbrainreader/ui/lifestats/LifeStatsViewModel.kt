package cloud.wafflecommons.pixelbrainreader.ui.lifestats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.model.HabitType
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flatMapLatest
import androidx.compose.runtime.Immutable
import cloud.wafflecommons.pixelbrainreader.data.repository.TaskRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.ChoreRepository
import cloud.wafflecommons.pixelbrainreader.domain.homeos.CalculateChoreEntropyUseCase
import cloud.wafflecommons.pixelbrainreader.ui.homeos.StatusColor
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
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
class LifeStatsViewModel @Inject constructor(
    private val gamificationRepository: GamificationRepository,
    moodRepository: MoodRepository,
    habitRepository: HabitRepository,
    choreRepository: ChoreRepository,
    private val taskRepository: TaskRepository,
    private val calculateChoreEntropyUseCase: CalculateChoreEntropyUseCase,
    private val gamificationPreferences: cloud.wafflecommons.pixelbrainreader.data.local.preferences.GamificationPreferences,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val syncOrchestrator: cloud.wafflecommons.pixelbrainreader.data.sync.SyncOrchestrator,
    private val healthMetricsRepository: cloud.wafflecommons.pixelbrainreader.data.repository.HealthMetricsRepository
) : ViewModel() {

    // Dummy flow to re-trigger when the date changes.
    private val todayFlow = flow { emit(LocalDate.now()) }

    private val healthMetricsFlow = syncOrchestrator.syncState
        .flatMapLatest { healthMetricsRepository.getMetricsHistoryFlow(LocalDate.now(), 7) }

    private fun isHabitLogComplete(
        habit: cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig,
        log: cloud.wafflecommons.pixelbrainreader.data.model.HabitLogEntry?
    ): Boolean {
        if (log == null) return false
        return if (habit.type == HabitType.MEASURABLE) log.value >= habit.targetValue
        else log.status == HabitStatus.COMPLETED
    }

    val uiState: StateFlow<LifeStatsUiState> = combine(
        moodRepository.getMoodFlow(),
        habitRepository.getLogsForYearFlow(LocalDate.now().year),
        choreRepository.getAllChoresStream(),
        combine(todayFlow, habitRepository.getHabitConfigsFlow()) { today, configs -> today to configs },
        healthMetricsFlow
    ) { moods, habitLogsMap, chores, todayAndConfigs, healthMetricsList ->
        val today = todayAndConfigs.first
        val configs = todayAndConfigs.second

        // --- Mental Health (Mood) ---
        val mood7Days = moods.filter { it.date >= today.minusDays(7).toString() }
        val mood30Days = moods.filter { it.date >= today.minusDays(30).toString() }
        val avgMood7Days = if (mood7Days.isNotEmpty()) mood7Days.map { it.score }.average().toFloat() else 0f
        val avgMood30Days = if (mood30Days.isNotEmpty()) mood30Days.map { it.score }.average().toFloat() else 0f

        // --- Physical Health (7-day metrics) ---
        var totalHrSum = 0
        var hrDaysCount = 0

        val moodHistoryLine = mutableListOf<LifeStatsMoodPoint>()
        val distanceHistory = mutableListOf<Float>()
        val activeMinHistory = mutableListOf<Float>()
        val sleepHistory = mutableListOf<Float>()

        var todayDistanceKm = 0.0
        var todayActiveMinutes = 0L
        var todaySleepMinutes = 0L
        var todaySteps = 0L
        var todayCaloriesBurned = 0

        (6 downTo 0).forEach { offset ->
            val d = today.minusDays(offset.toLong())
            var dayAvgBpm = 0
            val dhm = healthMetricsList.find { it.date == d.toString() }
            if (dhm != null) {
                dayAvgBpm = dhm.averageHeartRate
                if (dayAvgBpm > 0) { totalHrSum += dayAvgBpm; hrDaysCount++ }
                distanceHistory.add(dhm.distanceKm.toFloat())
                activeMinHistory.add(dhm.activeMinutes.toFloat())
                sleepHistory.add(dhm.sleepDurationMinutes.toFloat())
                if (offset == 0) {
                    todayDistanceKm = dhm.distanceKm
                    todayActiveMinutes = dhm.activeMinutes
                    todaySleepMinutes = dhm.sleepDurationMinutes
                    todaySteps = dhm.steps
                    todayCaloriesBurned = dhm.caloriesBurned.toInt()
                }
            } else {
                distanceHistory.add(0f); activeMinHistory.add(0f); sleepHistory.add(0f)
            }

            val dayMoods = moods.filter { it.date == d.toString() }
            val avg = if (dayMoods.isNotEmpty()) dayMoods.map { it.score }.average() else 0.0
            val emoji = when {
                avg == 0.0 -> "∅"
                avg < 1.8 -> "😫"
                avg.isNaN() -> "😐"
                avg < 2.6 -> "😞"
                avg < 3.4 -> "😐"
                avg < 4.2 -> "🙂"
                else -> "🤩"
            }
            moodHistoryLine.add(LifeStatsMoodPoint(d, avg.toFloat(), emoji, dayAvgBpm))
        }
        val avgHeartRate = if (hrDaysCount > 0) totalHrSum / hrDaysCount else 0

        // --- Productivity (Habits): today's completion + best current streak ---
        val dayMap = mapOf(
            java.time.DayOfWeek.MONDAY to "MON", java.time.DayOfWeek.TUESDAY to "TUE",
            java.time.DayOfWeek.WEDNESDAY to "WED", java.time.DayOfWeek.THURSDAY to "THU",
            java.time.DayOfWeek.FRIDAY to "FRI", java.time.DayOfWeek.SATURDAY to "SAT",
            java.time.DayOfWeek.SUNDAY to "SUN"
        )
        val todayKey = dayMap[today.dayOfWeek] ?: "MON"
        val todayString = today.toString()

        val activeConfigs = configs.filter { !it.archived }
        var totalHabitsScheduledToday = 0
        var completedHabitsToday = 0
        var bestHabitStreak = 0

        activeConfigs.forEach { habit ->
            val cleanFreq = habit.frequency.map { it.trim().uppercase() }
            val logs = habitLogsMap[habit.id] ?: emptyList()

            val isScheduled = cleanFreq.isEmpty() || cleanFreq.contains(todayKey)
            val completedToday = isHabitLogComplete(habit, logs.find { it.date == todayString })
            if (isScheduled) {
                totalHabitsScheduledToday++
                if (completedToday) completedHabitsToday++
            }

            // Current streak: skip unscheduled days, break on the first missed scheduled day.
            var streak = 0
            var cd = if (completedToday) today else today.minusDays(1)
            for (i in 0..365) {
                val key = dayMap[cd.dayOfWeek] ?: "MON"
                val sched = cleanFreq.isEmpty() || cleanFreq.contains(key)
                val lg = logs.find { it.date == cd.toString() }
                if (isHabitLogComplete(habit, lg)) { streak++; cd = cd.minusDays(1) }
                else if (!sched) { cd = cd.minusDays(1) }
                else break
            }
            bestHabitStreak = maxOf(bestHabitStreak, streak)
        }
        val habitCompletionRate = if (totalHabitsScheduledToday == 0) 1f
        else completedHabitsToday.toFloat() / totalHabitsScheduledToday.toFloat()

        // --- Home OS (Chores) ---
        val choreModels = calculateChoreEntropyUseCase(chores)
        val criticalChoresCount = choreModels.count { it.statusColor == StatusColor.RED }
        val cleanChoresCount = choreModels.count { it.statusColor == StatusColor.GREEN }
        val warningChoresCount = choreModels.count { it.statusColor == StatusColor.YELLOW }

        LifeStatsUiState(
            moodHistory = moodHistoryLine,
            avgMood7Days = avgMood7Days,
            avgMood30Days = avgMood30Days,
            avgHeartRate = avgHeartRate,
            caloriesBurned = todayCaloriesBurned,
            activeMinutes7Days = activeMinHistory,
            distance7Days = distanceHistory,
            sleepDuration7Days = sleepHistory,
            todayDistanceKm = todayDistanceKm,
            todayActiveMinutes = todayActiveMinutes,
            todaySleepMinutes = todaySleepMinutes,
            todaySteps = todaySteps,
            habitCompletionRate = habitCompletionRate,
            taskCompletionRate = 0f, // resolved in finalUiState
            completedHabitsToday = completedHabitsToday,
            scheduledHabitsToday = totalHabitsScheduledToday,
            totalActiveHabits = activeConfigs.size,
            bestHabitStreak = bestHabitStreak,
            criticalChoresCount = criticalChoresCount,
            cleanChoresCount = cleanChoresCount,
            warningChoresCount = warningChoresCount,
            totalChoresCount = choreModels.size,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LifeStatsUiState(isLoading = true))

    // Task completion rate (7 days) — suspend snapshot, kept out of the main combine.
    private val taskRatiosFlow = flow {
        var totalTasks = 0
        var completedTasks = 0
        (0..6).forEach { offset ->
            val date = LocalDate.now().minusDays(offset.toLong())
            val tasksSnapshot = taskRepository.getTasks(date)
            totalTasks += tasksSnapshot.size
            completedTasks += tasksSnapshot.count { it.isDone }
        }
        emit(if (totalTasks > 0) completedTasks.toFloat() / totalTasks.toFloat() else 0f)
    }

    val finalUiState: StateFlow<LifeStatsUiState> = combine(
        uiState,
        taskRatiosFlow,
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

    val isSyncing: StateFlow<cloud.wafflecommons.pixelbrainreader.data.sync.SyncState> = syncOrchestrator.syncState

    fun triggerSync() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            syncOrchestrator.executeFullSyncCycle()
        }
    }

    val sleepDurationState: StateFlow<Long> = finalUiState.map { it.todaySleepMinutes }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val globalCompletionState: StateFlow<Float> = finalUiState.map { state ->
        val totalChores = state.criticalChoresCount + state.cleanChoresCount
        val choreRate = if (totalChores > 0) state.cleanChoresCount.toFloat() / totalChores.toFloat() else 0f
        (state.taskCompletionRate + state.habitCompletionRate + choreRate) / 3f
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)
}
