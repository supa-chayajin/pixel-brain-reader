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
    val currentStreak: Int,
    val history: List<Boolean> // Last 7 days, boolean status
)

data class LifeOSUiState(
    val habits: List<HabitConfig> = emptyList(),
    val habitsWithStats: List<HabitWithStats> = emptyList(),
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

    private val _reloadTrigger = MutableSharedFlow<Unit>()
    val reloadTrigger = _reloadTrigger.asSharedFlow()

    fun loadData(date: LocalDate) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, selectedDate = date)
            
            val habits = habitRepository.getHabitConfigs()
            val logs = habitRepository.getLogsForYear(date.year)
            
            val scopedTasks = taskRepository.getScopedTasks(date)
            
            // Calculate Stats
            val habitsWithStats = habits.map { habit ->
                val habitLogs = logs.flatMap { it.value }.filter { it.habitId == habit.id }
                
                // 1. Is Completed Today?
                val todayLog = habitLogs.find { it.date == date.toString() }
                val isCompletedToday = todayLog?.status == HabitStatus.COMPLETED
                
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
                
                HabitWithStats(habit, isCompletedToday, streak, history)
            }
            
            _uiState.value = _uiState.value.copy(
                habits = habits,
                habitsWithStats = habitsWithStats,
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
