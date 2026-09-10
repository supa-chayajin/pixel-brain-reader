package cloud.wafflecommons.pixelbrainreader.ui.mood

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyMoodData
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodEntry
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodTagRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.NoteRepository
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
    moodTagRepository: MoodTagRepository,
    private val noteRepository: NoteRepository,
    appClock: AppClock
) : ViewModel() {

    private val _uiState = MutableStateFlow(MoodState(selectedDate = appClock.today.value))
    val uiState: StateFlow<MoodState> = _uiState.asStateFlow()

    /** Canonical, vault-synced activity tags offered in the check-in sheet. */
    val availableTags: StateFlow<List<String>> = moodTagRepository.tags

    // One-shot UI events (toasts). Buffered so emitting never suspends the save coroutine.
    private val _uiEvent = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    private var loadJob: Job? = null

    init {
        loadMood(appClock.today.value)
        // Hydrate the offered-tags list from the vault (seeds defaults on first run).
        viewModelScope.launch { moodTagRepository.ensureLoaded() }
        // Keep "Today" on the real day: if the date rolls over while today is shown, follow it.
        // A past day the user navigated to stays selected.
        var previousToday = appClock.today.value
        appClock.today
            .onEach { today ->
                if (today != previousToday && _uiState.value.selectedDate == previousToday) loadMood(today)
                previousToday = today
            }
            .launchIn(viewModelScope)
    }

    /**
     * Changes the selected date and reloads data.
     */
    fun selectDate(date: LocalDate) {
        loadMood(date)
    }

    /**
     * Observes mood data for a specific date (a live Room flow: it follows new entries and syncs).
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
     * Records a new mood entry. Saving is local (vault JSON + Room); the git push is batched in the
     * background, so the sheet no longer waits on the network.
     */
    fun addMoodEntry(score: Int, activities: List<String>, note: String, customTimestamp: LocalDateTime? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val message = try {
                val timestamp = customTimestamp ?: LocalDateTime.now()

                // Auto-map score to label for simplicity
                val label = when (score) {
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

                // 1. Vault JSON + Room (SSOT for fast queries)
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Not fatal: the entry itself was saved.
                    Log.e("MoodViewModel", "Failed to sync mood to markdown frontmatter", e)
                }
                "Mood saved ✅"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MoodViewModel", "Failed to save mood", e)
                "Couldn't save mood ❌: ${e.message}"
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
            _uiEvent.emit(UiEvent.ShowToast(message))
        }
    }
}
