package cloud.wafflecommons.pixelbrainreader.ui.main

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import cloud.wafflecommons.pixelbrainreader.data.remote.model.GithubFileDto
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UiState(
    val currentPath: String = "",
    val files: List<GithubFileDto> = emptyList(),
    val selectedFileContent: String? = null,
    val selectedFileName: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isFocusMode: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: FileRepository,
    private val secretManager: SecretManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    
    private var filesObservationJob: Job? = null

    init {
        loadFolder("")
    }

    fun loadFolder(path: String) {
        val (owner, repo) = secretManager.getRepoInfo()
        if (owner == null || repo == null) {
            _uiState.value = _uiState.value.copy(error = "Repository info missing.")
            return
        }

        // 1. Update Path & Reset Error
        _uiState.value = _uiState.value.copy(currentPath = path, error = null)

        // 2. Observe Database -> UI
        observeDatabase(path)

        // 3. Trigger Network Sync
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = repository.refreshFiles(owner, repo, path)
            
            if (result.isFailure) {
                // Offline Mode or Network Failure
                val errorMsg = result.exceptionOrNull()?.localizedMessage ?: "Unknown error"
                Log.e("MainViewModel", "Sync failed: $errorMsg")
                
                // Only show error if we have no files (Critical Failure) OR show as toast/snackbar (Transient)
                // For this V2 Reader, we show error in UI state but preserve files if present.
                if (_uiState.value.files.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Offline Mode (Empty Cache): $errorMsg"
                    )
                } else {
                     _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Sync Failed: $errorMsg" // UI should handle transient error display
                    )
                }
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
        
        // Safety Check: Database Timeout Monitor
        viewModelScope.launch {
            delay(5000)
            if (_uiState.value.isLoading && _uiState.value.files.isEmpty()) {
                Log.w("MainViewModel", "Loading timeout. DB or Network might be stuck.")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Loading Timeout. Check logs."
                )
            }
        }
    }

    private fun observeDatabase(path: String) {
        Log.d("MainViewModel", "Observing files for path: '$path'")
        filesObservationJob?.cancel()
        filesObservationJob = repository.getFiles(path)
            .onEach { entities ->
                Log.d("MainViewModel", "Database emitted ${entities.size} files for path '$path'")
                
                val dtos = entities
                    .map { it.toDto() }
                    .filter { !it.name.startsWith(".") }
                    .sortedWith(compareBy({ it.type != "dir" }, { it.name }))

                _uiState.value = _uiState.value.copy(files = dtos)
            }
            .catch { e ->
                Log.e("MainViewModel", "Database observation error", e)
                _uiState.value = _uiState.value.copy(error = "Database Error: ${e.message}")
            }
            .launchIn(viewModelScope)
    }

    fun loadFile(file: GithubFileDto) {
        if (file.downloadUrl == null) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, selectedFileName = file.name)
            
            val result = repository.getFileContent(file.downloadUrl)
            result.onSuccess { content ->
                _uiState.value = _uiState.value.copy(isLoading = false, selectedFileContent = content)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun navigateUp() {
        val currentPath = _uiState.value.currentPath
        if (currentPath.isNotEmpty()) {
            val parentPath = if(currentPath.contains("/")) currentPath.substringBeforeLast("/") else ""
            loadFolder(parentPath)
        }
    }

    fun logout() {
        secretManager.clear()
    }

    fun toggleFocusMode() {
        _uiState.value = _uiState.value.copy(isFocusMode = !_uiState.value.isFocusMode)
    }

    private fun FileEntity.toDto() = GithubFileDto(name, path, type, downloadUrl)
}
