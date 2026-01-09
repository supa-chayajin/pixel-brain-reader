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

data class LifeOSUiState(
    val habits: List<HabitConfig> = emptyList(),
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
            // Filter logs for this specific date might be useful UI side or here
            
            val scopedTasks = taskRepository.getScopedTasks(date)
            
            _uiState.value = _uiState.value.copy(
                habits = habits,
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
            
            // Reload logs
            val newLogs = habitRepository.getLogsForYear(date.year)
            _uiState.value = _uiState.value.copy(logs = newLogs)
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
