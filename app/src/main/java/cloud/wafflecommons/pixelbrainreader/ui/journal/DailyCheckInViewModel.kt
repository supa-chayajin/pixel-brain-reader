package cloud.wafflecommons.pixelbrainreader.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyNoteRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.utils.DailyLogEntry
import cloud.wafflecommons.pixelbrainreader.data.utils.DailySummary
import cloud.wafflecommons.pixelbrainreader.data.utils.FrontmatterManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class DailyCheckInViewModel @Inject constructor(
    private val fileRepository: FileRepository,
    private val dailyNoteRepository: DailyNoteRepository,
    private val secretManager: SecretManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(DailyCheckInUiState())
    val uiState: StateFlow<DailyCheckInUiState> = _uiState.asStateFlow()

    data class DailyCheckInUiState(
        val isLoading: Boolean = false,
        val success: Boolean = false,
        val error: String? = null,
        val message: String? = null,
        val summary: DailySummary = DailySummary(null, null, emptyList())
    )

    init {
        loadTodayStatus()
    }

    /**
     * Reads today's file to extract the summary for the UI.
     */
    fun loadTodayStatus() {
        viewModelScope.launch {
            try {
                val date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                val targetPath = "10_Journal/$date.md"
                val currentContent = fileRepository.getFileContentFlow(targetPath).first() ?: ""
                val summary = FrontmatterManager.getDailySummary(currentContent) ?: DailySummary(null, null, emptyList())
                _uiState.value = _uiState.value.copy(summary = summary)
            } catch (e: Exception) {
                // Fail silently for status loading
                android.util.Log.e("DailyCheckIn", "Failed to load today status", e)
            }
        }
    }

    fun submitCheckIn(score: Int, label: String, activities: List<String>, note: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                // 1. Ensure Directory & Path
                fileRepository.createLocalFolder("10_Journal")
                val date = java.time.LocalDate.now().toString() // yyyy-MM-dd
                val targetPath = "10_Journal/$date.md"

                // 2. Ensure File Exists (handles template creation if missing)
                dailyNoteRepository.getOrCreateTodayNote()
                val currentContent = fileRepository.getFileContentFlow(targetPath).first() ?: ""
                
                val now = LocalDateTime.now()
                val entry = DailyLogEntry(
                    time = now.format(DateTimeFormatter.ofPattern("HH:mm")),
                    moodScore = score,
                    moodLabel = label,
                    activities = activities,
                    note = note.ifBlank { null }
                )
                
                val updatedContent = FrontmatterManager.updateDailyLog(currentContent, entry)
                fileRepository.saveFileLocally(targetPath, updatedContent)
                
                val (owner, repo) = secretManager.getRepoInfo()
                if (owner != null && repo != null) {
                    fileRepository.pushDirtyFiles(owner, repo)
                }
                
                val newSummary = FrontmatterManager.getDailySummary(updatedContent) ?: DailySummary(null, null, emptyList())
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    success = true,
                    message = "Check-in saved!",
                    summary = newSummary
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Unknown error")
            }
        }
    }

    fun resetState() {
        _uiState.value = _uiState.value.copy(success = false, message = null, error = null)
    }
}
