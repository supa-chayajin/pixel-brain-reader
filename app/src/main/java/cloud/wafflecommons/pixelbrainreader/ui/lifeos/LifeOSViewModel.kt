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
    val groupedHabits: Map<String, List<HabitWithStats>> = emptyMap(), // [NEW] Grouped by Category
    val logs: Map<String, List<HabitLogEntry>> = emptyMap(),
    val scopedTasks: List<Task> = emptyList(),
    val selectedDate: LocalDate = LocalDate.now(),
    val isLoading: Boolean = false
)

@HiltViewModel
class LifeOSViewModel @Inject constructor(
    private val habitRepository: HabitRepository,
    private val taskRepository: TaskRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LifeOSUiState())
    val uiState: StateFlow<LifeOSUiState> = _uiState.asStateFlow()

    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        // Initial load
        observeData(LocalDate.now())
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
            // Task repo might not be reactive yet, so we suspend fetch it
            // Ideal: taskRepository.getScopedTasksFlow(date)
            // For now, we mix: Observe Habits, Fetch Tasks
            
            combine(configsFlow, logsFlow) { configs, logsMap ->
                  val scopedTasks = taskRepository.getScopedTasks(date) // Ideally reactive too
                  
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
                         // Max 365 days check preventing infinite loop
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
                        val regex = Regex("\\(\\+([A-Z]{3})\\)")
                        val match = regex.find(habitStat.config.description)
                        val tag = match?.groupValues?.get(1)
                        when (tag) {
                             "VIG" -> "Vigor (Physical)"
                             "MND" -> "Mind (Mental)"
                             "INT" -> "Intellect (Learning)"
                             "END" -> "Endurance (Resilience)"
                             "FTH" -> "Faith (Spiritual)"
                             "SOC" -> "Social (Connection)"
                             "CRE" -> "Create (Expression)" 
                             null -> "General"
                             else -> "$tag" 
                        }
                    }
                    
                    // Flatten logs for UI convenience if needed
                    val flatLogs = logsMap.values.flatten().groupBy { it.date } // Map<Date, List<Log>>? No, Map<HabitId, List> in State
                    // UI State expects: logs: Map<String, List<HabitLogEntry>> (Date -> Entries? OR Habit -> Entries?)
                    // Previous Impl: logs was from getLogsForYear -> Map<String, List<HabitLogEntry>>??
                    // getLogsForYear previously returned getLogsForYear(year). wait.
                    // Previous HabitRepository.getLogsForYear returned Map<String, List<HabitLogEntry>> 
                    // where String likely was HabitId? NO, let's check code.
                    // Previous: `gson.fromJson(json, type)` type = `Map<String, List<HabitLogEntry>>`
                    // Yes, usually keyed by HabitID in JSON.
                    
                    Triple(configs, habitsWithStats, groupedHabits)
            }.collect { (configs, stats, grouped) ->
                 _uiState.update { 
                     it.copy(
                         habits = configs,
                         habitsWithStats = stats,
                         groupedHabits = grouped,
                         scopedTasks = taskRepository.getScopedTasks(date), // refresh tasks on collect
                         isLoading = false
                     )
                 }
            }
        }
    }

    fun toggleHabit(habitId: String) {
        viewModelScope.launch {
            val date = _uiState.value.selectedDate
            val stats = _uiState.value.habitsWithStats.find { it.config.id == habitId } ?: return@launch
            
            val newEntry = if (stats.isCompletedToday) {
                 HabitLogEntry(habitId, date.toString(), 0.0, HabitStatus.SKIPPED)
            } else {
                 HabitLogEntry(habitId, date.toString(), 1.0, HabitStatus.COMPLETED)
            }
            habitRepository.logHabit(date, newEntry)
            // No manual reload needed, Flow updates
        }
    }

    fun updateHabitValue(habitId: String, newValue: Double) {
        viewModelScope.launch {
            val date = _uiState.value.selectedDate
            val habitConfig = _uiState.value.habits.find { it.id == habitId } ?: return@launch
            
            val status = when {
                newValue >= habitConfig.targetValue -> HabitStatus.COMPLETED
                newValue > 0 -> HabitStatus.PARTIAL
                else -> HabitStatus.SKIPPED
            }
            
            val newEntry = HabitLogEntry(habitId, date.toString(), newValue, status)
            habitRepository.logHabit(date, newEntry)
        }
    }

    fun toggleTask(task: Task) {
        viewModelScope.launch {
            taskRepository.toggleTask(_uiState.value.selectedDate, task)
            // Task toggle doesn't update Habit Flows so we manually trigger task refresh?
            // Ideally tasks are also a flow. For now, let's just re-emit state
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
                description = "Created via Debug FAB",
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
