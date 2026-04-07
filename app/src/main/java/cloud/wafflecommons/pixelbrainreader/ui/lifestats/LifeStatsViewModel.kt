package cloud.wafflecommons.pixelbrainreader.ui.lifestats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.domain.gamification.ApplyHealthSynergyUseCase
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

data class LifeStatsMoodPoint(
    val date: LocalDate,
    val score: Float,
    val emoji: String,
    val avgBpm: Int = 0
)

data class LifeStatsUiState(
    val moodHistory: List<LifeStatsMoodPoint> = emptyList(),
    val avgMood7Days: Float = 0f,
    val avgMood30Days: Float = 0f,
    
    val avgHeartRate: Int = 0,
    val totalCalories: Int = 0,
    val totalMeditationMinutes: Int = 0,
    
    val habitCompletionRate: Float = 0f,
    val taskCompletionRate: Float = 0f,
    
    val criticalChoresCount: Int = 0,
    val cleanChoresCount: Int = 0,
    
    val isHealthSynergyActive: Boolean = false,
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
    private val applyHealthSynergyUseCase: ApplyHealthSynergyUseCase,
    gamificationPreferences: cloud.wafflecommons.pixelbrainreader.data.local.preferences.GamificationPreferences,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {

    init {
        viewModelScope.launch {
            applyHealthSynergyUseCase(LocalDate.now())
        }
    }

    private val isHealthSynergyActiveFlow = gamificationPreferences.lastHealthSynergyAppliedDateFlow
        .map { it == LocalDate.now().toString() }

    // Dummy flow to trigger refresh when date changes
    private val todayFlow = flow {
        emit(LocalDate.now())
    }

    val uiState: StateFlow<LifeStatsUiState> = combine(
        moodRepository.getMoodFlow(),
        habitRepository.getLogsForYearFlow(LocalDate.now().year),
        choreRepository.getAllChoresStream(),
        isHealthSynergyActiveFlow,
        todayFlow // ensures we have 5 arguments for combine, or we can use combine over 5 sources
    ) { moods, habitLogsMap, chores, synergyActive, today ->
        
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
                } catch (e: Exception) {}
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
        var totalHabitLogs = 0
        var completedHabitLogs = 0
        
        // Look at past 7 days for habits
        val past7DaysStrings = (0..6).map { today.minusDays(it.toLong()).toString() }
        
        habitLogsMap.forEach { (habitId, logs) ->
            logs.filter { it.date in past7DaysStrings }.forEach { log ->
                val target = log.value
                if (target > 0) completedHabitLogs++
                totalHabitLogs++
            }
        }
        val habitCompletionRate = if (totalHabitLogs == 0) 0f else (completedHabitLogs.toFloat() / totalHabitLogs.toFloat())
        
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
            habitCompletionRate = habitCompletionRate,
            taskCompletionRate = 0f, // Resolved below
            criticalChoresCount = criticalChoresCount,
            cleanChoresCount = cleanChoresCount,
            isHealthSynergyActive = synergyActive,
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
}
