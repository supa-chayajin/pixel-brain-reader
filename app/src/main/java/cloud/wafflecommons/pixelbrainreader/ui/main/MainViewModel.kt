package cloud.wafflecommons.pixelbrainreader.ui.main

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import cloud.wafflecommons.pixelbrainreader.data.remote.model.GithubFileDto
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: FileRepository,
    private val secretManager: SecretManager,
    private val userPrefs: UserPreferencesRepository
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
        val userMessage: String? = null, // Success/Info messages
        val isFocusMode: Boolean = false,
        val isEditing: Boolean = false, // Edit Mode
        val isSyncing: Boolean = false,

        val hasUnsavedChanges: Boolean = false,
        val importState: ImportState? = null,
        
        val listPaneWidth: Float = 360f, // Default width

        // Derived property for Move Dialog
        val folders: List<String> = emptyList(), // Legacy - to be replaced by availableMoveDestinations
        val availableMoveDestinations: List<String> = emptyList(), // Filtered list for Move Dialog
        val moveDialogCurrentPath: String = "" // Current path in Move Dialog
    )

    data class ImportState(val title: String, val content: String)

    private var filesObservationJob: Job? = null
    private var contentObservationJob: Job? = null

    private var isInitialSyncDone = false

    init {
        // Initial load of root folder (Database Only)
        loadFolder("")
        
        // Observe Preferences
        userPrefs.listPaneWidth
            .onEach { width ->
                _uiState.value = _uiState.value.copy(listPaneWidth = width)
            }
            .launchIn(viewModelScope)
    }

    /**
     * Called by Activity on Cold Start (savedInstanceState == null).
     * Ensures we only sync once per app session start.
     */
    fun performInitialSync() {
        if (isInitialSyncDone) return
        isInitialSyncDone = true
        
        val (owner, repo) = secretManager.getRepoInfo()
        if (owner == null || repo == null) return

        viewModelScope.launch {
             _uiState.value = _uiState.value.copy(isSyncing = true)
             // Full Mirror Sync
             val result = repository.syncRepository(owner, repo)
             _uiState.value = _uiState.value.copy(isSyncing = false)
             
             if (result.isFailure) {
                 Log.w("MainViewModel", "Initial Sync failed: ${result.exceptionOrNull()?.message}")
             }
        }
    }

    fun updateListPaneWidth(width: Float) {
        // Immediate UI update for responsiveness
       _uiState.value = _uiState.value.copy(listPaneWidth = width)
       viewModelScope.launch {
           userPrefs.setListPaneWidth(width)
       }
    }

    /**
     * Navigation Logic (Offline-First).
     * 1. Updates Path.
     * 2. Observes DB immediately (Instant UI).
     * NO AUTO-SYNC on navigation to prevent frequent blocking/network usage.
     */
    fun loadFolder(path: String) {
        _uiState.value = _uiState.value.copy(currentPath = path, error = null)
        
        // Instant DB Access
        observeDatabase(path)
    }

    fun refreshCurrentFolder() {
        val (owner, repo) = secretManager.getRepoInfo()
        if (owner == null || repo == null) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, isRefreshing = true)
            
            // Hard Refresh / Authoritative Sync (Full Repo)
            val result = repository.syncRepository(owner, repo)
            
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

    private fun observeDatabase(path: String) {
        filesObservationJob?.cancel()
        filesObservationJob = repository.getFiles(path)
            .onEach { entities ->
                val dtos = entities
                    .map { it.toDto() }
                    .filter { !it.name.startsWith(".") }
                    .sortedWith(compareBy({ it.type != "dir" }, { it.name }))

                // Extract unique folders for Move Dialog (simple flat list for now)
                val allFolders = entities
                    .filter { it.type == "dir" }
                    .map { it.path }
                    .distinct()
                    .sorted()

                _uiState.value = _uiState.value.copy(files = dtos, folders = allFolders)
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

    fun userMessageShown() {
        _uiState.value = _uiState.value.copy(userMessage = null)
    }
             
    fun renameFile(newName: String, targetFile: GithubFileDto? = null) {
        // Support renaming specific file (swipe) or current selected (menu)
        val fileToRename = targetFile?.name ?: _uiState.value.selectedFileName ?: return
        val path = _uiState.value.files.find { it.name == fileToRename }?.path ?: return
        val isDirectory = _uiState.value.files.find { it.name == fileToRename }?.type == "dir"
        
        // Construct new path. 
        val parentPath = if(path.contains("/")) path.substringBeforeLast("/") else ""
        
        // Logic Fix: Robust extension handling
        val finalNewName = if (isDirectory) {
            newName // Directories: Never append extension
        } else {
            if (newName.endsWith(".md", ignoreCase = true)) newName else "$newName.md"
        }
        
        val finalNewPath = if(parentPath.isNotEmpty()) "$parentPath/$finalNewName" else finalNewName
        
        if (finalNewPath == path) return
        
        val (owner, repo) = secretManager.getRepoInfo()

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                isSyncing = true,
                userMessage = "Renaming ${if(isDirectory) "folder" else "file"}..."
            )
            
            val result = repository.renameAndSync(path, finalNewPath, owner, repo)
            
            if (result.isSuccess) {
                // Update UI State locally for immediate feedback if needed (though DB observation updates list)
                 if (targetFile == null) {
                    _uiState.value = _uiState.value.copy(selectedFileName = finalNewName)
                }
                _uiState.value = _uiState.value.copy(
                    isSyncing = false, 
                    userMessage = "Renamed successfully"
                )
            } else {
                 Log.e("PixelBrain", "Rename Failed", result.exceptionOrNull())
                 _uiState.value = _uiState.value.copy(
                     isSyncing = false,
                     error = "Rename Failed: ${result.exceptionOrNull()?.message}"
                 )
            }
        }
    }

    fun moveFile(file: GithubFileDto, targetFolder: String) {
        val currentPath = file.path
        val newPath = if (targetFolder.isEmpty()) file.name else "$targetFolder/${file.name}" // Name stays same
        
        if (currentPath == newPath) return
        
        val (owner, repo) = secretManager.getRepoInfo()

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
             _uiState.value = _uiState.value.copy(
                isSyncing = true,
                userMessage = "Moving to $targetFolder..."
            )

            val result = repository.renameAndSync(currentPath, newPath, owner, repo)
            
             if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isSyncing = false, 
                    userMessage = "Moved successfully"
                )
            } else {
                 _uiState.value = _uiState.value.copy(
                     isSyncing = false,
                     error = "Move Failed: ${result.exceptionOrNull()?.message}"
                 )
            }
        }
    }

    // Cache for valid move destinations during a move operation
    private var cachedValidMoveDestinations: List<String> = emptyList()

    /**
     * Smart Move Preparation.
     * Fetches folders and filters unavailable destinations.
     */
    fun prepareMove(targetFile: GithubFileDto) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val allFolders = repository.getAllFolders()
            val currentParent = if(targetFile.path.contains("/")) targetFile.path.substringBeforeLast("/") else ""
            
            // Filter global list once
            cachedValidMoveDestinations = allFolders.filter { folderPath ->
                // 1. Cannot move to self (if it's a folder)
                if (folderPath == targetFile.path) return@filter false
                
                // 2. Cannot move into own descendant (if target is folder)
                if (targetFile.type == "dir" && folderPath.startsWith("${targetFile.path}/")) return@filter false
                
                // 3. Cannot move to current location (Parent)
                if (folderPath == currentParent) return@filter false
                
                true
            }.sorted()

            // Initialize Dialog at Root
            updateMoveDialogContent("")
        }
    }
    
    fun navigateToMoveFolder(path: String) {
        updateMoveDialogContent(path)
    }
    
    fun navigateUpMoveFolder() {
        val current = _uiState.value.moveDialogCurrentPath
        if (current.isNotEmpty()) {
            val parent = if(current.contains("/")) current.substringBeforeLast("/") else ""
            updateMoveDialogContent(parent)
        }
    }
    
    private fun updateMoveDialogContent(currentPath: String) {
        // Filter cached list to show only DIRECT children of currentPath
        val displayedFolders = cachedValidMoveDestinations.filter { folderPath ->
            if (currentPath.isEmpty()) {
                // Root: Show only top-level folders (no slashes)
                !folderPath.contains("/")
            } else {
                // Subfolder: Show direct children (starts with path/, no extra slashes after)
                folderPath.startsWith("$currentPath/") && 
                !folderPath.substringAfter("$currentPath/").contains("/")
            }
        }.map { 
            // Return only the folder name for display if needed, but we store full path
            // Actually, let's return full path but UI can format it
            it
        }

        _uiState.value = _uiState.value.copy(
            moveDialogCurrentPath = currentPath,
            availableMoveDestinations = displayedFolders
        )
    }

    fun createNewFile() {
        val parentPath = _uiState.value.currentPath
        val name = "Untitled_${System.currentTimeMillis()}.md"
        val fullPath = if (parentPath.isNotEmpty()) "$parentPath/$name" else name
        
        viewModelScope.launch {
            repository.saveFileLocally(fullPath, "")
            delay(100)
            val newDto = GithubFileDto(name, fullPath, "file", null)
            loadFile(newDto)
            toggleEditMode()
        }
    }
}
