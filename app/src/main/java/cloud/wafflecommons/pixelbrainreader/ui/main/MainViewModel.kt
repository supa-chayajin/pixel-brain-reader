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
    val selectedFileName: String? = null, // Pour afficher le titre
    val isLoading: Boolean = false,
    val error: String? = null,
    val isFocusMode: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: GithubRepository,
    private val tokenManager: TokenManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadFolder("")
    }

    fun loadFolder(path: String) {
        val (owner, repo) = tokenManager.getRepoInfo()
        if (owner == null || repo == null) {
            _uiState.value = _uiState.value.copy(error = "Repository information not found. Please login again.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = repository.getContents(owner, repo, path)
            result.onSuccess { files ->
                // FILTRAGE : On retire les fichiers cachés (.)
                val filteredFiles = files.filter { file ->
                    !file.name.startsWith(".")
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    files = filteredFiles.sortedWith(compareBy({ it.type != "dir" }, { it.name })), // Dossiers en premier
                    currentPath = path,
                    selectedFileContent = null,
                    selectedFileName = null
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun loadFile(file: GithubFileDto) {
        if (file.downloadUrl == null) return

        viewModelScope.launch {
            // On sauvegarde le nom immédiatement pour l'UI
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                selectedFileName = file.name
            )

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
    }

    fun toggleFocusMode() {
        _uiState.value = _uiState.value.copy(isFocusMode = !_uiState.value.isFocusMode)
    }
}
