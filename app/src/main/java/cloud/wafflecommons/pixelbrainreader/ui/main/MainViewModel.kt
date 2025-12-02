package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.local.TokenManager
import cloud.wafflecommons.pixelbrainreader.data.remote.model.GithubFileDto
import cloud.wafflecommons.pixelbrainreader.data.repository.GithubRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UiState(
    val currentPath: String = "",
    val files: List<GithubFileDto> = emptyList(),
    val selectedFileContent: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: GithubRepository,
    private val tokenManager: TokenManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Hardcoded for now, could be dynamic or user input
    private val owner = "google"
    private val repo = "guava"

    init {
        loadFolder("")
    }

    fun loadFolder(path: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = repository.getContents(owner, repo, path)
            result.onSuccess { files ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    files = files.sortedBy { it.type }, // Folders first usually, but simple sort for now
                    currentPath = path,
                    selectedFileContent = null // Clear selection when changing folder
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun loadFile(file: GithubFileDto) {
        if (file.downloadUrl == null) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = repository.getFileContent(file.downloadUrl)
            result.onSuccess { content ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    selectedFileContent = content
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun navigateUp() {
        val currentPath = _uiState.value.currentPath
        if (currentPath.isEmpty()) return

        val parentPath = if (currentPath.contains("/")) {
            currentPath.substringBeforeLast("/")
        } else {
            ""
        }
        loadFolder(parentPath)
    }

    fun logout() {
        tokenManager.clearToken()
        // In a real app, we might expose a separate flow for navigation events
    }
}
