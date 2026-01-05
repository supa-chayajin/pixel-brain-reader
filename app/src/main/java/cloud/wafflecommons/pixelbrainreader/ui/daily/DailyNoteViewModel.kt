package cloud.wafflecommons.pixelbrainreader.ui.daily

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyMoodData
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.utils.FrontmatterManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class DailyNoteState(
    val date: LocalDate = LocalDate.now(),
    val moodData: DailyMoodData? = null,
    val noteContent: String = "",
    val isLoading: Boolean = true
)

@HiltViewModel
class DailyNoteViewModel @Inject constructor(
    private val moodRepository: MoodRepository,
    private val fileRepository: FileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DailyNoteState())
    val uiState: StateFlow<DailyNoteState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            val today = LocalDate.now()
            val formattedDate = today.format(DateTimeFormatter.ISO_DATE) // YYYY-MM-DD
            val notePath = "10_Journal/$formattedDate.md"

            // Step A: Fetch Mood Data
            val moodFlow = moodRepository.getDailyMood(today)

            // Step B: Fetch Markdown Content
            val contentFlow = fileRepository.getFileContentFlow(notePath)

            combine(moodFlow, contentFlow) { mood, content ->
                val params = if (content != null) {
                    FrontmatterManager.stripFrontmatter(content)
                } else {
                    ""
                }
                
                DailyNoteState(
                    date = today,
                    moodData = mood,
                    noteContent = params,
                    isLoading = false
                )
            }.catch { e ->
                // Handle error
                _uiState.value = _uiState.value.copy(isLoading = false)
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }
}
