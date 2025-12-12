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
    private val secretManager: SecretManager,
    private val userPrefs: UserPreferencesRepository,
    private val geminiRagManager: cloud.wafflecommons.pixelbrainreader.data.ai.GeminiRagManager
) : ViewModel() {
    // Expose Theme Preference
    val themeMode: StateFlow<String> = userPrefs.themeMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = "SYSTEM" // Safe default
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
        val isExitPending: Boolean = false // Signal to close the activity
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
            // Trigger Top Bar AND Pull-Physics
            _uiState.value = _uiState.value.copy(isRefreshing = true, isSyncing = true)
            
            // Hard Refresh / Authoritative Sync (Full Repo)
            val result = repository.syncRepository(owner, repo)
            
            _uiState.value = _uiState.value.copy(
                isLoading = false, // clear loading if it was set
                isRefreshing = false, 
                isSyncing = false
            )
            
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
                 // Self-Healing: Check for Corrupted JSON Data
                 // RELAXED CHECK: If it looks like a JSON Object, try to parse it.
                 // The previous check failed because of whitespace differences in "encoding":"base64".
                 if (content != null && content.trim().startsWith("{")) {
                     try {
                         // Corrupted Data Detected! Repairs...
                         val json = org.json.JSONObject(content)
                         // Check for GitHub API signature fields
                         if (json.has("content") && json.has("sha") && json.has("encoding")) {
                             val encoding = json.getString("encoding")
                             if (encoding.equals("base64", ignoreCase = true)) {
                                 val base64Content = json.optString("content", "")
                                 // Handle potential newlines in Base64 string from GitHub
                                 val cleanBase64 = base64Content.replace("\n", "")
                                 val decodedBytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                                 val cleanContent = String(decodedBytes, Charsets.UTF_8)
                                 
                                 // 1. Show Clean Content Immediately
                                 _uiState.value = _uiState.value.copy(selectedFileContent = cleanContent)
                                 
                                 // 2. Persist Repair (Async)
                                 viewModelScope.launch {
                                     repository.saveFileLocally(path, cleanContent)
                                     Log.d("PixelBrain", "Self-Healed corrupted file (Aggressive): $path")
                                 }
                                 return@onEach
                             }
                         }
                     } catch (e: Exception) {
                         // Not JSON or Not GitHub Blob JSON -> Treat as normal text
                     }
                 }
                 
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
            if (isUserAction) _uiState.value = _uiState.value.copy(isRefreshing = true, isSyncing = true)
            
            repository.refreshFileContent(path, url)
            
            if (isUserAction) _uiState.value = _uiState.value.copy(isRefreshing = false, isSyncing = false)
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
            _uiState.value = _uiState.value.copy(isRefreshing = true, isSyncing = true)
            repository.refreshFileContent(file.path, file.downloadUrl)
            _uiState.value = _uiState.value.copy(isRefreshing = false, isSyncing = false)
        }
    }

    fun saveFile() {
        val currentFileName = _uiState.value.selectedFileName ?: return
        val contentToSave = _uiState.value.unsavedContent ?: _uiState.value.selectedFileContent ?: return

        // Resolve Path: Use Repo File or Construct Virtual Path
        val file = _uiState.value.files.find { it.name == currentFileName }
        val path = file?.path ?: run {
            val parent = _uiState.value.currentPath
            if (parent.isNotEmpty()) "$parent/$currentFileName" else currentFileName
        }

        viewModelScope.launch {
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
                    // Don't show error if it's just network offline, maybe? 
                    // For now, consistent with existing logic
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
                // Priority 1: HTML -> Convert to Markdown
                textToProcess = cloud.wafflecommons.pixelbrainreader.data.utils.ContentSanitizer.htmlToMarkdown(htmlText)
                isMarkdown = true
            } else if (plainText != null) {
                // Priority 2: Plain Text
                textToProcess = plainText
                // If it looks like a URL, allow normal processing (fetch).
                // Otherwise, treat as raw text (markdown) to preserve newlines.
                isMarkdown = !cloud.wafflecommons.pixelbrainreader.data.utils.ContentSanitizer.URL_REGEX.matches(plainText.trim())
            } else {
                textToProcess = null
                isMarkdown = false
            }
            
            if (textToProcess != null) {
                isExternalShare = true // Mark as external
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
             // 1. Save locally (Synchronous)
             repository.saveFileLocally(fullPath, content)
             
             // 2. Handle Finish or Open
             if (isExternalShare) {
                 // EXTERNAL FLOW: Save & Exit
                 dismissImport()
                 _uiState.value = _uiState.value.copy(
                     userMessage = "Imported & Saved",
                     isExitPending = true // Signal View to finish activity
                 )
             } else {
                 // INTERNAL FLOW: Save & Open
                 val newDto = GithubFileDto(
                    name = filename,
                    path = fullPath,
                    type = "file", 
                    downloadUrl = null,
                    // isDirty removed
                    lastModified = System.currentTimeMillis()
                )
                 
                 // Close Import Overlay first to reveal underlying UI
                 dismissImport()
                 
                 // Open the file
                 loadFile(newDto)
                 
                 // Show Success
                 _uiState.value = _uiState.value.copy(userMessage = "Imported successfully")
             }
             
             // 3. Trigger Push (Background)
             val (owner, repo) = secretManager.getRepoInfo()
             if (owner != null && repo != null) {
                  _uiState.value = _uiState.value.copy(isSyncing = true)
                  repository.pushDirtyFiles(owner, repo)
                  _uiState.value = _uiState.value.copy(isSyncing = false)
             }
             
             // Reset flag
             isExternalShare = false
        }
    }

    fun dismissImport() {
        _uiState.value = _uiState.value.copy(importState = null)
        isExternalShare = false // Reset on cancel too
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

    fun createNewFile() {
        val parentPath = _uiState.value.currentPath
        val name = "Untitled_${System.currentTimeMillis()}.md"
        val fullPath = if (parentPath.isNotEmpty()) "$parentPath/$name" else name
        
        viewModelScope.launch {
            // 1. Synchronous Creation (Disk/DB)
            repository.saveFileLocally(fullPath, "")
            
            // 2. Immediate UI Update (Don't wait for DB Observer)
            val newDto = GithubFileDto(
                name = name, 
                path = fullPath, 
                type = "file", 
                downloadUrl = null,
                // isDirty not in DTO, handled by UI state hasUnsavedChanges
                lastModified = System.currentTimeMillis()
            )
            
            // 3. Open immediately
            loadFile(newDto)
            
            // 4. Set Edit Mode & Ready State
            _uiState.value = _uiState.value.copy(
                isEditing = true,
                unsavedContent = "", // Ready for typing
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
}
