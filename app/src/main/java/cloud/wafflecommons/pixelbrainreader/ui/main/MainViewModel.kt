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

// UiState moved inside MainViewModel or defined here if needed.
// Cleaning up previous definition to avoid duplicates.

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: FileRepository,
    private val secretManager: SecretManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    
    // Add isRefreshing state
    data class UiState(
        val currentPath: String = "",
        val files: List<GithubFileDto> = emptyList(),
        val selectedFileContent: String? = null,
        val selectedFileName: String? = null,
        val isLoading: Boolean = false, // Full screen loader (Init only)
        val isRefreshing: Boolean = false, // Pull-to-refresh
        val error: String? = null,
        val isFocusMode: Boolean = false
    )

    private var filesObservationJob: Job? = null
    private var contentObservationJob: Job? = null

    init {
        // Initial silent sync
        loadFolder("")
    }

    /**
     * Navigation Logic (Offline-First).
     * 1. Updates Path.
     * 2. Observes DB immediately (Instant UI).
     * 3. Triggers Silent Sync (Background).
     */
    fun loadFolder(path: String) {
        _uiState.value = _uiState.value.copy(currentPath = path, error = null)
        
        // Instant DB Access
        observeDatabase(path)
        
        // Silent Sync
        syncFolder(path, isUserAction = false)
    }

    /**
     * User Action: Pull-to-Refresh.
     */
    fun refresh() {
        val path = _uiState.value.currentPath
        syncFolder(path, isUserAction = true)
    }

    private fun syncFolder(path: String, isUserAction: Boolean) {
        val (owner, repo) = secretManager.getRepoInfo()
        if (owner == null || repo == null) return

        viewModelScope.launch {
            if (isUserAction) {
                _uiState.value = _uiState.value.copy(isRefreshing = true)
            }
            // Note: We do NOT set isLoading = true here. Data is visible from DB.

            val result = repository.refreshFiles(owner, repo, path)
            
            _uiState.value = _uiState.value.copy(isRefreshing = false)
            
            if (result.isFailure) {
                val errorMsg = result.exceptionOrNull()?.localizedMessage ?: "Unknown error"
                Log.w("MainViewModel", "Sync failed: $errorMsg")
                // Only show error toast/snackbar if user explicitly refreshed, or just update state 
                if (isUserAction) {
                     _uiState.value = _uiState.value.copy(error = "Sync Failed: $errorMsg")
                }
            }
        }
    }

    private fun observeDatabase(path: String) {
        filesObservationJob?.cancel()
        filesObservationJob = repository.getFiles(path)
            .onEach { entities ->
                val dtos = entities
                    .map { it.toDto() }
                    .filter { !it.name.startsWith(".") }
                    .sortedWith(compareBy({ it.type != "dir" }, { it.name }))

                _uiState.value = _uiState.value.copy(files = dtos)
            }
            .catch { e ->
                _uiState.value = _uiState.value.copy(error = "DB Error: ${e.message}")
            }
            .launchIn(viewModelScope)
    }

    fun loadFile(file: GithubFileDto) {
        // Reset View
        _uiState.value = _uiState.value.copy(
            selectedFileName = file.name, 
            selectedFileContent = null,
            error = null
        )

        // Observe Content Flow
        val fileId = file.path // Or use API path. Assuming file.path is the repo path.
        observeFileContent(fileId)

        // Trigger Sync
        if (file.downloadUrl != null) {
            syncFile(fileId, file.downloadUrl, isUserAction = false)
        }
    }
    
    fun refreshFile(file: GithubFileDto) {
        if (file.downloadUrl == null) return
        syncFile(file.path, file.downloadUrl, isUserAction = true)
    }

    private fun observeFileContent(path: String) {
        contentObservationJob?.cancel()
        contentObservationJob = repository.getFileContentFlow(path)
            .onEach { content ->
                 _uiState.value = _uiState.value.copy(selectedFileContent = content)
            }
            .launchIn(viewModelScope)
    }

    private fun syncFile(path: String, url: String, isUserAction: Boolean) {
         viewModelScope.launch {
            if (isUserAction) _uiState.value = _uiState.value.copy(isRefreshing = true)
            
            repository.refreshFileContent(path, url)
            
            if (isUserAction) _uiState.value = _uiState.value.copy(isRefreshing = false)
         }
    }

    fun navigateUp() {
        val currentPath = _uiState.value.currentPath
        if (currentPath.isNotEmpty()) {
            val parentPath = if(currentPath.contains("/")) currentPath.substringBeforeLast("/") else ""
            loadFolder(parentPath)
        } else {
             // Already at root.
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
