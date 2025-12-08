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
        val unsavedContent: String? = null, // The Draft
        val selectedFileName: String? = null,
        val isLoading: Boolean = false, // Full screen loader (Init only)
        val isRefreshing: Boolean = false, // Pull-to-refresh
        val error: String? = null,
        val isFocusMode: Boolean = false,
        val isEditing: Boolean = false, // Edit Mode
        val isSyncing: Boolean = false,

        val hasUnsavedChanges: Boolean = false,
        val importState: ImportState? = null
    )

    data class ImportState(val title: String, val content: String)

    private var filesObservationJob: Job? = null
    private var contentObservationJob: Job? = null

    init {
        // Initial silent sync & Prime the DB (Authoritative Root Sync)
        loadFolder("")
        viewModelScope.launch {
            val (owner, repo) = secretManager.getRepoInfo()
            if (owner != null && repo != null) {
                repository.refreshFolder(owner, repo, "")
            }
        }
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
        
        // Silent Sync (Authoritative)
        syncFolder(path, isUserAction = false)
    }

    fun refreshCurrentFolder() {
        val path = _uiState.value.currentPath
        val (owner, repo) = secretManager.getRepoInfo()
        if (owner == null || repo == null) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, isRefreshing = true)
            
            // Hard Refresh / Authoritative Sync
            val result = repository.refreshFolder(owner, repo, path)
            
            _uiState.value = _uiState.value.copy(isLoading = false, isRefreshing = false)
            
            if (result.isFailure) {
                val errorMsg = result.exceptionOrNull()?.localizedMessage ?: "Unknown error"
                 _uiState.value = _uiState.value.copy(error = "Sync Failed: $errorMsg")
            }
        }
    }

    /**
     * User Action: Pull-to-Refresh.
     */
    fun refresh() {
       refreshCurrentFolder()
    }

    private fun syncFolder(path: String, isUserAction: Boolean) {
        val (owner, repo) = secretManager.getRepoInfo()
        if (owner == null || repo == null) return

        viewModelScope.launch {
            if (isUserAction) {
                _uiState.value = _uiState.value.copy(isRefreshing = true)
            }
            // Note: We do NOT set isLoading = true here. Data is visible from DB.

            val result = repository.refreshFolder(owner, repo, path)
            
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
        // Reset View & Draft
        _uiState.value = _uiState.value.copy(
            selectedFileName = file.name, 
            selectedFileContent = null,
            unsavedContent = null,
            hasUnsavedChanges = false,
            error = null,
            isEditing = false
        )

        // Observe Content Flow
        val fileId = file.path 
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
    
    fun closeFile() {
        contentObservationJob?.cancel()
        _uiState.value = _uiState.value.copy(
            selectedFileName = null,
            selectedFileContent = null,
            unsavedContent = null,
            hasUnsavedChanges = false,
            isEditing = false
        )
    }

    private fun syncFile(path: String, url: String, isUserAction: Boolean) {
         viewModelScope.launch {
            if (isUserAction) _uiState.value = _uiState.value.copy(isRefreshing = true)
            
            repository.refreshFileContent(path, url)
            
            if (isUserAction) _uiState.value = _uiState.value.copy(isRefreshing = false)
         }
    }

    fun toggleEditMode() {
        val isEditing = !_uiState.value.isEditing
        // If entering edit mode, ensure unsavedContent is initialized if null
        if (isEditing && _uiState.value.unsavedContent == null) {
             _uiState.value = _uiState.value.copy(
                 unsavedContent = _uiState.value.selectedFileContent ?: "",
                 hasUnsavedChanges = false // It matches DB currently
             )
        }
        _uiState.value = _uiState.value.copy(isEditing = isEditing)
    }

    fun onContentChanged(newContent: String) {
        _uiState.value = _uiState.value.copy(
            unsavedContent = newContent,
            hasUnsavedChanges = true
        )
    }

    fun refreshCurrentFile() {
        val fileName = _uiState.value.selectedFileName ?: return
        val file = _uiState.value.files.find { it.name == fileName } ?: return
        if (file.downloadUrl == null) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            repository.refreshFileContent(file.path, file.downloadUrl)
            _uiState.value = _uiState.value.copy(isRefreshing = false)
        }
    }

    fun saveFile() {
        val currentFileName = _uiState.value.selectedFileName ?: return
        val file = _uiState.value.files.find { it.name == currentFileName } ?: return
        val contentToSave = _uiState.value.unsavedContent ?: _uiState.value.selectedFileContent ?: return

        viewModelScope.launch {
            // 1. Save Local
            repository.saveFileLocally(file.path, contentToSave)
            
            // 2. Clear Draft & Exit Edit Mode
            _uiState.value = _uiState.value.copy(
                isEditing = false,
                unsavedContent = null,
                hasUnsavedChanges = false,
                selectedFileContent = contentToSave // Update view immediately
            )
            
            // 3. Trigger Push Immediately (Blocking)
            val (owner, repo) = secretManager.getRepoInfo()
            if (owner != null && repo != null) {
                _uiState.value = _uiState.value.copy(isSyncing = true)
                val result = repository.pushDirtyFiles(owner, repo)
                 if (result.isFailure) {
                    val msg = result.exceptionOrNull()?.message ?: "Unknown"
                    _uiState.value = _uiState.value.copy(error = "Commit Failed: $msg")
                }
                _uiState.value = _uiState.value.copy(isSyncing = false)
            }
        }
    }
    
    private fun syncDirtyFiles() {
        // Redundant with the new saveContent logic but helper kept if needed.
        val (owner, repo) = secretManager.getRepoInfo()
        if (owner == null || repo == null) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true)
            val result = repository.pushDirtyFiles(owner, repo)
            if (result.isFailure) {
                _uiState.value = _uiState.value.copy(error = "Push Failed: ${result.exceptionOrNull()?.message}")
            }
            _uiState.value = _uiState.value.copy(isSyncing = false)
        }
    }

    fun navigateUp() {
        navigateBack()
    }

    fun navigateBack(): Boolean {
        val currentPath = _uiState.value.currentPath
        if (currentPath.isNotEmpty()) {
            val parentPath = if(currentPath.contains("/")) currentPath.substringBeforeLast("/") else ""
            loadFolder(parentPath)
            return true
        }
        return false
    }

    /**
     * Handles Back Press logic for Folders.
     * Returns true if handled (navigated up), false if app should exit.
     */
    fun mapsBack(): Boolean {
        if (_uiState.value.currentPath.isNotEmpty()) {
            navigateUp()
            return true
        }
        return false
    }

    fun logout() {
        secretManager.clear()
    }

    fun toggleFocusMode() {
        _uiState.value = _uiState.value.copy(isFocusMode = !_uiState.value.isFocusMode)
    }

    private fun FileEntity.toDto() = GithubFileDto(name, path, type, downloadUrl)

    fun handleShareIntent(intent: android.content.Intent) {
        if (intent.action == android.content.Intent.ACTION_SEND) {
            val text: CharSequence? = intent.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT)
                ?: intent.getStringExtra(android.content.Intent.EXTRA_HTML_TEXT)
            
            if (text != null) {
                _uiState.value = _uiState.value.copy(isLoading = true)
                viewModelScope.launch {
                    val result = cloud.wafflecommons.pixelbrainreader.data.utils.ContentSanitizer.processSharedContent(text)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        importState = ImportState(result.title, result.markdownContent)
                    )
                }
            }
        }
    }

    fun confirmImport(filename: String, folder: String, content: String) {
        val fullPath = if (folder.isNotBlank()) "$folder/$filename" else filename
        viewModelScope.launch {
             // Save locally
             repository.saveFileLocally(fullPath, content)
             
             // Trigger Sync
             val (owner, repo) = secretManager.getRepoInfo()
             if (owner != null && repo != null) {
                 repository.pushDirtyFiles(owner, repo)
             }
             
             dismissImport()
             loadFolder(folder) 
        }
    }

    fun dismissImport() {
        _uiState.value = _uiState.value.copy(importState = null)
    }
}
