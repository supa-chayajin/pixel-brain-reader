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
    val isLoading: Boolean = false
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
     * Changes the selected date and reloads data.
     */
    fun selectDate(date: LocalDate) {
        _uiState.update { it.copy(selectedDate = date) }
        loadMood(date)
    }

    /**
     * Observes mood data for a specific date.
     */
    fun loadMood(date: LocalDate) {
        _uiState.update { it.copy(isLoading = true) }
        
        moodJob?.cancel()
        moodJob = moodRepository.getDailyMood(date)
            .onEach { data ->
                _uiState.update { it.copy(moodData = data, isLoading = false) }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Records a new mood entry for the currently selected date.
     */
    fun addMoodEntry(score: Int, activities: List<String>, note: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val now = LocalDateTime.now()
                // Auto-map score to label for simplicity
                val label = when(score) {
                    1 -> "😫"
                    2 -> "😞"
                    3 -> "😐"
                    4 -> "🙂"
                    5 -> "🤩"
                    else -> "😐"
                }

                val entry = MoodEntry(
                    time = now.format(DateTimeFormatter.ofPattern("HH:mm")),
                    score = score,
                    label = label,
                    activities = activities,
                    note = note.ifBlank { null }
                )
                moodRepository.addEntry(_uiState.value.selectedDate, entry)
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun resetState() {
        // No longer using success/error for simple autonomous design
    }
}
