package cloud.wafflecommons.pixelbrainreader.ui.lifeos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.model.HabitLogEntry
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
import cloud.wafflecommons.pixelbrainreader.data.model.Task
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class HabitWithStats(
    val config: HabitConfig,
    val isCompletedToday: Boolean,
    val currentValue: Double, // New field for Measurable
    val currentStreak: Int,
    val history: List<Boolean>, // Last 7 days, boolean status
    val isScheduledToday: Boolean // [NEW] For visual dimming
)

data class LifeOSUiState(
    val habits: List<HabitConfig> = emptyList(),
    val habitsWithStats: List<HabitWithStats> = emptyList(),
    val groupedHabits: Map<String, List<HabitWithStats>> = emptyMap(),
    val logs: Map<String, List<HabitLogEntry>> = emptyMap(),
    val scopedTasks: List<Task> = emptyList(),
    val selectedDate: LocalDate = LocalDate.now(),
    val isLoading: Boolean = false,
    val gamificationState: cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationState = cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationState()
)

@HiltViewModel
class LifeOSViewModel @Inject constructor(
    private val habitRepository: HabitRepository,
    private val taskRepository: TaskRepository,
    private val gamificationRepository: cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository,
    private val grantXpUseCase: cloud.wafflecommons.pixelbrainreader.domain.gamification.GrantXpUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(LifeOSUiState())
    val uiState: StateFlow<LifeOSUiState> = _uiState.asStateFlow()

    private var loadJob: kotlinx.coroutines.Job? = null

    // One-time events channel for XP toasts (omitted for brevity, handled via log/simple toast logic in UI?)
    // Let's add a simple XpGainEvent flow
    private val _xpEvents = MutableSharedFlow<cloud.wafflecommons.pixelbrainreader.data.gamification.XpGainEntry>()
    val xpEvents = _xpEvents.asSharedFlow()

    init {
        // Initial load
        observeData(LocalDate.now())
        
        // Gamification auto-load
        viewModelScope.launch {
            gamificationRepository.loadState()
        }
    }

    private val _reloadTrigger = MutableSharedFlow<Unit>()
    val reloadTrigger = _reloadTrigger.asSharedFlow()

    fun loadData(date: LocalDate) {
         if (date == _uiState.value.selectedDate && loadJob?.isActive == true) return
         observeData(date)
    }

    private fun observeData(date: LocalDate) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
             _uiState.update { it.copy(isLoading = true, selectedDate = date) }

            // Reactive Streams
            val configsFlow = habitRepository.getHabitConfigsFlow()
            val logsFlow = habitRepository.getLogsForYearFlow(date.year)
            val gamificationFlow = gamificationRepository.gamificationState // Corrected property name

            combine(
                configsFlow, 
                logsFlow, 
                gamificationFlow
            ) { configs: List<HabitConfig>, logsMap: Map<String, List<HabitLogEntry>>, gamificationState: cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationState ->
                  val scopedTasks = taskRepository.getScopedTasks(date) // Ideal: reactive
                  
                   // [NEW] 1. Filter by Frequency (Day of Week)
                    val dayMap = mapOf(
                        java.time.DayOfWeek.MONDAY to "MON",
                        java.time.DayOfWeek.TUESDAY to "TUE",
                        java.time.DayOfWeek.WEDNESDAY to "WED",
                        java.time.DayOfWeek.THURSDAY to "THU",
                        java.time.DayOfWeek.FRIDAY to "FRI",
                        java.time.DayOfWeek.SATURDAY to "SAT",
                        java.time.DayOfWeek.SUNDAY to "SUN"
                    )
                    val todayKey = dayMap[date.dayOfWeek] ?: "MON"
                    
                    val habitsWithStats = configs.map { habit ->
                         val habitLogs = logsMap[habit.id] ?: emptyList()
                         
                         val cleanFreq = habit.frequency.map { it.trim().uppercase() }
                         val isScheduledToday = cleanFreq.isEmpty() || cleanFreq.contains(todayKey)
                         
                         val todayLog = habitLogs.find { it.date == date.toString() }
                         val isCompletedToday = isHabitComplete(habit, todayLog)
                         val currentValue = todayLog?.value ?: 0.0
                         
                         val history = (0..6).map { i ->
                            val checkDate = date.minusDays(i.toLong()).toString()
                            val log = habitLogs.find { it.date == checkDate }
                            isHabitComplete(habit, log)
                         }.reversed()
                         
                         // Streak
                         var streak = 0
                         var checkDate = if (isCompletedToday) date else date.minusDays(1)
                         for (i in 0..365) {
                              val d = checkDate.toString()
                              val log = habitLogs.find { it.date == d }
                              if (isHabitComplete(habit, log)) {
                                  streak++
                                  checkDate = checkDate.minusDays(1)
                              } else {
                                  break
                              }
                         }
                         
                         HabitWithStats(habit, isCompletedToday, currentValue, streak, history, isScheduledToday)
                    }
                    
                    // Grouping
                    val groupedHabits = habitsWithStats.groupBy { habitStat ->
                        val parser = cloud.wafflecommons.pixelbrainreader.data.gamification.AttributeParser
                        val attr = parser.parse(habitStat.config.description)
                        // Group by Attribute Name or Tag if present, else fallback
                        if (attr != null) {
                             "${attr.name} Training"
                        } else {
                             "General"
                        }
                    }
                    
                    LifeOSUiState(
                         habits = configs,
                         habitsWithStats = habitsWithStats,
                         groupedHabits = groupedHabits,
                         scopedTasks = scopedTasks,
                         isLoading = false,
                         selectedDate = date,
                         gamificationState = gamificationState // Corrected variable
                    )
            }.collect { newState ->
                 _uiState.value = newState
            }
        }
    }

    fun toggleHabit(habitId: String) {
        viewModelScope.launch {
            val date = _uiState.value.selectedDate
            val stats = _uiState.value.habitsWithStats.find { it.config.id == habitId } ?: return@launch
            
            val isCompleting = !stats.isCompletedToday
            val newEntry = if (!isCompleting) {
                 HabitLogEntry(habitId, date.toString(), 0.0, HabitStatus.SKIPPED)
            } else {
                 HabitLogEntry(habitId, date.toString(), 1.0, HabitStatus.COMPLETED)
            }
            habitRepository.logHabit(date, newEntry)
            
            // Grant XP only on Completion (not unchecking)
            // And only if it's TODAY (gamification should probably be real-time/strict?)
            // We allow backfilling but maybe reduce XP? For now, standard XP for any date logic.
            if (isCompleting) {
                grantXpUseCase.execute(
                    sourceId = habitId,
                    actionType = cloud.wafflecommons.pixelbrainreader.domain.gamification.XpActionType.HABIT_DONE
                )
                // Emit event?
                // Ideally GrantXpUseCase returns the diff or we observe state.
                // We'll rely on global state update for UI change.
            }
        }
    }

    fun updateHabitValue(habitId: String, newValue: Double) {
        viewModelScope.launch {
            val date = _uiState.value.selectedDate
            val habitConfig = _uiState.value.habits.find { it.id == habitId } ?: return@launch
            
            // Check if becoming complete
            val wasComplete = isHabitComplete(habitConfig, _uiState.value.logs[habitId]?.find { it.date == date.toString() })
            
            val status = when {
                newValue >= habitConfig.targetValue -> HabitStatus.COMPLETED
                newValue > 0 -> HabitStatus.PARTIAL
                else -> HabitStatus.SKIPPED
            }
            
            val newEntry = HabitLogEntry(habitId, date.toString(), newValue, status)
            habitRepository.logHabit(date, newEntry)
            
            // Logic: Award XP if crossing threshold? Or proportional?
            // Simple: Award on 'COMPLETED' status transition?
            // Or just allow repeated calls for now (Gameable, but simpler).
            // Let's stick to "If status becomes COMPLETED"
            val isNowComplete = status == HabitStatus.COMPLETED
            if (isNowComplete && !wasComplete) {
                 grantXpUseCase.execute(
                    sourceId = habitId,
                    actionType = cloud.wafflecommons.pixelbrainreader.domain.gamification.XpActionType.HABIT_DONE,
                    value = newValue
                )
            }
        }
    }

    fun toggleTask(task: Task) {
        viewModelScope.launch {
            taskRepository.toggleTask(_uiState.value.selectedDate, task)
            
            if (!task.isCompleted) { // If it was NOT completed, and we toggle it -> Completed
                 grantXpUseCase.execute(
                    sourceId = task.originalText.hashCode().toString(),
                    actionType = cloud.wafflecommons.pixelbrainreader.domain.gamification.XpActionType.TASK_DONE
                )
            }
            
            val tasks = taskRepository.getScopedTasks(_uiState.value.selectedDate)
             _uiState.update { it.copy(scopedTasks = tasks) }
             _reloadTrigger.emit(Unit)
        }
    }

    fun addDebugHabit() {
        viewModelScope.launch {
            val randomId = java.util.UUID.randomUUID().toString()
            val newHabit = HabitConfig(
                id = randomId,
                title = "New Habit ${randomId.take(4)}",
                description = "Created via Debug FAB (+VIG)",
                frequency = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
            )
            habitRepository.addHabitConfig(newHabit)
        }
    }

    private fun isHabitComplete(habit: HabitConfig, log: HabitLogEntry?): Boolean {
        if (log == null) return false
        return if (habit.type == cloud.wafflecommons.pixelbrainreader.data.model.HabitType.MEASURABLE) {
            log.value >= habit.targetValue
        } else {
            log.status == HabitStatus.COMPLETED
        }
    }
}
