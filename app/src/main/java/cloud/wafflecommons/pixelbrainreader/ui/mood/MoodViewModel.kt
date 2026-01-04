package cloud.wafflecommons.pixelbrainreader.ui.mood

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyMoodData
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodEntry
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * UI State for the Mood Tracker.
 */
data class MoodState(
    val selectedDate: LocalDate = LocalDate.now(),
    val moodData: DailyMoodData? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val success: Boolean = false // Used for auto-dismissing sheets or showing confirmation
)

@HiltViewModel
class MoodViewModel @Inject constructor(
    private val moodRepository: MoodRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MoodState())
    val uiState: StateFlow<MoodState> = _uiState.asStateFlow()

    private var moodJob: kotlinx.coroutines.Job? = null

    init {
        // Initial load for today
        loadMood(LocalDate.now())
    }

    /**
     * Changes the selected date and observes its mood data.
     */
    fun loadMood(date: LocalDate) {
        _uiState.update { it.copy(selectedDate = date) }
        
        moodJob?.cancel()
        moodJob = moodRepository.getDailyMood(date)
            .onEach { data ->
                _uiState.update { it.copy(moodData = data) }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Records a new mood entry for the currently selected date.
     */
    fun addMoodEntry(score: Int, label: String, activities: List<String>, note: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, success = false) }
            try {
                val now = LocalDateTime.now()
                val entry = MoodEntry(
                    time = now.format(DateTimeFormatter.ofPattern("HH:mm")),
                    score = score,
                    label = label,
                    activities = activities,
                    note = note.ifBlank { null }
                )
                moodRepository.addEntry(_uiState.value.selectedDate, entry)
                _uiState.update { it.copy(isLoading = false, success = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Failed to save mood") }
            }
        }
    }

    /**
     * Resets the success/error state (e.g., after dismissing a sheet).
     */
    fun resetState() {
        _uiState.update { it.copy(success = false, error = null) }
    }
}
