package cloud.wafflecommons.pixelbrainreader.ui.lifestats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.model.RpgAttribute
import com.patrykandpatrick.vico.core.entry.FloatEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.runtime.Immutable
import cloud.wafflecommons.pixelbrainreader.data.repository.TaskRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.ChoreRepository
import cloud.wafflecommons.pixelbrainreader.domain.homeos.CalculateChoreEntropyUseCase
import cloud.wafflecommons.pixelbrainreader.ui.homeos.StatusColor
import cloud.wafflecommons.pixelbrainreader.data.health.DailyHealthMetrics
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import java.io.File
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
    val totalCalories: Int = 0,
    val totalMeditationMinutes: Int = 0,
    
    val activeMinutes7Days: List<Float> = emptyList(),
    val distance7Days: List<Float> = emptyList(),
    val sleepDuration7Days: List<Float> = emptyList(),
    val todayDistanceKm: Double = 0.0,
    val todayActiveMinutes: Long = 0L,
    val todaySleepMinutes: Long = 0L,
    
    val habitCompletionRate: Float = 0f,
    val taskCompletionRate: Float = 0f,
    
    val criticalChoresCount: Int = 0,
    val cleanChoresCount: Int = 0,
    
    val isLoading: Boolean = true
)

@HiltViewModel
class LifeStatsViewModel @Inject constructor(
    gamificationRepository: GamificationRepository,
    moodRepository: MoodRepository,
    habitRepository: HabitRepository,
    choreRepository: ChoreRepository,
    private val taskRepository: TaskRepository,
    private val calculateChoreEntropyUseCase: CalculateChoreEntropyUseCase,
    gamificationPreferences: cloud.wafflecommons.pixelbrainreader.data.local.preferences.GamificationPreferences,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {

    // Dummy flow to trigger refresh when date changes
    private val todayFlow = flow {
        emit(LocalDate.now())
    }

    val uiState: StateFlow<LifeStatsUiState> = combine(
        moodRepository.getMoodFlow(),
        habitRepository.getLogsForYearFlow(LocalDate.now().year),
        choreRepository.getAllChoresStream(),
        combine(todayFlow, habitRepository.getHabitConfigsFlow()) { today, configs -> today to configs }
    ) { moods, habitLogsMap, chores, (today, configs) ->
        
        // --- Mental Health (Mood) ---
        val mood7Days = moods.filter { it.date >= today.minusDays(7).toString() }
        val mood30Days = moods.filter { it.date >= today.minusDays(30).toString() }
        
        val avgMood7Days = if (mood7Days.isNotEmpty()) mood7Days.map { it.score }.average().toFloat() else 0f
        val avgMood30Days = if (mood30Days.isNotEmpty()) mood30Days.map { it.score }.average().toFloat() else 0f
        
        // --- Physical Health (Metrics via JSON cache) ---
        var totalCalories = 0.0
        var totalMeditation = 0
        var totalHrSum = 0
        var hrDaysCount = 0
        
        val moodHistoryLine = mutableListOf<LifeStatsMoodPoint>()
        val distanceHistory = mutableListOf<Float>()
        val activeMinHistory = mutableListOf<Float>()
        val sleepHistory = mutableListOf<Float>()
        
        var todayDistanceKm = 0.0
        var todayActiveMinutes = 0L
        var todaySleepMinutes = 0L
        
        (6 downTo 0).forEach { offset ->
            val d = today.minusDays(offset.toLong())
            
            // Read JSON exactly as DailyNoteViewModel
            val metricsFile = File(context.filesDir, "10_Journal/data/health/metrics/$d.json")
            var dayAvgBpm = 0
            if (metricsFile.exists()) {
                try {
                    val dhm = com.google.gson.Gson().fromJson(metricsFile.readText(), DailyHealthMetrics::class.java)
                    dayAvgBpm = dhm?.averageHeartRate ?: 0
                    totalCalories += (dhm?.caloriesConsumed ?: 0.0)
                    totalMeditation += (dhm?.mindfulnessMinutes?.toInt() ?: 0)
                    if (dayAvgBpm > 0) {
                        totalHrSum += dayAvgBpm
                        hrDaysCount++
                    }
                    distanceHistory.add(dhm?.distanceKm?.toFloat() ?: 0f)
                    activeMinHistory.add(dhm?.activeMinutes?.toFloat() ?: 0f)
                    sleepHistory.add(dhm?.sleepDurationMinutes?.toFloat() ?: 0f)
                    if (offset == 0) {
                        todayDistanceKm = dhm?.distanceKm ?: 0.0
                        todayActiveMinutes = dhm?.activeMinutes ?: 0L
                        todaySleepMinutes = dhm?.sleepDurationMinutes ?: 0L
                    }
                } catch (e: Exception) {
                    distanceHistory.add(0f)
                    activeMinHistory.add(0f)
                    sleepHistory.add(0f)
                }
            } else {
                distanceHistory.add(0f)
                activeMinHistory.add(0f)
                sleepHistory.add(0f)
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

        // --- Productivity (Habits & Tasks) ---
        var totalHabitsScheduledToday = 0
        var completedHabitsToday = 0
        
        val dayMap = mapOf(
            java.time.DayOfWeek.MONDAY to "MON",
            java.time.DayOfWeek.TUESDAY to "TUE",
            java.time.DayOfWeek.WEDNESDAY to "WED",
            java.time.DayOfWeek.THURSDAY to "THU",
            java.time.DayOfWeek.FRIDAY to "FRI",
            java.time.DayOfWeek.SATURDAY to "SAT",
            java.time.DayOfWeek.SUNDAY to "SUN"
        )
        val todayKey = dayMap[today.dayOfWeek] ?: "MON"
        val todayString = today.toString()
        
        configs.filter { !it.archived }.forEach { habit ->
            val cleanFreq = habit.frequency.map { it.trim().uppercase() }
            val isScheduled = cleanFreq.isEmpty() || cleanFreq.contains(todayKey)
            
            if (isScheduled) {
                totalHabitsScheduledToday++
                val log = habitLogsMap[habit.id]?.find { it.date == todayString }
                if (log != null) {
                    val isCompleted = if (habit.type == cloud.wafflecommons.pixelbrainreader.data.model.HabitType.MEASURABLE) {
                        log.value >= habit.targetValue
                    } else {
                        log.status == cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus.COMPLETED
                    }
                    if (isCompleted) {
                        completedHabitsToday++
                    }
                }
            }
        }
        
        val habitCompletionRate = if (totalHabitsScheduledToday == 0) 1f else (completedHabitsToday.toFloat() / totalHabitsScheduledToday.toFloat())
        
        // Tasks (Suspend call workaround - we should ideally fetch this outside combine or use getTasksFlow)
        // Since getTasksFlow exists, let's just cheat and do synchronous runBlocking or simply rely on tasks flow. 
        // Wait! We can't do suspend call cleanly inside Combine. Let's return the state and we handle Tasks separately, Or use runBlocking. 
        // Wait, TaskRepository.kt has taskDao.getTasksSnapshot. Since this runs on IO, we can't easily launch inside Combine.
        // I will use another combine flow for Tasks below.
        
        // --- Home OS (Chores) ---
        val choreModels = calculateChoreEntropyUseCase(chores)
        val criticalChoresCount = choreModels.count { it.statusColor == StatusColor.RED }
        val cleanChoresCount = choreModels.count { it.statusColor == StatusColor.GREEN }

        LifeStatsUiState(
            moodHistory = moodHistoryLine,
            avgMood7Days = avgMood7Days,
            avgMood30Days = avgMood30Days,
            avgHeartRate = avgHeartRate,
            totalCalories = totalCalories.toInt(),
            totalMeditationMinutes = totalMeditation,
            activeMinutes7Days = activeMinHistory,
            distance7Days = distanceHistory,
            sleepDuration7Days = sleepHistory,
            todayDistanceKm = todayDistanceKm,
            todayActiveMinutes = todayActiveMinutes,
            todaySleepMinutes = todaySleepMinutes,
            habitCompletionRate = habitCompletionRate,
            taskCompletionRate = 0f, // Resolved below
            criticalChoresCount = criticalChoresCount,
            cleanChoresCount = cleanChoresCount,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LifeStatsUiState(isLoading = true))

    // Task Completion Rate resolver (7 Days)
    val taskRatiosFlow = kotlinx.coroutines.flow.flow {
        var totalTasks = 0
        var completedTasks = 0
        (0..6).forEach { offset ->
            val date = LocalDate.now().minusDays(offset.toLong())
            val tasksSnapshot = taskRepository.getTasks(date)
            totalTasks += tasksSnapshot.size
            completedTasks += tasksSnapshot.count { it.isDone }
        }
        val ratio = if (totalTasks > 0) completedTasks.toFloat() / totalTasks.toFloat() else 0f
        emit(ratio)
    }

    val finalUiState: StateFlow<LifeStatsUiState> = combine(uiState, taskRatiosFlow) { state, taskRatio ->
        state.copy(taskCompletionRate = taskRatio)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LifeStatsUiState(isLoading = true))

    val sleepDurationState: StateFlow<Long> = finalUiState.map { it.todaySleepMinutes }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val globalCompletionState: StateFlow<Float> = finalUiState.map { state ->
        val totalChores = state.criticalChoresCount + state.cleanChoresCount
        val choreRate = if (totalChores > 0) state.cleanChoresCount.toFloat() / totalChores.toFloat() else 0f
        
        // Simple average of 3 metrics (Task, Habit, Chores)
        (state.taskCompletionRate + state.habitCompletionRate + choreRate) / 3f
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)
}
