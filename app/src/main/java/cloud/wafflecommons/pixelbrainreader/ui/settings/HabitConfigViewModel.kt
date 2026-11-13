package cloud.wafflecommons.pixelbrainreader.ui.settings

import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.CustodySchedule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class HabitConfigViewModel @Inject constructor(
    private val habitRepository: HabitRepository,
    modesRepository: ModesRepository
) : ViewModel() {

    private val _habits = MutableStateFlow<List<HabitConfig>>(emptyList())
    val habits: StateFlow<List<HabitConfig>> = _habits.asStateFlow()

    /** The Modes custody calendar: the editor offers the "Days" choice only while it is on. */
    val custody: StateFlow<CustodySchedule> = modesRepository.config
        .map { it.custody }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), modesRepository.config.value.custody)

    init {
        loadHabits()
    }

    private fun loadHabits() {
        viewModelScope.launch {
            habitRepository.getHabitConfigsFlow().collect { configs ->
                _habits.value = configs.sortedBy { it.sortOrder }
            }
        }
    }

    fun saveHabit(habit: HabitConfig) {
        viewModelScope.launch {
            habitRepository.addHabitConfig(habit)
            // Note: addHabitConfig already calls exportConfigToJson() inside HabitRepository as requested
        }
    }

    fun deleteHabit(habit: HabitConfig) {
        viewModelScope.launch {
            val archived = habit.copy(archived = true)
            habitRepository.addHabitConfig(archived)
        }
    }

    fun forceSyncFromVault() {
        viewModelScope.launch {
            habitRepository.importConfigFromJson()
        }
    }
}
