package cloud.wafflecommons.pixelbrainreader.ui.mood

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyMoodData
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodEntry
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
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
    private val moodRepository: MoodRepository,
    private val moodTagRepository: cloud.wafflecommons.pixelbrainreader.data.repository.MoodTagRepository,
    private val noteRepository: cloud.wafflecommons.pixelbrainreader.data.repository.NoteRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MoodState())
    val uiState: StateFlow<MoodState> = _uiState.asStateFlow()

    /** Canonical, vault-synced activity tags offered in the check-in sheet. */
    val availableTags: StateFlow<List<String>> = moodTagRepository.tags

    init {
        // Initial load for today
        loadMood(LocalDate.now())
        // Hydrate the offered-tags list from the vault (seeds defaults on first run).
        viewModelScope.launch { moodTagRepository.ensureLoaded() }
    }

    /**
     * Changes the selected date and reloads data.
     */
    fun selectDate(date: LocalDate) {
        loadMood(date)
    }

    /**
     * Observes mood data for a specific date.
     */
    private var loadJob: kotlinx.coroutines.Job? = null

    /**
     * Observes mood data for a specific date.
     * Uses UNDISPATCHED start to ensure immediate execution on the current thread until the first suspension.
     */
    fun loadMood(date: LocalDate) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, selectedDate = date) }

            moodRepository.getDailyMood(date)
                .catch { e ->
                    Log.e("MoodViewModel", "Crash prevented", e)
                    _uiState.update { it.copy(moodData = null, isLoading = false) }
                }
                .collect { data ->
                    _uiState.update { it.copy(moodData = data, isLoading = false) }
                }
        }
    }

    /**
     * Refreshes the mood data for the currently selected date.
     * Can be called from UI onResume or after a Git Sync.
     */
    fun refreshData() {
        loadMood(_uiState.value.selectedDate)
    }

    /**
     * Records a new mood entry for the currently selected date.
     */
    // Event Channel
    private val _uiEvent = kotlinx.coroutines.flow.MutableSharedFlow<cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent>()
    val uiEvent = _uiEvent.asSharedFlow()

    fun addMoodEntry(score: Int, activities: List<String>, note: String, customTimestamp: LocalDateTime? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val timestamp = customTimestamp ?: LocalDateTime.now()
                
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
                    time = timestamp.format(DateTimeFormatter.ofPattern("HH:mm")),
                    score = score,
                    label = label,
                    activities = activities,
                    note = note.ifBlank { null }
                )
                val targetDate = timestamp.toLocalDate()
                
                // 1. Insert into Room Database (SSOT for fast queries)
                moodRepository.addEntry(targetDate, entry)
                
                // 2. Dual-Sync: Write to Markdown Frontmatter
                try {
                    val path = "10_Journal/diary/${targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE)}.md"
                    noteRepository.updateNoteMetadata(
                        path = path,
                        updates = mapOf(
                            "mood_score" to score,
                            "mood_emoji" to label
                        )
                    )
                } catch (e: Exception) {
                    Log.e("MoodViewModel", "Failed to sync mood to markdown frontmatter", e)
                    // We don't throw here, DB entry was successful.
                }

                _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Mood Saved & Synced ✅"))
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Sync Failed ❌: ${e.message}"))
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun resetState() {
        // No longer using success/error for simple autonomous design
    }
}
