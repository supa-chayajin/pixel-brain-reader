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
    private val taskRepository: TaskRepository,
    private val dataRefreshBus: cloud.wafflecommons.pixelbrainreader.data.utils.DataRefreshBus
) : ViewModel() {

    init {
        viewModelScope.launch {
            dataRefreshBus.refreshEvent.collect {
                // Reload on Sync success
                loadData(_uiState.value.selectedDate)
            }
        }
    }

    private val _uiState = MutableStateFlow(LifeOSUiState())
    val uiState: StateFlow<LifeOSUiState> = _uiState.asStateFlow()

    private val _reloadTrigger = MutableSharedFlow<Unit>()
    val reloadTrigger = _reloadTrigger.asSharedFlow()

    fun loadData(date: LocalDate) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, selectedDate = date)
            
            val allHabits = habitRepository.getHabitConfigs()
            val logs = habitRepository.getLogsForYear(date.year)
            
            val scopedTasks = taskRepository.getScopedTasks(date)

            // [NEW] 1. Filter by Frequency (Day of Week)
            // Use Strict Mapping to ensure config.json "MON", "TUE" matches LocalDate.DayOfWeek
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
            
            // [UPDATED] Do NOT filter out inactive habits. We want to show All.
            // Just calculate 'isScheduledToday'
            
            // Calculate Stats
            val habitsWithStats = allHabits.map { habit ->
                val habitLogs = logs.flatMap { it.value }.filter { it.habitId == habit.id }
                
                // Check if scheduled
                val cleanFreq = habit.frequency.map { it.trim().uppercase() }
                val isScheduledToday = cleanFreq.isEmpty() || cleanFreq.contains(todayKey)
                
                // 1. Is Completed Today & Current Value
                val todayLog = habitLogs.find { it.date == date.toString() }
                val isCompletedToday = todayLog?.status == HabitStatus.COMPLETED
                val currentValue = todayLog?.value ?: 0.0
                
                // 2. Calculate History (Last 7 days)
                val history = (0..6).map { i ->
                    val checkDate = date.minusDays(i.toLong())
                    val checkLog = habitLogs.find { it.date == checkDate.toString() }
                    checkLog?.status == HabitStatus.COMPLETED
                }.reversed() // Oldest to Newest
                
                // 3. Calculate Current Streak
                var streak = 0
                // Start checking from Yesterday (or Today if completed)
                var checkDate = if (isCompletedToday) date else date.minusDays(1)
                
                while (true) {
                    val log = habitLogs.find { it.date == checkDate.toString() }
                    if (log?.status == HabitStatus.COMPLETED) {
                        streak++
                        checkDate = checkDate.minusDays(1)
                    } else {
                        break
                    }
                }
                
                HabitWithStats(habit, isCompletedToday, currentValue, streak, history, isScheduledToday)
            }

            // [NEW] 2. Group by RPG Tag
            // Extract tag from description like (+VIG), (+MND). Default to "General"
            val groupedHabits = habitsWithStats.groupBy { habitStat ->
                val regex = Regex("\\(\\+([A-Z]{3})\\)")
                val match = regex.find(habitStat.config.description)
                val tag = match?.groupValues?.get(1) // "VIG"
                
                when (tag) {
                    "VIG" -> "Vigor (Physical)"
                    "MND" -> "Mind (Mental)"
                    "INT" -> "Intellect (Learning)"
                    "END" -> "Endurance (Resilience)"
                    "FTH" -> "Faith (Spiritual)"
                    "SOC" -> "Social (Connection)"
                    "CRE" -> "Create (Expression)" // Added generic common RPG stats
                    null -> "General"
                    else -> "$tag" 
                }
            }
            
            _uiState.value = _uiState.value.copy(
                habits = allHabits, // Show ALL
                habitsWithStats = habitsWithStats,
                groupedHabits = groupedHabits,
                logs = logs,
                scopedTasks = scopedTasks,
                isLoading = false
            )
        }
    }

    fun toggleHabit(habitId: String) {
        viewModelScope.launch {
            val date = _uiState.value.selectedDate
            val currentLogs = _uiState.value.logs[date.toString()] ?: emptyList()
            val existing = currentLogs.find { it.habitId == habitId }
            
            val newEntry = if (existing?.status == HabitStatus.COMPLETED) {
                 HabitLogEntry(habitId, date.toString(), 0.0, HabitStatus.SKIPPED)
            } else {
                 HabitLogEntry(habitId, date.toString(), 1.0, HabitStatus.COMPLETED)
            }
            
            habitRepository.logHabit(date, newEntry)
            
            // Reload everything to update streaks and UI
            loadData(date)
        }
    }

    fun updateHabitValue(habitId: String, newValue: Double) {
        viewModelScope.launch {
            val date = _uiState.value.selectedDate
            
            val habitConfig = _uiState.value.habits.find { it.id == habitId } ?: return@launch
            
            // Auto STATUS logic
            val status = when {
                newValue >= habitConfig.targetValue -> HabitStatus.COMPLETED
                newValue > 0 -> HabitStatus.PARTIAL
                else -> HabitStatus.SKIPPED
            }
            
            val newEntry = HabitLogEntry(habitId, date.toString(), newValue, status)
            habitRepository.logHabit(date, newEntry)
            
            loadData(date)
        }
    }

    fun toggleTask(task: Task) {
        viewModelScope.launch {
            taskRepository.toggleTask(_uiState.value.selectedDate, task)
            loadData(_uiState.value.selectedDate)
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
            loadData(_uiState.value.selectedDate)
        }
    }
}
