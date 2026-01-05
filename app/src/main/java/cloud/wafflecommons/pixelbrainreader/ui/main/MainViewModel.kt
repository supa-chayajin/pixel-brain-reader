package cloud.wafflecommons.pixelbrainreader.ui.main

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import cloud.wafflecommons.pixelbrainreader.data.remote.model.GithubFileDto
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyNoteRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.TemplateRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.AppThemeConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: FileRepository,
    private val dailyNoteRepository: DailyNoteRepository,
    private val templateRepository: TemplateRepository,
    private val secretManager: SecretManager,
    private val userPrefs: UserPreferencesRepository,
    private val geminiRagManager: cloud.wafflecommons.pixelbrainreader.data.ai.GeminiRagManager
) : ViewModel() {
    // Expose Theme Preference
    val themeConfig: StateFlow<AppThemeConfig> = userPrefs.themeConfig
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppThemeConfig.FOLLOW_SYSTEM
        )

    
    // UI State
    private val _uiState = MutableStateFlow(UiState(isLoading = true))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    
    // Add isRefreshing state
    data class UiState(
        val searchQuery: String = "", // Search/Filter Query
        val currentPath: String = "",
        val files: List<GithubFileDto> = emptyList(),
        val selectedFileContent: String? = null,
        val unsavedContent: String? = null, // The Draft
        val selectedFileName: String? = null,
        val selectedFilePath: String? = null, // CRITICAL FIX: Store full path explicitly
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
        val moveDialogCurrentPath: String = "", // Current path in Move Dialog
        val isExitPending: Boolean = false, // Signal to close the activity
        
        // Templates
        val availableTemplates: List<String> = emptyList(),
        val showCreateFileDialog: Boolean = false,
        
        // Navigation Signal (One-shot)
        val navigationTrigger: String? = null
    )

    data class ImportState(val title: String, val content: String)

    private var filesObservationJob: Job? = null
    private var contentObservationJob: Job? = null
    private var updatesObservationJob: Job? = null

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

        // Reactive File Updates (Debounced Refresh)
        updatesObservationJob = repository.fileUpdates
            .onEach { path: String ->
               // Smart Refresh: Only if relevant to current view
               val current = _uiState.value.currentPath
               // Simple logic: If inside current folder or is subfolder?
               // For now, just trigger db refresh if path starts with currentPath (or currentPath is root)
               if (current.isEmpty() || path.startsWith(current)) {
                   // Debounce is hard in onEach without operator.
                   // But since `observeDatabase` is a Flow observation, it should auto-update if DB changes!
                   // The `FileRepository.saveFileLocally` updates DB.
                   // So `fileDao.getFiles` flow receives update automatically.
                   // DO WE NEED MANUAL REFRESH?
                   // No, `getFilesFlow` is reactive (Room).
                   // BUT `contentObservationJob` uses `getFileContentFlow`.
                   // `UiState.selectedFilePath` determines what we are watching.
                   
                   // Log for debug
                   Log.d("MainViewModel", "File update received: $path. DB Flow should auto-update.")
               }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Called by Activity on Cold Start (savedInstanceState == null).
     * Ensures we only sync once per app session start.
     */
    /**
     * Called by Activity on Cold Start (savedInstanceState == null).
     * Ensures we only sync once per app session start.
     * NOW BLOCKING: Shows loading screen until sync completes.
     */
    fun performInitialSync() {
        if (isInitialSyncDone) return
        isInitialSyncDone = true
        
        val (owner, repo) = secretManager.getRepoInfo()
        if (owner == null || repo == null) {
            // No credentials, just stop loading
             _uiState.value = _uiState.value.copy(isLoading = false)
            return
        }

        viewModelScope.launch {
             // BLOCKING UI: Show Full Screen Loader
             _uiState.value = _uiState.value.copy(isLoading = true, isSyncing = true)
             
             try {
                 // Full Mirror Sync
                 val result = repository.syncRepository(owner, repo)
                 
                 if (result.isSuccess) {
                     _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Sync Complete ✅"))
                     // Reload DB/Cache to ensure freshness
                     loadFolder(_uiState.value.currentPath)
                 } else {
                     val msg = result.exceptionOrNull()?.message ?: "Unknown"
                     Log.w("MainViewModel", "Initial Sync failed: $msg")
                     _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Sync Failed ❌: Using local cache"))
                 }
             } catch (e: Exception) {
                 Log.e("MainViewModel", "Sync Error", e)
                 _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Sync Error ⚠️: ${e.message}"))
             } finally {
                 // Release UI
                 _uiState.value = _uiState.value.copy(isLoading = false, isSyncing = false)
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
            // Trigger Top Bar AND Pull-Physics
            _uiState.value = _uiState.value.copy(isRefreshing = true, isSyncing = true)
            
            try {
                // Hard Refresh / Authoritative Sync (Full Repo)
                val result = repository.syncRepository(owner, repo)
                
                if (result.isSuccess) {
                    _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Synced with Remote ✅"))
                    // CRITICAL: Reload Local Files
                    loadFolder(_uiState.value.currentPath)
                } else {
                    val errorMsg = result.exceptionOrNull()?.localizedMessage ?: "Unknown error"
                    _uiState.value = _uiState.value.copy(error = "Sync Failed: $errorMsg")
                    _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Sync Failed ❌: $errorMsg"))
                }
            } catch (e: Exception) {
                 _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Sync Error ⚠️: ${e.localizedMessage}"))
            } finally {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false, 
                    isSyncing = false
                )
            }
        }
    }

    /**
     * User Action: Pull-to-Refresh.
     */
    fun refresh() {
       refreshCurrentFolder()
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    private fun observeDatabase(path: String) {
        filesObservationJob?.cancel()
        
        // Use flatMapLatest to SWITCH streams based on query
        val searchFlow = _uiState.map { it.searchQuery }.distinctUntilChanged()
        
        filesObservationJob = searchFlow.flatMapLatest { query ->
            if (query.isBlank()) {
                // CASE 1: Normal View (Scoped to currentPath)
                repository.getFiles(path)
            } else {
                // CASE 2: Global Deep Search (Repo content-aware filter)
                repository.searchFiles(query)
            }
        }.onEach { entities ->
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
            selectedFilePath = file.path, // Store Full Path!
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

    fun closeFile() {
        contentObservationJob?.cancel()
        _uiState.value = _uiState.value.copy(
            selectedFileName = null,
            selectedFilePath = null,
            selectedFileContent = null,
            unsavedContent = null,
            hasUnsavedChanges = false,
            isEditing = false
        )
    }

    private fun observeFileContent(path: String) {
        contentObservationJob?.cancel()
        contentObservationJob = repository.getFileContentFlow(path)
            .onEach { content ->
                 // Self-Healing: Check for Corrupted JSON Data
                 if (content != null && content.trim().startsWith("{")) {
                     try {
                         val json = org.json.JSONObject(content)
                         if (json.has("content") && json.has("sha") && json.has("encoding")) {
                             val encoding = json.getString("encoding")
                             if (encoding.equals("base64", ignoreCase = true)) {
                                 val base64Content = json.optString("content", "")
                                 val cleanBase64 = base64Content.replace("\n", "")
                                 val decodedBytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                                 val cleanContent = String(decodedBytes, Charsets.UTF_8)
                                 
                                 _uiState.value = _uiState.value.copy(selectedFileContent = cleanContent)
                                 
                                 viewModelScope.launch {
                                     repository.saveFileLocally(path, cleanContent)
                                     Log.d("PixelBrain", "Self-Healed corrupted file (Aggressive): $path")
                                 }
                                 return@onEach
                             }
                         }
                     } catch (e: Exception) { }
                 }
                 _uiState.value = _uiState.value.copy(selectedFileContent = content)
            }
            .launchIn(viewModelScope)
    }

    private fun syncFile(path: String, url: String, isUserAction: Boolean) {
         viewModelScope.launch {
            if (isUserAction) _uiState.value = _uiState.value.copy(isRefreshing = true, isSyncing = true)
            repository.refreshFileContent(path, url)
            if (isUserAction) _uiState.value = _uiState.value.copy(isRefreshing = false, isSyncing = false)
         }
    }

    fun toggleEditMode() {
        val isEditing = !_uiState.value.isEditing
        if (isEditing && _uiState.value.unsavedContent == null) {
             _uiState.value = _uiState.value.copy(
                 unsavedContent = _uiState.value.selectedFileContent ?: "",
                 hasUnsavedChanges = false 
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
            _uiState.value = _uiState.value.copy(isRefreshing = true, isSyncing = true)
            repository.refreshFileContent(file.path, file.downloadUrl)
            _uiState.value = _uiState.value.copy(isRefreshing = false, isSyncing = false)
        }
    }

    // Event Channel
    private val _uiEvent = kotlinx.coroutines.flow.MutableSharedFlow<cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent>()
    val uiEvent = _uiEvent.asSharedFlow()

    fun saveFile() {
        val currentFileName = _uiState.value.selectedFileName ?: return
        val contentToSave = _uiState.value.unsavedContent ?: _uiState.value.selectedFileContent ?: return

        // Resolve Path: PRIORITIZE selectedFilePath (The Source of Truth)
        val path = _uiState.value.selectedFilePath ?: run {
             // Fallback Logic (Legacy/Recover)
             // If we don't have a path, assume context relative
             val file = _uiState.value.files.find { it.name == currentFileName }
             file?.path ?: run {
                 val parent = _uiState.value.currentPath
                 if (parent.isNotEmpty()) "$parent/$currentFileName" else currentFileName
             }
        }

        viewModelScope.launch {
            try {
                // 1. Save Local
                repository.saveFileLocally(path, contentToSave)
                
                // 2. Atomic UI Reset (Clear Dirty State)
                _uiState.value = _uiState.value.copy(
                    isEditing = false,
                    unsavedContent = null,
                    hasUnsavedChanges = false,
                    selectedFileContent = contentToSave // Update view immediately
                )
                
                // 3. Trigger Push Immediately (Blocking/Background)
                val (owner, repo) = secretManager.getRepoInfo()
                if (owner != null && repo != null) {
                    _uiState.value = _uiState.value.copy(isSyncing = true)
                    val result = repository.pushDirtyFiles(owner, repo)
                     if (result.isFailure) {
                        val msg = result.exceptionOrNull()?.message ?: "Unknown"
                        _uiState.value = _uiState.value.copy(error = "Commit Failed: $msg")
                        _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Git Sync Failed ❌: $msg"))
                    } else {
                        _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Synced with Git ✅"))
                    }
                    _uiState.value = _uiState.value.copy(isSyncing = false)
                }
            } catch (e: Exception) {
                 _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Save Failed ❌: ${e.message}"))
            }
        }
    }
    
    private fun syncDirtyFiles() {
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

    private fun FileEntity.toDto() = GithubFileDto(
        name = name,
        path = path, 
        type = type, 
        downloadUrl = downloadUrl,
        sha = sha,
        lastModified = localModifiedTimestamp ?: lastSyncedAt
    )

    // Flag to track if the current import session came from an external Intent
    private var isExternalShare = false

    fun handleShareIntent(intent: android.content.Intent) {
        if (intent.action == android.content.Intent.ACTION_SEND) {
            val htmlText = intent.getStringExtra(android.content.Intent.EXTRA_HTML_TEXT)
            val plainText = intent.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT)
            
            val textToProcess: CharSequence?
            val isMarkdown: Boolean

            if (htmlText != null) {
                textToProcess = cloud.wafflecommons.pixelbrainreader.data.utils.ContentSanitizer.htmlToMarkdown(htmlText)
                isMarkdown = true
            } else if (plainText != null) {
                textToProcess = plainText
                isMarkdown = !cloud.wafflecommons.pixelbrainreader.data.utils.ContentSanitizer.URL_REGEX.matches(plainText.trim())
            } else {
                textToProcess = null
                isMarkdown = false
            }
            
            if (textToProcess != null) {
                isExternalShare = true 
                _uiState.value = _uiState.value.copy(isLoading = true)
                viewModelScope.launch {
                    val result = cloud.wafflecommons.pixelbrainreader.data.utils.ContentSanitizer.processSharedContent(textToProcess, isMarkdown)
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
             repository.saveFileLocally(fullPath, content)
             
             if (isExternalShare) {
                 dismissImport()
                 _uiState.value = _uiState.value.copy(
                     userMessage = "Imported & Saved",
                     isExitPending = true 
                 )
             } else {
                 val newDto = GithubFileDto(
                    name = filename,
                    path = fullPath,
                    type = "file", 
                    downloadUrl = null,
                    lastModified = System.currentTimeMillis()
                )
                 dismissImport()
                 loadFile(newDto)
                 _uiState.value = _uiState.value.copy(userMessage = "Imported successfully")
             }
             
              val (owner, repo) = secretManager.getRepoInfo()
              if (owner != null && repo != null) {
                   _uiState.value = _uiState.value.copy(isSyncing = true)
                   try {
                       val result = repository.pushDirtyFiles(owner, repo)
                       if (result.isSuccess) {
                           _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Synced with Git ✅"))
                       } else {
                           val msg = result.exceptionOrNull()?.message ?: "Unknown"
                           _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Git Sync Failed ❌: $msg"))
                       }
                   } catch (e: Exception) {
                       _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Git Sync Failed ❌: ${e.message}"))
                   }
                   _uiState.value = _uiState.value.copy(isSyncing = false)
              }
             isExternalShare = false
        }
    }

    fun dismissImport() {
        _uiState.value = _uiState.value.copy(importState = null)
        isExternalShare = false 
    }

    fun userMessageShown() {
        _uiState.value = _uiState.value.copy(userMessage = null)
    }

    fun consumeNavigationTrigger() {
        _uiState.value = _uiState.value.copy(navigationTrigger = null)
    }
    
    fun renameFile(newName: String, targetFile: GithubFileDto? = null) {
        // Support renaming specific file (swipe) or current selected (menu)
        val fileToRename = targetFile?.name ?: _uiState.value.selectedFileName ?: return
        // Fix: Use selectedFilePath if targeting current
        val path = if (targetFile != null) targetFile.path else _uiState.value.selectedFilePath ?: _uiState.value.files.find { it.name == fileToRename }?.path ?: return
        
        val isDirectory = _uiState.value.files.find { it.path == path }?.type == "dir" // safer check
        
        
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
        
        // 1. Check for valid move (destination != source)
        if (currentPath == newPath) {
             _uiState.value = _uiState.value.copy(userMessage = "Item is already in this folder")
             return
        }

        // 2. Check if moving to same parent (Redundant but safe)
        val currentParent = if(file.path.contains("/")) file.path.substringBeforeLast("/") else ""
        if (currentParent == targetFolder) {
             _uiState.value = _uiState.value.copy(userMessage = "Item is already in this folder")
             return
        }
        
        val (owner, repo) = secretManager.getRepoInfo()

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
             _uiState.value = _uiState.value.copy(
                isSyncing = true,
                userMessage = "Moving to ${if(targetFolder.isEmpty()) "Root" else targetFolder}..."
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
                // Rule 1: Exclude hidden folders (start with .)
                if (folderPath.split("/").any { it.startsWith(".") }) return@filter false

                // Rule 2: Cannot move folder into itself or its descendants
                if (targetFile.type == "dir") {
                    if (folderPath == targetFile.path) return@filter false
                    if (folderPath.startsWith("${targetFile.path}/")) return@filter false
                }
                
                // Rule 3: Allow current parent (Context) - REMOVED the check that excluded currentParent
                
                true
            }.sorted()

            // Initialize Dialog at Root (or start at current parent for better UX? No, start at root is safer navigation)
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

    fun openCreateFileDialog() {
        viewModelScope.launch {
            val templates = templateRepository.getAvailableTemplates()
            _uiState.value = _uiState.value.copy(
                availableTemplates = templates,
                showCreateFileDialog = true
            )
        }
    }

    fun dismissCreateFileDialog() {
        _uiState.value = _uiState.value.copy(showCreateFileDialog = false)
    }

    fun createNewFile(filename: String? = null, templateName: String? = null) {
        val parentPath = _uiState.value.currentPath
        val finalName = if (filename.isNullOrBlank()) "Untitled_${System.currentTimeMillis()}.md" else if(filename.endsWith(".md")) filename else "$filename.md"
        
        val fullPath = if (parentPath.isNotEmpty()) "$parentPath/$finalName" else finalName
        
        viewModelScope.launch {
            // 1. Prepare Content
            var content = ""
            if (!templateName.isNullOrBlank()) {
                 // Fetch Template Content
                 // We reuse Repository logic (or access Dao via Repo?)
                 // FileRepository.getFileContentFlow works for reading, but we need single shot.
                 // We can use the same path logic as TemplateRepository (it knows folder).
                 // For now, let's use a public method on FileRepository if available?
                 // Or just Read via Repo.
                 val templatePath = "${TemplateRepository.TEMPLATE_FOLDER}/$templateName"
                 val templateContent = repository.getFileContentFlow(templatePath).firstOrNull() ?: ""
                 
                 // Apply Engine
                 content = cloud.wafflecommons.pixelbrainreader.data.utils.TemplateEngine.apply(templateContent, finalName.substringBeforeLast("."))
            }

            // 2. Synchronous Creation (Disk/DB)
            repository.saveFileLocally(fullPath, content)
            
            // 3. Close Dialog
             _uiState.value = _uiState.value.copy(showCreateFileDialog = false)

            // 4. Immediate UI Update (Don't wait for DB Observer)
            val newDto = GithubFileDto(
                name = finalName, 
                path = fullPath, 
                type = "file", 
                downloadUrl = null,
                // isDirty not in DTO, handled by UI state hasUnsavedChanges
                lastModified = System.currentTimeMillis()
            )
            
            // 5. Open immediately
            loadFile(newDto)
            
            // 6. Set Edit Mode & Ready State
            _uiState.value = _uiState.value.copy(
                isEditing = true,
                unsavedContent = content, // Ready for typing (pre-filled with template)
                hasUnsavedChanges = true // It is a new file
            )
        }
    }

    /**
     * Folder Insight / RAG Pivot
     * Analyzes current folder contents and generates an index.
     */
    fun analyzeCurrentFolder() {
        val files = _uiState.value.files.filter { it.type == "file" }.take(10) // Limit to 10 files
        if (files.isEmpty()) {
            _uiState.value = _uiState.value.copy(userMessage = "No files to analyze here.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, userMessage = "AI analyzing folder...")
            
            // 1. Fetch Content
            val fileContexts = files.mapNotNull { file ->
                val content = repository.getFileContentFlow(file.path).firstOrNull() 
                if (content != null) file.name to content else null
            }
            
            // 2. Call Gemini
            val rawSummary = geminiRagManager.analyzeFolder(fileContexts)
            
            // Sanitize Output: Remove potential Markdown code fences
            val summary = rawSummary
                .replace(Regex("^```markdown\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^```\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*```$"), "")
                .trim()
            
            // 3. Show Result as Virtual File (Detail Pane)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                importState = null,
                selectedFileName = "Folder_Insight.md",
                selectedFileContent = "", // Disk state is empty
                unsavedContent = summary, // Current state is the summary (Draft)
                hasUnsavedChanges = true,
                isEditing = false // View Mode
            )
        }
    }

    fun appendContent(text: String) {
        val currentContent = _uiState.value.unsavedContent ?: _uiState.value.selectedFileContent ?: ""
        val newContent = if (currentContent.isBlank()) text else "$currentContent\n\n$text"
        
        onContentChanged(newContent)
        
        // Ensure we are in Edit Mode to see the changes and Save button
        if (!_uiState.value.isEditing) {
            _uiState.value = _uiState.value.copy(isEditing = true)
        }
    }

    fun onWikiLinkClick(linkText: String) {
        // 1. Aggressive Cleaning
        var target = linkText.replace(Regex("[\\[\\]]"), "") // Remove [[ ]]
        target = target.split("|")[0] // Remove alias
        target = target.trim()
        target = target.removeSuffix("/") // Critical Fix for "Folder/" links
        
        val cleanTarget = target // Final clean value

        viewModelScope.launch {
            // STEP 2: Folder Detection (PRIORITY)
            val allFolders = repository.getAllFolders()
            
            // Check for Exact Match (or "Root/Target" suffix)
            val matchingFolder = allFolders.find { 
                it.equals(cleanTarget, ignoreCase = true) || it.endsWith("/$cleanTarget", ignoreCase = true) 
            }
            
            if (matchingFolder != null) {
                // Folder Found: Update List, Keep Content Open
                loadFolder(matchingFolder)
                _uiState.value = _uiState.value.copy(userMessage = "📂 Opened ${matchingFolder.substringAfterLast("/")}")
                return@launch
            }

            // STEP 3: File Check (Deterministic Resolver)
            // Note: resolveLink is Strict (Exact or .md)
            val entity = repository.resolveLink(cleanTarget)
            
            if (entity != null) {
                if (entity.type == "dir") {
                    // Redundant Safety Fallback
                    loadFolder(entity.path)
                    _uiState.value = _uiState.value.copy(userMessage = "📂 Opened ${entity.name}")
                } else {
                    // File Found
                    loadFile(entity.toDto())
                }
                return@launch
            }

            // STEP 4: Fallback
            _uiState.value = _uiState.value.copy(userMessage = "Target '$cleanTarget' not found")
        }
    }

    /**
     * Saves chat content to a new file in 00_Inbox.
     * Non-blocking UI (Background save).
     */
    fun saveChatToInbox(content: String) {
        android.util.Log.d("PixelBrain", "saveChatToInbox called with length: ${content.length}")
        viewModelScope.launch {
            val folderName = "00_Inbox"
            
            // Ensure folder exists in DB (so it shows up in UI immediately)
            repository.createLocalFolder(folderName)
            
            // Format: AI_Note_YYYYMMDD_HHmmss.md
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            val filename = "AI_Note_$timestamp.md"
            val fullPath = "$folderName/$filename"
            
            // 1. Save Local
            repository.saveFileLocally(fullPath, content)
            
            // 2. Feedback
            _uiState.value = _uiState.value.copy(userMessage = "Saved to $folderName")
            
            // 3. Sync
             val (owner, repo) = secretManager.getRepoInfo()
             if (owner != null && repo != null) {
                  repository.pushDirtyFiles(owner, repo)
             }
        }
    }

    /**
     * FEATURE C: Daily Note
     * Triggers logic to open or create today's journal entry.
     */
    fun onTodayClicked(pathOverride: String? = null, startEditing: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                // 1. Get Path (This creates file if needed)
                val notePath = pathOverride ?: dailyNoteRepository.getOrCreateTodayNote()
                val noteName = notePath.substringAfterLast("/")
                
                // 2. Open File
                // Construct Pseudo-DTO (Since we know path & name)
                val dto = GithubFileDto(
                     name = noteName,
                     path = notePath,
                     type = "file",
                     downloadUrl = null, // Local
                     sha = null,
                     lastModified = System.currentTimeMillis()
                )
                
                loadFile(dto)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isEditing = startEditing, // Open in Edit Mode if requested
                    navigationTrigger = "home" // Step B (THE FIX): Force Navigation to Repo Tab
                )
            } catch (e: Exception) {
                Log.e("DailyNote", "Failed to open today", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to open Daily Note: ${e.message}"
                )
            }
        }
    }
}
